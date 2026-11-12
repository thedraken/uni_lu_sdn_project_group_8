package org.uni.lu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onosproject.app.ApplicationAdminService;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.rest.AbstractWebResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * REST API for the SDN monitor, with Role-Based Access Control.
 */
@Path("")
public class MonitorWebResource extends AbstractWebResource {

    private static final Logger log = LoggerFactory.getLogger(MonitorWebResource.class);

    //Must match onos.app.name in pom.xml / the name in app.xml
    private static final String APP_NAME = "org.uni.lu.Onos_setup_project";

    @Context
    private SecurityContext security;

    /**
     * Enforce that the caller holds at least role needed otherwise abort
     * the request with 403.
     */
    private void require(Role needed) {
        Role actual = currentRole();
        if (!actual.allows(needed)) {
            Principal principal = security.getUserPrincipal();
            String user = principal == null ? "<anonymous>" : principal.getName();
            log.warn("RBAC DENIED user={} role={} attempted an action needing {}",
                     user, actual, needed);
            throw new ForbiddenAbort(user, needed);
        }
    }

    /**
     * Resolve the caller's role.
     */
    private Role currentRole() {
        Principal principal = security == null ? null : security.getUserPrincipal();
        String user = principal == null ? null : principal.getName();
        if (user != null) {
            Role assigned = get(MonitorService.class).roleForUser(user);
            if (assigned != null) {
                return assigned;
            }
        }
        return Role.of(security);
    }

    @GET
    @Path("stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response stats() {
        require(Role.OBSERVER);
        ObjectNode root = mapper().createObjectNode();
        get(MonitorService.class).getStats()
                .forEach(root::put);
        return ok(root).build();
    }

    @GET
    @Path("alerts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response alerts() {
        require(Role.OBSERVER);
        ObjectNode root = mapper().createObjectNode();
        root.set("alerts", mapper().valueToTree(get(MonitorService.class).getAlerts()));
        return ok(root).build();
    }

    @GET
    @Path("thresholds")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getThresholds() {
        require(Role.OBSERVER);
        ObjectNode root = mapper().createObjectNode();
        get(MonitorService.class).getThresholds()
                .forEach(root::put);
        return ok(root).build();
    }

    @POST
    @Path("counters/reset")
    @Produces(MediaType.APPLICATION_JSON)
    public Response resetCounters() {
        require(Role.OPERATOR);
        get(MonitorService.class).resetCounters();
        log.info("RBAC OK user={} reset the monitor counters", caller());
        return ok(mapper().createObjectNode().put("reset", true)).build();
    }

    @POST
    @Path("thresholds")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setThresholds(InputStream body) {
        require(Role.ADMIN);
        MonitorService monitor = get(MonitorService.class);
        ObjectNode result = mapper().createObjectNode();
        try {
            @SuppressWarnings("unchecked") Map<String, Object> changes = mapper().readValue(body, Map.class);
            changes.forEach((name, value) -> {
                int parsed = Integer.parseInt(value.toString().trim());
                monitor.setThreshold(name, parsed);
                result.put(name, parsed);
            });
        } catch (IllegalArgumentException | IOException e) {
            //Bad field name or non-integer value: a client error, not a 500
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(mapper().createObjectNode().put("error", e.getMessage()))
                    .build();
        }
        log.info("RBAC OK user={} changed thresholds {}", caller(), result);
        return ok(result).build();
    }

