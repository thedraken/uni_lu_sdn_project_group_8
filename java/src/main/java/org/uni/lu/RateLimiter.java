package org.uni.lu;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IpPrefix;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.meter.Band;
import org.onosproject.net.meter.DefaultBand;
import org.onosproject.net.meter.DefaultMeterRequest;
import org.onosproject.net.meter.Meter;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.meter.MeterRequest;
import org.onosproject.net.meter.MeterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Installs OpenFlow meters plus the flow rules that feed
 * traffic through them, to rate-limit a device. This is the active-mitigation
 * half of the monitor
 */
class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    //Above the reactive-forwarding app's flows so our policing flow wins.
    private static final int FLOW_PRIORITY = 50000;

    private final MeterService meterService;
    private final FlowRuleService flowRuleService;
    private final ApplicationId appId;

    //One active limit per device.
    private final Map<DeviceId, Limit> limits = new ConcurrentHashMap<>();

    RateLimiter(MeterService meterService, FlowRuleService flowRuleService,
                ApplicationId appId) {
        this.meterService = meterService;
        this.flowRuleService = flowRuleService;
        this.appId = appId;
    }

    /**
     * Rate-limit traffic on a device at a specific kbit/s. If
     * null, all IPv4 traffic on the device is limited
     */
    synchronized void apply(DeviceId deviceId, IpPrefix src, long rateKbps) {
        //Replace any prior limit so re-applying never leaks meters or flows.
        remove(deviceId);

        Band drop = DefaultBand.builder()
                .ofType(Band.Type.DROP)
                .withRate(rateKbps)
                .burstSize(rateKbps)            //~1 second of burst
                .build();
        Collection<Band> bands = Collections.singleton(drop);

        MeterRequest addRequest = DefaultMeterRequest.builder()
                .forDevice(deviceId)
                .fromApp(appId)
                .withUnit(Meter.Unit.KB_PER_SEC)   //OFPMF_KBPS -> kbit/s
                .burst()
                .withBands(bands)
                .add();
        Meter meter = meterService.submit(addRequest);
        @SuppressWarnings("deprecation") MeterId meterId = meter.id();

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4);
        if (src != null) {
            selector.matchIPSrc(src);
        }
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .meter(meterId)                    //police here
                .setOutput(PortNumber.NORMAL)      //then forward via OVS L2
                .build();
        FlowRule flow = DefaultFlowRule.builder()
                .forDevice(deviceId)
                .fromApp(appId)
                .withSelector(selector.build())
                .withTreatment(treatment)
                .withPriority(FLOW_PRIORITY)
                .makePermanent()
                .build();
        flowRuleService.applyFlowRules(flow);

        limits.put(deviceId, new Limit(meterId, bands, flow, rateKbps,
                src == null ? "all-IPv4" : src.toString()));
        log.warn("RATE LIMIT applied on {} ({}) at {} kbit/s",
                 deviceId, src == null ? "all IPv4" : "src " + src, rateKbps);
    }

    /** Remove the limit on a device */
    synchronized boolean remove(DeviceId deviceId) {
        Limit limit = limits.remove(deviceId);
        if (limit == null) {
            return false;
        }
        flowRuleService.removeFlowRules(limit.flow);
        MeterRequest removeRequest = DefaultMeterRequest.builder()
                .forDevice(deviceId)
                .fromApp(appId)
                .withUnit(Meter.Unit.KB_PER_SEC)
                .burst()
                .withBands(limit.bands)
                .remove();
        //noinspection deprecation
        meterService.withdraw(removeRequest, limit.meterId);
        log.warn("RATE LIMIT removed on {}", deviceId);
        return true;
    }

    /** Remove every limit (used on shutdown). */
    synchronized void removeAll() {
        for (DeviceId deviceId : limits.keySet().toArray(new DeviceId[0])) {
            remove(deviceId);
        }
    }

    /** deviceId -> human-readable description, for the REST API. */
    Map<String, String> list() {
        Map<String, String> out = new LinkedHashMap<>();
        limits.forEach((deviceId, limit) ->
                out.put(deviceId.toString(),
                        limit.rateKbps + " kbit/s on " + limit.match));
        return out;
    }

    boolean isLimited(DeviceId deviceId) {
        return limits.containsKey(deviceId);
    }

    private static final class Limit {
        final MeterId meterId;
        final Collection<Band> bands;
        final FlowRule flow;
        final long rateKbps;
        final String match;

        Limit(MeterId meterId, Collection<Band> bands, FlowRule flow,
              long rateKbps, String match) {
            this.meterId = meterId;
            this.bands = bands;
            this.flow = flow;
            this.rateKbps = rateKbps;
            this.match = match;
        }
    }
}
