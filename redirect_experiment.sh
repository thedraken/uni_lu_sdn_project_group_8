#!/bin/bash
#Brings up ONOS and runs the redirect scenario, then collects the results
#Run from ~/group_8 after the oar is built, like setup_script.sh

OAR="onos_group_8-1.0-SNAPSHOT.oar"
RESULTS="results/redirect"
mkdir -p "$RESULTS" pcap

#Start the docker network and ONOS
docker network create --subnet=10.0.0.0/24 onos-net 2>/dev/null || true
docker rm -f onos 2>/dev/null || true
docker run -d --name onos --net onos-net \
  -p 6653:6653 -p 8181:8181 -p 8101:8101 \
  onosproject/onos:2.7.0

sudo /usr/bin/python3.13 /usr/bin/mn -c

echo "Waiting for ONOS REST API"
ONOS_READY=false
for attempt in $(seq 1 24); do
    CODE=$(docker exec onos curl -s -o /dev/null -w "%{http_code}" \
        -u onos:rocks http://localhost:8181/onos/v1/applications 2>/dev/null)
    if [ "$CODE" = "200" ]; then ONOS_READY=true; break; fi
    echo "ONOS not ready ($attempt/24)"; sleep 5
done
if [ "$ONOS_READY" != "true" ]; then
    echo "ERROR: ONOS did not start" >&2; exit 1
fi
sleep 5

#Activate the needed apps and install our monitor
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.openflow
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.ovsdb
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.drivers
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.fwd
docker cp "$OAR" onos:/root/monitor.oar
docker exec onos /root/onos/bin/onos-app localhost install! /root/monitor.oar
sleep 5

#Write the monitor log
MONITOR_LOG="$RESULTS/onos_monitor.log"
docker exec onos sh -c 'echo $$ > /tmp/mon.pid; exec tail -n 0 -F /root/onos/apache-karaf-*/data/log/karaf.log' \
  | grep --line-buffered -E "ANOMALY|RECOVERED|SUMMARY|LINK_|DEVICE_|isolated|reinstated" > "$MONITOR_LOG" &

#Capture only the SDN and control traffic, not the host network
sudo rm -f /tmp/redirect_tshark.pid
sudo bash -c '
  echo $$ > /tmp/redirect_tshark.pid
  exec tshark -i any -s 256 \
    -f "net 10.0.0.0/8 or port 6653 or port 6633 or ether proto 0x88cc" \
    -w /tmp/redirect_capture.pcap
' > /tmp/redirect_tshark.log 2>&1 &
TSHARK_PID=$!
sleep 5

#Baseline first, then the attack
export REDIRECT_RESULTS="$RESULTS/baseline"
echo "=== BASELINE run ==="
sudo -E /usr/bin/python3.13 redirect_main.py baseline

export REDIRECT_RESULTS="$RESULTS/attack"
echo "=== ATTACK run ==="
sudo -E /usr/bin/python3.13 redirect_main.py

#Stop the capture
sleep 3
if [ -f /tmp/redirect_tshark.pid ]; then
  sudo kill -INT "$(cat /tmp/redirect_tshark.pid)" 2>/dev/null || true
fi
wait "$TSHARK_PID" 2>/dev/null || true
sudo rm -f /tmp/redirect_tshark.pid
[ -f /tmp/redirect_capture.pcap ] && sudo mv /tmp/redirect_capture.pcap pcap/redirect_capture.pcap

#Stop the monitor tail
docker exec onos sh -c 'if [ -f /tmp/mon.pid ]; then kill "$(cat /tmp/mon.pid)" 2>/dev/null || true; fi' 2>/dev/null || true

echo
echo "Done. Results in $RESULTS/"
