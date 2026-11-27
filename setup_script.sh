#!/bin/bash

# Babbling Switch and packet capture settings, all overridable from the environment.
# PCAP_MAX_KB stops the tshark capture after that many kilobytes (the VM disk is small).
AUTO_ISOLATE_BABBLING=${AUTO_ISOLATE_BABBLING:-false}
BABBLING_TARGET_DEVICE=${BABBLING_TARGET_DEVICE:-}
BABBLING_EXCESS_THRESHOLD=${BABBLING_EXCESS_THRESHOLD:-50}
BABBLING_SUSTAINED_SAMPLES=${BABBLING_SUSTAINED_SAMPLES:-2}
PCAP_MAX_KB=${PCAP_MAX_KB:-50000}

# Validate configuration before changing the environment.
for VALUE in "${AUTO_ISOLATE:-true}" "$AUTO_ISOLATE_BABBLING"
do
    if [[ "$VALUE" != "true" && "$VALUE" != "false" ]]; then
        echo "ERROR: Isolation settings must be true or false."
        exit 1
    fi
done

for VALUE in "$BABBLING_EXCESS_THRESHOLD" "$BABBLING_SUSTAINED_SAMPLES" "$PCAP_MAX_KB"
do
    if [[ ! "$VALUE" =~ ^[1-9][0-9]{0,8}$ ]]; then
        echo "ERROR: Thresholds, sample counts and the capture size must be positive integers."
        exit 1
    fi
done

if [[ -n "$BABBLING_TARGET_DEVICE" ]] &&
   [[ ! "$BABBLING_TARGET_DEVICE" =~ ^of:[0-9a-f]{16}$ ]]; then
    echo "ERROR: Invalid BABBLING_TARGET_DEVICE."
    exit 1
fi

if [[ "$AUTO_ISOLATE_BABBLING" == "true" ]] && [[ -z "$BABBLING_TARGET_DEVICE" ]]; then
    echo "ERROR: A valid BABBLING_TARGET_DEVICE is required."
    exit 1
fi

#Set the docker to use the needed ip address
docker network create --subnet=10.0.0.0/24 onos-net 2>/dev/null || true
#Renove the docker image
docker rm -f onos 2>/dev/null || true

#Redownload and start the docker image
docker run -d \
  --name onos \
  --net onos-net \
  -e TZ=UTC \
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

#Waiting for service to be up, so we can change parameters
sleep 10

#Use bash arguments to turn on auto isolate a switch, if it breaks
# the threshold count for number of packets sent vs received
AUTO_ISOLATE=${AUTO_ISOLATE:-true}
echo "Babbling excess threshold: $BABBLING_EXCESS_THRESHOLD"
echo "Babbling sustained samples: $BABBLING_SUSTAINED_SAMPLES"
echo "Babbling target: $BABBLING_TARGET_DEVICE"
echo "Set autoIsolateBabbling=$AUTO_ISOLATE_BABBLING"
echo "Set autoIsolate=$AUTO_ISOLATE"
AUTO_ISOLATE_SET=false
HTTP_CODE=$(docker exec onos curl -s -o /dev/null -w "%{http_code}" -u onos:rocks \
  -X POST -H "Content-Type: application/json" \
  -d "{\"autoIsolate\": \"$AUTO_ISOLATE\", \"autoIsolateBabbling\": \"$AUTO_ISOLATE_BABBLING\", \"babblingTargetDevice\": \"$BABBLING_TARGET_DEVICE\", \"babblingExcessThreshold\": \"$BABBLING_EXCESS_THRESHOLD\", \"babblingSustainedSamples\": \"$BABBLING_SUSTAINED_SAMPLES\"}" \
  http://localhost:8181/onos/v1/configuration/org.uni.lu.NetworkMonitorComponent 2>/dev/null)
if [ "$HTTP_CODE" = "200" ]; then
    AUTO_ISOLATE_SET=true
fi
if [ "$AUTO_ISOLATE_SET" != "true" ]; then
    echo "ERROR: ONOS configuration failed (HTTP $HTTP_CODE)." >&2
    exit 1
fi

#Each run gets its own UTC timestamped directory, so runs never overwrite each other
RUN_DIR="output/$(date -u +%Y-%m-%d_%H-%M-%S)"
mkdir -p "$RUN_DIR"
echo "Run output directory: $RUN_DIR"
MONITOR_LOG="$RUN_DIR/onos_monitor.log"
echo "Write ONOS monitor details to $MONITOR_LOG"
docker exec onos sh -c 'echo $$ > /tmp/monitor_tail.pid; exec tail -n 0 -F /root/onos/apache-karaf-*/data/log/karaf.log' \
  | grep --line-buffered -E "ANOMALY|RECOVERED|SUMMARY|BABBLING_|LINK_|DEVICE_|Isolated|isolated|reinstated" > "$MONITOR_LOG" &


TEMP_PCAP="/tmp/sdn_capture.pcap"
sudo rm -f /tmp/group8_tshark.pid
echo "Launch tshark"

sudo bash -c '
  echo $$ > /tmp/group8_tshark.pid
  exec tshark -i any -f "not port 22" -s 256 -a filesize:"$1" \
    -w /tmp/sdn_capture.pcap
' _ "$PCAP_MAX_KB" > /tmp/tshark_debug.log 2>&1 &
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
  # Can pass attack value into script
  sudo .venv/bin/python main.py --output_dir "$RUN_DIR" "$@"

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
    if sudo mv "$TEMP_PCAP" "$RUN_DIR/"; then
      echo "Capture complete: $RUN_DIR/$(basename "$TEMP_PCAP")"
    else
      echo "ERROR: Failed to move PCAP file." >&2
    fi
  else
    echo "WARNING: PCAP file was not created." >&2
  fi
  ls -lh "$RUN_DIR"/
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
echo "Run output saved in: $RUN_DIR"
