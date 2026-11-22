#!/bin/bash
#Set the docker to use the needed ip address
docker network create --subnet=10.0.0.0/24 onos-net 2>/dev/null || true
#Renove the docker image
docker rm -f onos 2>/dev/null || true

#Redownload and start the docker image
docker run -d \
  --name onos \
  --net onos-net \
  -p 6653:6653 \
  -p 8181:8181 \
  -p 8101:8101 \
  onosproject/onos:2.7.0

#Clean up the mininet machines, while waiting for docker to start
sudo mn -c

echo "Waiting for ONOS REST API to be ready"
#Wait until ONOS has started up
ONOS_READY=false

for attempt in $(seq 1 24); do
    HTTP_CODE=$(docker exec onos curl -s -o /dev/null -w "%{http_code}"       -u onos:rocks http://localhost:8181/onos/v1/applications 2>/dev/null)

    if [ "$HTTP_CODE" = "200" ]; then
        ONOS_READY=true
        break
    fi

    echo "ONOS not ready (attempt $attempt/24)"
    sleep 5
done

if [ "$ONOS_READY" != "true" ]; then
    echo "ERROR: ONOS REST API did not become ready within the timeout." >&2
    exit 1
fi

#Sometimes, the API is not ready even though the web interface is, so add a further 5 seconds delay
echo "Final sleep, make sure all services are running"
sleep 5

#Activate needed services
#Onos cannot activate multiple services in one call, so we do multiple calls
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.openflow
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.ovsdb
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.drivers
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.fwd

#Install my custom monitor
echo "Copy ONOS monitor"
docker cp onos_group_8-1.0-SNAPSHOT.oar onos:/root/monitor.oar
echo "Install ONOS monitor"
docker exec onos /root/onos/bin/onos-app localhost install! /root/monitor.oar

mkdir -p pcap
MONITOR_LOG="output/onos_monitor.log"
mkdir -p output
echo "Write ONOS monitor details to $MONITOR_LOG"
docker exec onos sh -c 'echo $$ > /tmp/monitor_tail.pid; exec tail -n 0 -F /root/onos/apache-karaf-*/data/log/karaf.log' \
  | grep --line-buffered -E "ANOMALY|RECOVERED|SUMMARY|LINK_|DEVICE_" > "$MONITOR_LOG" &


TEMP_PCAP="/tmp/sdn_capture.pcap"
sudo rm -f /tmp/group8_tshark.pid
echo "Launch tshark"

sudo bash -c '
  echo $$ > /tmp/group8_tshark.pid
  exec tshark -i any -f "not port 22" -s 256 \
    -w /tmp/sdn_capture.pcap
' > /tmp/tshark_debug.log 2>&1 &
TSHARK_PID=$!

echo "Sleep for 5, wait for tshark"
sleep 5

#Is tshark running?
if [ -f /tmp/group8_tshark.pid ] && sudo kill -0 "$(cat /tmp/group8_tshark.pid)" 2>/dev/null; then
  echo "tshark is running successfully (PID: $(cat /tmp/group8_tshark.pid))"
  #See if ny program ran, sometimes the command is too quick
  #read -p "Press [Enter] to continue, is tshark running?"

  uv sync
  #Start the script
  sudo uv run main.py

  echo "Sleeping for 5, make sure tshark has finished"
  sleep 5

  # Stop TShark after Mininet exits and wait for capture to finish
  if [ -f /tmp/group8_tshark.pid ]; then
    sudo kill -INT "$(cat /tmp/group8_tshark.pid)" 2>/dev/null || true
  fi
  wait "$TSHARK_PID" 2>/dev/null || true
  sudo rm -f /tmp/group8_tshark.pid

  echo "Move the capture to its destination"
  if [ -f "$TEMP_PCAP" ]; then
    if sudo mv "$TEMP_PCAP" pcap/; then
      echo "Capture complete: pcap/$(basename "$TEMP_PCAP")"
    else
      echo "ERROR: Failed to move PCAP file." >&2
    fi
  else
    echo "WARNING: PCAP file was not created." >&2
  fi
  ls -lh pcap/
else
  echo "tshark failed to start!"
  cat /tmp/tshark_debug.log
  sudo rm -f /tmp/group8_tshark.pid
fi

#Stop the background tail inside the container, grep then exits with it
docker exec onos sh -c 'if [ -f /tmp/monitor_tail.pid ]; then
  kill "$(cat /tmp/monitor_tail.pid)" 2>/dev/null || true
  rm -f /tmp/monitor_tail.pid
fi' 2>/dev/null || true
echo "ONOS monitor log: $MONITOR_LOG"
