package org.uni.lu;

import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MacRepository {

    private final Map<MacAddress, MacConnection> macConnections = new ConcurrentHashMap<>();

    public void record(MacAddress mac, String source, Collection<IpAddress> ips) {
        macConnections.computeIfAbsent(mac, MacConnection::new).record(source, ips);
    }

    public List<MacConnection> getAll() {
        return new ArrayList<>(macConnections.values());
    }

    public List<MacConnection> findByIp(IpAddress ip) {
        return macConnections.values().stream()
                .filter(mc -> mc.hasIp(ip))
                .collect(Collectors.toList());
    }

    public int clearEntries() {
        int currentCount = macConnections.size();
        macConnections.clear();
        //Just in case, should not happen, but maybe an edge case...?
        //noinspection ConstantValue
        if (macConnections.isEmpty()) {
            return currentCount;
        } else {
            return currentCount - macConnections.size();
        }
    }
}
