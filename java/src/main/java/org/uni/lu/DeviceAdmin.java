package org.uni.lu;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.link.LinkService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component(immediate = true)
public class DeviceAdmin {

    private static final Logger log = LoggerFactory.getLogger(DeviceAdmin.class);


    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceAdminService deviceAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LinkService linkService;

    /**
     * Our list of switches that have been disconnected from the network, and the connectPoints they had removed
     */
    private final Map<DeviceId, Set<ConnectPoint>> isolatedSwitches = new ConcurrentHashMap<>();

    /**
     * Isolates the device on the network, uses the device ID to locate connections to the switches and "removes" them
     * @param deviceId The device ID to isolate
     */
    public void isolate(DeviceId deviceId) {
        //Connect points to close, set needs building
        Set<ConnectPoint> closedPorts = new HashSet<>();
        isolatedSwitches.putIfAbsent(deviceId, closedPorts);
        try {
            linkService.getDeviceIngressLinks(deviceId).forEach(link -> {
                ConnectPoint originPort = link.src();
                //Disable the healthy switch from connecting to the bad switch
                deviceAdminService.changePortState(originPort.deviceId(), originPort.port(), false);
                closedPorts.add(originPort);
            });
            //Calling isolate() twice might overwrite the saved ports with an empty set if ONOS no longer sees the links, preventing reinstate() from restoring them.
            isolatedSwitches.get(deviceId).addAll(closedPorts);
            log.info("Switch {} was isolated on ports: [{}]", deviceId, StringHelper.collectionToString(closedPorts, ";"));
        } catch (Exception e) {
            log.error("Error closing the ports to device {}", deviceId, e);
            //We had a failure, we should re-enable the currently closed ports in this run
            try {
                if (!closedPorts.isEmpty()) {
                    closedPorts.forEach(closedPort ->
                            deviceAdminService.changePortState(closedPort.deviceId(), closedPort.port(), true));
                }
            } catch (Exception innerE) {
                log.error("Error trying to re-open the ports after failed closing for device {}", deviceId, innerE);
            }
        }
    }

    /**
     * Reinstates the switch to the network by re-opening the connecting ports
     * @param deviceId The device ID to re-enable, if none is found, no ports are re-opened
     */
    public void reinstate(DeviceId deviceId) {
        Set<ConnectPoint> connectPointsToReEnable = null;
        try {
            connectPointsToReEnable = isolatedSwitches.remove(deviceId);
            //If device ID found in the hashset, work through the ports and re-enable them
            if (connectPointsToReEnable != null && !connectPointsToReEnable.isEmpty()) {
                connectPointsToReEnable.forEach(connectPoint ->
                        deviceAdminService.changePortState(connectPoint.deviceId(), connectPoint.port(), true));
                log.info("Switch {} was reinstated on ports: [{}]", deviceId, StringHelper.collectionToString(connectPointsToReEnable, ";"));
            }
        } catch (Exception e) {
            log.error("Error opening the ports to device {}", deviceId, e);
            //Re-add connections that were isolated, we do not know where we were in the code when the failure happened
            //This way, if we call reinstate again, we can reactivate them if needed
            if (connectPointsToReEnable != null) {
                isolatedSwitches.put(deviceId, connectPointsToReEnable);
            }
            //Reisolate the device ID, as we do not want to be in a partial state
            isolate(deviceId);
        }
    }
}