    @POST
    @Path("restart")
    @Produces(MediaType.APPLICATION_JSON)
    public Response restart() {
        require(Role.ADMIN);
        ApplicationAdminService appService = get(ApplicationAdminService.class);
        ApplicationId id = appService.getId(APP_NAME);
        if (id == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper().createObjectNode().put("error", "app not found"))
                    .build();
        }
        log.warn("RBAC OK user={} requested a restart of {}", caller(), APP_NAME);
        //Deactivating this app tears down the bundle serving this request, so
        //do it on a short-lived background thread and answer 202 right away.
        new Thread(() -> {
            try {
                appService.deactivate(id);
                Thread.sleep(1000);
                appService.activate(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "monitor-restart").start();
        return Response.accepted(
                mapper().createObjectNode().put("restarting", APP_NAME)).build();
    }

    // ---- Rate limiting (OpenFlow meters) -----------------------------------

    @GET
    @Path("ratelimits")
    @Produces(MediaType.APPLICATION_JSON)
    public Response rateLimits() {
        require(Role.OBSERVER);
        ObjectNode root = mapper().createObjectNode();
        get(MonitorService.class).getRateLimits().forEach(root::put);
        return ok(root).build();
    }

    @POST
    @Path("ratelimit")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response applyRateLimit(InputStream body) {
        require(Role.ADMIN);
        try {
            JsonNode in = mapper().readTree(body);
            if (in == null || !in.hasNonNull("device")) {
                return badRequest("missing 'device' (e.g. of:0000000000000001)");
            }
            DeviceId device = DeviceId.deviceId(in.get("device").asText());
            IpPrefix src = in.hasNonNull("src") ? IpPrefix.valueOf(in.get("src").asText()) : null;
            long rateKbps = in.hasNonNull("rateKbps") ? in.get("rateKbps").asLong() : 1000L;
            if (rateKbps <= 0) {
                return badRequest("rateKbps must be > 0");
            }
            get(MonitorService.class).applyRateLimit(device, src, rateKbps);
            log.info("RBAC OK user={} rate-limited {} at {} kbit/s ({})",
                     caller(), device, rateKbps, src == null ? "all IPv4" : src);
            return ok(mapper().createObjectNode()
                    .put("device", device.toString())
                    .put("rateKbps", rateKbps)
                    .put("src", src == null ? "all-IPv4" : src.toString())).build();
        } catch (IllegalArgumentException | IOException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("ratelimit/remove")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeRateLimit(InputStream body) {
        require(Role.ADMIN);
        try {
            JsonNode in = mapper().readTree(body);
            if (in == null || !in.hasNonNull("device")) {
                return badRequest("missing 'device'");
            }
            DeviceId device = DeviceId.deviceId(in.get("device").asText());
            boolean removed = get(MonitorService.class).removeRateLimit(device);
            log.info("RBAC OK user={} removed rate limit on {} (existed={})",
                     caller(), device, removed);
            return ok(mapper().createObjectNode()
                    .put("device", device.toString())
                    .put("removed", removed)).build();
        } catch (IllegalArgumentException | IOException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("macAddresses")
    @Produces(MediaType.APPLICATION_JSON)
    public Response macAddresses() {
        require(Role.OBSERVER);
        return ok(macsJson(get(MonitorService.class).getMacAddresses())).build();
    }

    @GET
    @Path("macAddresses/{ip}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response macAddressesForIp(@PathParam("ip") String ipText) {
        require(Role.OBSERVER);
        IpAddress ip;
        try {
            ip = IpAddress.valueOf(ipText);
        } catch (IllegalArgumentException e) {
            return badRequest("not a valid IP address: " + ipText);
        }
        List<MacConnection> found = get(MonitorService.class).getMacAddressesForIp(ip);
        if (found.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper().createObjectNode()
                            .put("error", "no MAC address seen for " + ip))
                    .build();
        }
        return ok(macsJson(found).put("ip", ip.toString())).build();
    }

    @DELETE
    @Path("macAddresses")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteMacAddresses() {
        require(Role.ADMIN);
        int count = get(MonitorService.class).clearMacAddresses();
        log.info("RBAC OK user={} cleared {} captured MAC addresses", caller(), count);
        return ok(mapper().createObjectNode()
                .put("macAddressesDeleted", count))
                .build();
    }

    private ObjectNode macsJson(List<MacConnection> macs) {
        ObjectNode root = mapper().createObjectNode();
        ArrayNode array = root.putArray("macAddresses");
        macs.forEach(mc -> {
            ObjectNode entry = array.addObject()
                    .put("mac", mc.getMacAddress().toString())
                    .put("firstSeen", mc.getFirstSeen().toString())
                    .put("lastSeen", mc.getLastSeen().toString())
                    .put("lastSource", mc.getLastSource())
                    .put("sightings", mc.getSightings());
            ArrayNode ips = entry.putArray("ips");
            mc.getIpAddresses().forEach(ip -> ips.add(ip.toString()));
        });
        root.put("count", macs.size());
        return root;
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapper().createObjectNode().put("error", message))
                .build();
    }

    private String caller() {
        Principal principal = security.getUserPrincipal();
        return principal == null ? "<anonymous>" : principal.getName();
    }

    /** 403 carrying a short JSON body explaining what was missing. */
    private static final class ForbiddenAbort extends javax.ws.rs.WebApplicationException {
        ForbiddenAbort(String user, Role needed) {
            super(Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity("{\"error\":\"forbidden\",\"user\":\"" + user
                            + "\",\"requiredRole\":\"" + needed + "\"}")
                    .build());
        }
    }
}
