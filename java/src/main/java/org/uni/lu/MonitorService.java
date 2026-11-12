package org.uni.lu;

import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onosproject.net.DeviceId;

import java.util.List;
import java.util.Map;

/**
 * The slice of the monitor that the REST API exposes.
 */
public interface MonitorService {

    /** A point-in-time snapshot of the running totals. */
    Map<String, Long> getStats();

    /** The most recent anomaly / recovery lines. */
    List<String> getAlerts();

    /** The current threshold configuration. */
    Map<String, Integer> getThresholds();

    /**
     * Change one threshold.
     */
    void setThreshold(String name, int value);

    /** Clear the running counters and any in-flight "over threshold" state. */
    void resetCounters();

    /**
     * The role assigned to an authenticated user by the app's "roleAssignments"
     * config, or null if the user has no explicit assignment.
     */
    Role roleForUser(String username);

    /** The rate limits currently installed: deviceId -> description. */
    Map<String, String> getRateLimits();

    /**
     * Rate-limit traffic on a device.
     */
    void applyRateLimit(DeviceId deviceId, IpPrefix src, long rateKbps);

    /** Remove the rate limit on a device*/
    boolean removeRateLimit(DeviceId deviceId);

    /**
     * Every MAC address captured from packet-ins and host events.
     */
    List<MacConnection> getMacAddresses();

    /**
     * The MAC address(es) that have been seen using this IP.
     */
    List<MacConnection> getMacAddressesForIp(IpAddress ip);

    /**
     * Forget all captured MAC addresses, returning how many there were.
     */
    int clearMacAddresses();
}
