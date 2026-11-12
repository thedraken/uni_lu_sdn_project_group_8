package org.uni.lu;

import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MacConnection {
    private final MacAddress macAddress;
    private final Instant firstSeen;
    private final Set<IpAddress> ipAddresses = ConcurrentHashMap.newKeySet();
    private final AtomicLong sightings = new AtomicLong();
    private volatile Instant lastSeen;
    private volatile String lastSource;

    public MacConnection(MacAddress macAddress) {
        this.macAddress = macAddress;
        this.firstSeen = Instant.now();
        this.lastSeen = firstSeen;
    }

    /**
     * Record one more sighting, from a packet-in or a host event.
     */
    public void record(String source, Collection<IpAddress> ips) {
        ipAddresses.addAll(ips);
        lastSource = source;
        lastSeen = Instant.now();
        sightings.incrementAndGet();
    }

    public MacAddress getMacAddress() {
        return macAddress;
    }

    public Set<IpAddress> getIpAddresses() {
        return Set.copyOf(ipAddresses);
    }

    public boolean hasIp(IpAddress ip) {
        return ipAddresses.contains(ip);
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public String getLastSource() {
        return lastSource;
    }

    public long getSightings() {
        return sightings.get();
    }
}
