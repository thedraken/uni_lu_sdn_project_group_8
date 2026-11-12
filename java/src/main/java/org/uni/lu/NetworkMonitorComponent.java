package org.uni.lu;

import org.onlab.packet.*;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.host.HostEvent;
import org.onosproject.net.host.HostListener;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.link.LinkListener;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.meter.MeterService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("unused")
@Component(immediate = true,
        service = MonitorService.class,
        property = {
                "packetInThreshold:Integer=500",
                "flowModThreshold:Integer=50",
                "newHostThreshold:Integer=1",
                "sustainedSeconds:Integer=5",
                "maxFlowsPerDevice:Integer=500",
                "summaryInterval:Integer=10",
                "roleAssignments=alice:admin,bob:operator,charlie:observer,onos:admin",
                "rateLimitKbps:Integer=1000",
                "autoMitigate:Boolean=false"
        })
public class NetworkMonitorComponent implements MonitorService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MeterService meterService;

    private int packetInThreshold = 100;
    private int flowModThreshold = 100;
    private int newHostThreshold = 10;
    private int sustainedSeconds = 5;
    private int maxFlowsPerDevice = 1000;
    private int summaryInterval = 10;
    private int rateLimitKbps = 1000;
    private boolean autoMitigate = false;

    private final MacRepository macRepository = new MacRepository();

    //username -> role, the lab's "assign roles to users". Rebuilt on every
    //config change so roles can be reassigned at runtime with "cfg set".
    private volatile Map<String, Role> roleAssignments = new HashMap<>();

    //Application identity and the active-mitigation helper, wired in activate().
    private static final String APP_NAME = "org.uni.lu.Onos_setup_project";
    private RateLimiter rateLimiter;

    private final ReactivePacketProcessor processor = new ReactivePacketProcessor();
    private final InternalDeviceListener deviceListener = new InternalDeviceListener();
    private final InternalLinkListener linkListener = new InternalLinkListener();
    private final InternalHostListener hostListener = new InternalHostListener();
    private final InternalFlowRuleListener flowRuleListener = new InternalFlowRuleListener();

    private final Map<DeviceId, AtomicLong> packetIns = new ConcurrentHashMap<>();
    private final Map<DeviceId, AtomicLong> flowAdds = new ConcurrentHashMap<>();
    private final AtomicLong newHosts = new AtomicLong();
    private final Map<String, Integer> overThreshold = new ConcurrentHashMap<>();

    //Recent anomaly/recovery lines, served by the REST API's GET /alerts.
    //Bounded so a long run can never grow it without limit.
    private static final int MAX_ALERTS = 100;
    private final Deque<String> recentAlerts = new ArrayDeque<>();

    //The name ComponentConfigService knows this component by, used when the
    private final String componentName = getClass().getName();

    private final AtomicLong summaryPacketIns = new AtomicLong();
    private final AtomicLong summaryFlowAdds = new AtomicLong();
    private final AtomicLong summaryFlowRemoves = new AtomicLong();
    private final AtomicLong summaryTopologyEvents = new AtomicLong();
    private long secondsSinceSummary = 0;

    private ScheduledExecutorService executor;

    @Activate
    protected void activate(ComponentContext context) {
        cfgService.registerProperties(getClass());
        ApplicationId appId = coreService.registerApplication(APP_NAME);
        rateLimiter = new RateLimiter(meterService, flowRuleService, appId);
        readConfig(context);

        packetService.addProcessor(processor, PacketProcessor.director(1));
        deviceService.addListener(deviceListener);
        linkService.addListener(linkListener);
        hostService.addListener(hostListener);
        flowRuleService.addListener(flowRuleListener);


        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(this::everySecond, 1, 1, TimeUnit.SECONDS);
        log.info("ONOS Monitoring App Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        executor.shutdownNow();
        packetService.removeProcessor(processor);
        deviceService.removeListener(deviceListener);
        linkService.removeListener(linkListener);
        hostService.removeListener(hostListener);
        flowRuleService.removeListener(flowRuleListener);
        if (rateLimiter != null) {
            //Tear down any meters/flows we installed so they don't outlive us.
            rateLimiter.removeAll();
        }
        log.info("ONOS Monitoring App Stopped");
    }

    @Modified
    protected void modified(ComponentContext context) {
        readConfig(context);
    }

    private void readConfig(ComponentContext context) {
        if (context == null) {
            return;
        }
        Dictionary<?, ?> properties = context.getProperties();
        packetInThreshold = intProperty(properties, "packetInThreshold", packetInThreshold);
        flowModThreshold = intProperty(properties, "flowModThreshold", flowModThreshold);
        newHostThreshold = intProperty(properties, "newHostThreshold", newHostThreshold);
        sustainedSeconds = intProperty(properties, "sustainedSeconds", sustainedSeconds);
        maxFlowsPerDevice = intProperty(properties, "maxFlowsPerDevice", maxFlowsPerDevice);
        summaryInterval = intProperty(properties, "summaryInterval", summaryInterval);
        rateLimitKbps = intProperty(properties, "rateLimitKbps", rateLimitKbps);
        autoMitigate = boolProperty(properties, "autoMitigate", autoMitigate);
        roleAssignments = parseRoleAssignments(properties.get("roleAssignments"));
        log.info("Thresholds: packetIn={}/s flowMod={}/s newHosts={}/s for {}s, maxFlows={}",
                 packetInThreshold, flowModThreshold, newHostThreshold, sustainedSeconds,
                 maxFlowsPerDevice);
        log.info("Mitigation: autoMitigate={} rateLimit={} kbit/s", autoMitigate, rateLimitKbps);
        log.info("RBAC role assignments: {}", roleAssignments);
    }

    private static boolean boolProperty(Dictionary<?, ?> properties, @SuppressWarnings("SameParameterValue") String name, boolean current) {
        Object value = properties.get(name);
        if (value == null) {
            return current;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    private static Map<String, Role> parseRoleAssignments(Object raw) {
        //Accepts "user:role,user:role,...". DS may hand us the commas already
        //split into a String[], so cope with both that and a plain String.
        Map<String, Role> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        String[] pairs = (raw instanceof String[])
                ? (String[]) raw
                : raw.toString().split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":");
            if (kv.length == 2) {
                Role role = Role.fromName(kv[1]);
                if (role != null) {
                    map.put(kv[0].trim(), role);
                }
            }
        }
        return map;
    }

    private static int intProperty(Dictionary<?, ?> properties, String name, int current) {
        Object value = properties.get(name);
        if (value == null) {
            return current;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return current;
        }
    }

    private void everySecond() {
        try {
            packetIns.forEach((deviceId, count) -> {
                long rate = count.getAndSet(0);
                checkRate("PACKET_IN " + deviceId, rate, packetInThreshold, deviceId);
            });
            flowAdds.forEach((deviceId, count) -> {
                long rate = count.getAndSet(0);
                checkRate("FLOW_MOD " + deviceId, rate, flowModThreshold, deviceId);
            });
            //Not device-specific, so nothing to auto-mitigate here.
            checkRate("New hosts", newHosts.getAndSet(0), newHostThreshold, null);

            secondsSinceSummary++;
            if (secondsSinceSummary >= summaryInterval) {
                secondsSinceSummary = 0;
                checkFlowTables();
                logSummary();
            }
        } catch (Exception e) {
            //An exception would stop the scheduled task for good
            log.error("Monitor check failed", e);
        }
    }

    private void checkRate(String name, long rate, int threshold, DeviceId source) {
        int seconds = overThreshold.getOrDefault(name, 0);
        if (rate > threshold) {
            seconds++;
            overThreshold.put(name, seconds);
            if (seconds == sustainedSeconds) {
                log.warn("ANOMALY {}: {}/s for {}s (threshold {}/s)",
                         name, rate, seconds, threshold);
                recordAlert(String.format("ANOMALY %s: %d/s for %ds (threshold %d/s)",
                        name, rate, seconds, threshold));
                autoMitigate(source);
            }
        } else if (seconds > 0) {
            if (seconds >= sustainedSeconds) {
                log.warn("RECOVERED {}: back to {}/s after {}s", name, rate, seconds);
                recordAlert(String.format("RECOVERED %s: back to %d/s after %ds",
                        name, rate, seconds));
            }
            overThreshold.remove(name);
        }
    }

    private void checkFlowTables() {
        for (Device device : deviceService.getAvailableDevices()) {
            int flows = flowRuleService.getFlowRuleCount(device.id());
            if (flows > maxFlowsPerDevice) {
                log.warn("ANOMALY flow table {}: {} flows (threshold {})",
                        device.id(), flows, maxFlowsPerDevice);
                recordAlert(String.format("ANOMALY flow table %s: %d flows (threshold %d)",
                        device.id(), flows, maxFlowsPerDevice));
            }
        }
    }

    private void logSummary() {
        log.info("SUMMARY interval={}s packetIn={} flowAdded={} flowRemoved={} " +
                         "topologyEvents={} devices={} links={} hosts={} flows={}",
                 summaryInterval,
                 summaryPacketIns.getAndSet(0),
                 summaryFlowAdds.getAndSet(0),
                 summaryFlowRemoves.getAndSet(0),
                 summaryTopologyEvents.getAndSet(0),
                 deviceService.getAvailableDeviceCount(),
                 linkService.getLinkCount(),
                 hostService.getHostCount(),
                 flowRuleService.getFlowRuleCount());
    }

    private void recordAlert(String message) {
        String line = Instant.now() + " " + message;
        synchronized (recentAlerts) {
            recentAlerts.addLast(line);
            while (recentAlerts.size() > MAX_ALERTS) {
                recentAlerts.removeFirst();
            }
        }
    }

    @Override
    public Map<String, Long> getStats() {
        //A live snapshot; these are the same figures the periodic SUMMARY logs.
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("devices", (long) deviceService.getAvailableDeviceCount());
        stats.put("links", (long) linkService.getLinkCount());
        stats.put("hosts", (long) hostService.getHostCount());
        stats.put("flows", (long) flowRuleService.getFlowRuleCount());
        stats.put("packetInSinceSummary", summaryPacketIns.get());
        stats.put("flowAddedSinceSummary", summaryFlowAdds.get());
        stats.put("flowRemovedSinceSummary", summaryFlowRemoves.get());
        stats.put("topologyEventsSinceSummary", summaryTopologyEvents.get());
        return stats;
    }

    @Override
    public List<String> getAlerts() {
        synchronized (recentAlerts) {
            return new ArrayList<>(recentAlerts);
        }
    }

    @Override
    public Map<String, Integer> getThresholds() {
        Map<String, Integer> thresholds = new LinkedHashMap<>();
        thresholds.put("packetInThreshold", packetInThreshold);
        thresholds.put("flowModThreshold", flowModThreshold);
        thresholds.put("newHostThreshold", newHostThreshold);
        thresholds.put("sustainedSeconds", sustainedSeconds);
        thresholds.put("maxFlowsPerDevice", maxFlowsPerDevice);
        thresholds.put("summaryInterval", summaryInterval);
        return thresholds;
    }

    @Override
    public void setThreshold(String name, int value) {
        if (!getThresholds().containsKey(name)) {
            throw new IllegalArgumentException("unknown threshold: " + name);
        }
        if (value < 0) {
            throw new IllegalArgumentException("threshold must be >= 0: " + name);
        }
        cfgService.setProperty(componentName, name, Integer.toString(value));
    }

    //Install a rate limit on the offending device when a sustained anomaly
    //fires.
    private void autoMitigate(DeviceId source) {
        if (!autoMitigate || source == null || rateLimiter == null
                || rateLimiter.isLimited(source)) {
            return;
        }
        try {
            rateLimiter.apply(source, null, rateLimitKbps);
            recordAlert(String.format("AUTO-MITIGATE rate limited %s at %d kbit/s",
                    source, rateLimitKbps));
        } catch (Exception e) {
            //Most likely the switch's datapath has no meter support.
            log.error("Auto rate-limit failed on {}", source, e);
        }
    }

    @Override
    public Role roleForUser(String username) {
        return username == null ? null : roleAssignments.get(username);
    }

    @Override
    public Map<String, String> getRateLimits() {
        return rateLimiter.list();
    }

    @Override
    public void applyRateLimit(DeviceId deviceId, IpPrefix src, long rateKbps) {
        rateLimiter.apply(deviceId, src, rateKbps);
    }

    @Override
    public boolean removeRateLimit(DeviceId deviceId) {
        return rateLimiter.remove(deviceId);
    }

    @Override
    public List<MacConnection> getMacAddresses() {
        return macRepository.getAll();
    }

    @Override
    public List<MacConnection> getMacAddressesForIp(IpAddress ip) {
        return macRepository.findByIp(ip);
    }

    @Override
    public int clearMacAddresses() {
        int count = macRepository.clearEntries();
        log.info("Cleared {} captured MAC addresses via REST API", count);
        return count;
    }

    @Override
    public void resetCounters() {
        packetIns.clear();
        flowAdds.clear();
        newHosts.set(0);
        overThreshold.clear();
        summaryPacketIns.set(0);
        summaryFlowAdds.set(0);
        summaryFlowRemoves.set(0);
        summaryTopologyEvents.set(0);
        secondsSinceSummary = 0;
        log.info("Monitor counters reset via REST API");
    }

    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context == null || context.inPacket() == null ||
                    context.inPacket().receivedFrom() == null) {
                return;
            }

            ConnectPoint connectPoint = context.inPacket().receivedFrom();
            recordSourceMac(context.inPacket().parsed());

            DeviceId deviceId = connectPoint.deviceId();
            packetIns.computeIfAbsent(deviceId, id -> new AtomicLong()).incrementAndGet();
            summaryPacketIns.incrementAndGet();
        }

        //Packet-ins always arrive on a switch port, so the host's MAC and IP
        //have to come from the frame itself, not from receivedFrom().
        private void recordSourceMac(Ethernet eth) {
            if (eth == null || eth.getEtherType() == Ethernet.TYPE_LLDP
                    || eth.getEtherType() == Ethernet.TYPE_BSN) {
                //Topology discovery probes come from the switches, not hosts
                return;
            }
            IpAddress ip = sourceIp(eth);
            macRepository.record(eth.getSourceMAC(), "Packet",
                    ip == null || ip.isZero() ? Set.of() : Set.of(ip));
        }

        private IpAddress sourceIp(Ethernet eth) {
            IPacket payload = eth.getPayload();
            if (payload instanceof IPv4) {
                return IpAddress.valueOf(((IPv4) payload).getSourceAddress());
            } else if (payload instanceof IPv6) {
                return IpAddress.valueOf(IpAddress.Version.INET6,
                        ((IPv6) payload).getSourceAddress());
            } else if (payload instanceof ARP) {
                ARP arp = (ARP) payload;
                if (arp.getProtocolType() == ARP.PROTO_TYPE_IP) {
                    return IpAddress.valueOf(IpAddress.Version.INET,
                            arp.getSenderProtocolAddress());
                }
            }
            return null;
        }
    }

    private class InternalFlowRuleListener implements FlowRuleListener {
        @Override
        public void event(FlowRuleEvent event) {
            if (event.type() == FlowRuleEvent.Type.RULE_ADD_REQUESTED) {
                DeviceId deviceId = event.subject().deviceId();
                flowAdds.computeIfAbsent(deviceId, id -> new AtomicLong()).incrementAndGet();
                summaryFlowAdds.incrementAndGet();
            } else if (event.type() == FlowRuleEvent.Type.RULE_REMOVED) {
                summaryFlowRemoves.incrementAndGet();
            }
        }
    }

    private class InternalHostListener implements HostListener {
        @Override
        public void event(HostEvent event) {
            Host host = event.subject();
            switch (event.type()) {
                case HOST_ADDED:
                    newHosts.incrementAndGet();
                    log.info("HOST_ADDED {} at {}", host.mac(), host.locations());
                    macRepository.record(host.mac(), "Host added", host.ipAddresses());
                    break;
                case HOST_MOVED:
                    log.warn("HOST_MOVED {} from {} to {}", host.mac(),
                            event.prevSubject().locations(), host.locations());
                    macRepository.record(host.mac(), "Host moved", host.ipAddresses());
                    break;
                case HOST_UPDATED:
                    //Hosts are often added before their IP is known; it arrives here
                    macRepository.record(host.mac(), "Host updated", host.ipAddresses());
                    break;
                case HOST_REMOVED:
                    //Kept, since the capture is a history; only DELETE clears it
                    macRepository.record(host.mac(), "Host removed", host.ipAddresses());
                    break;
                default:
                    break;
            }
        }
    }

    private class InternalDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            switch (event.type()) {
                case DEVICE_ADDED:
                case DEVICE_REMOVED:
                case DEVICE_AVAILABILITY_CHANGED:
                    summaryTopologyEvents.incrementAndGet();
                    log.warn("{} {} available={}", event.type(), event.subject().id(),
                             deviceService.isAvailable(event.subject().id()));
                    break;
                case PORT_UPDATED:
                    summaryTopologyEvents.incrementAndGet();
                    log.info("PORT_UPDATED {} port {} enabled={}", event.subject().id(),
                             event.port().number(), event.port().isEnabled());
                    break;
                default:
                    //Port stats updates arrive every few seconds, too noisy to log
                    break;
            }
        }
    }

    private class InternalLinkListener implements LinkListener {
        @Override
        public void event(LinkEvent event) {
            summaryTopologyEvents.incrementAndGet();
            if (event.type() == LinkEvent.Type.LINK_REMOVED) {
                log.warn("LINK_REMOVED {} -> {}", event.subject().src(), event.subject().dst());
            } else {
                log.info("{} {} -> {}", event.type(), event.subject().src(),
                         event.subject().dst());
            }
        }
    }
}
