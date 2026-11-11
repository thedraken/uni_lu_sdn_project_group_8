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
until [ "$(docker exec onos curl -s -o /dev/null -w "%{http_code}" -u onos:rocks http://localhost:8181/onos/v1/applications)" -eq 200 ]; do
  echo "Wait 5 more seconds"
  sleep 5
done

#Sometimes, the API is not ready even though the web interface is, so add a further 5 seconds delay
echo "Final sleep, make sure all services are running"
sleep 5

#Activate needed services
#Onos cannot activate multiple services in one call, so we do multiple calls
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.openflow
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.ovsdb
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.drivers
docker exec onos /root/onos/bin/onos-app localhost activate org.onosproject.fwd

MONITOR_LOG="output/onos_monitor.log"
mkdir -p output
echo "Write ONOS monitor details to $MONITOR_LOG"
docker exec onos sh -c 'echo $$ > /tmp/monitor_tail.pid; exec tail -n 0 -F /root/onos/apache-karaf-*/data/log/karaf.log' \
  | grep --line-buffered -E "ANOMALY|RECOVERED|SUMMARY|LINK_|DEVICE_" > "$MONITOR_LOG" &


TEMP_PCAP="/tmp/sdn_capture.pcap"
echo "Launch tshark"

sudo tshark -i any -f "not port 22" -s 256 -b filesize:10000 -a duration:600 -w "$TEMP_PCAP" > /tmp/tshark_debug.log 2>&1 &
TSHARK_PID=$!

echo "Sleep for 5, wait for tshark"
sleep 5

#Is tshark running?
if ps -p $TSHARK_PID >/dev/null; then
  echo "tshark is running successfully (PID: $TSHARK_PID)"
  #See if ny program ran, sometimes the command is too quick
  #read -p "Press [Enter] to continue, is tshark running?"

  #Start my script
  sudo python3 python/main.py

  echo "Sleeping for 5, make sure tshark has finished"
  sleep 5

  #Kill tshark after mininet exit
  sudo kill $TSHARK_PID 2>/dev/null || true
  sleep 5
  echo "Move the file to the correction destination"
  sudo mv /tmp/*.pcap pcap/

  echo "Capture complete: $FINAL_PCAP"
  ls -lh pcap/
else
  echo "tshark failed to start!"
  cat /tmp/tshark_debug.log
fi

#Stop the background tail inside the container, grep then exits with it
docker exec onos sh -c 'kill $(cat /tmp/monitor_tail.pid)' 2>/dev/null || true
echo "ONOS monitor log: $MONITOR_LOG"
