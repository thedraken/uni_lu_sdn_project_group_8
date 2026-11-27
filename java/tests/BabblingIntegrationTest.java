import org.uni.lu.NetworkMonitorComponent;
import org.uni.lu.DeviceAdmin;
import org.uni.lu.domain.PacketDetail;
import java.time.Instant;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.Link;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.link.LinkService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BabblingIntegrationTest {

    private static final DeviceId TARGET =
        DeviceId.deviceId("of:000000000000000a");

    private static final DeviceId S1 =
        DeviceId.deviceId("of:0000000000000001");

    private static final DeviceId S2 =
        DeviceId.deviceId("of:0000000000000002");

    private static void setField(
            Object object, String name, Object value)
            throws Exception {

        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void require(
            boolean condition, String message) {

        if (!condition) {
            throw new AssertionError(message);
        }

        System.out.println("PASS: " + message);
    }

    private static Link mockLink(DeviceId source) {

        ConnectPoint port = new ConnectPoint(
            source, PortNumber.portNumber(1)
        );

        return (Link) Proxy.newProxyInstance(
            Link.class.getClassLoader(),
            new Class<?>[] { Link.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "src":
                        return port;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        throw new UnsupportedOperationException(
                            method.getName()
                        );
                }
            }
        );
    }

    private static void runScenario(
            String scenario,
            boolean automaticIsolation,
            String authorisedTarget,
            boolean expectIsolation,
            List<String> samples) throws Exception {

        System.out.println("\n===== " + scenario + " =====");

        List<String> commands = new ArrayList<>();

        Set<Link> links = new HashSet<>();
        links.add(mockLink(S1));
        links.add(mockLink(S2));

        LinkService linkService =
            (LinkService) Proxy.newProxyInstance(
                LinkService.class.getClassLoader(),
                new Class<?>[] { LinkService.class },
                (proxy, method, args) -> {
                    if (method.getName().equals(
                            "getDeviceIngressLinks")) {

                        if (!TARGET.equals(args[0])) {
                            throw new AssertionError(
                                "Unexpected isolation target"
                            );
                        }

                        return links;
                    }

                    throw new UnsupportedOperationException(
                        method.getName()
                    );
                }
            );

        DeviceAdminService adminService =
            (DeviceAdminService) Proxy.newProxyInstance(
                DeviceAdminService.class.getClassLoader(),
                new Class<?>[] { DeviceAdminService.class },
                (proxy, method, args) -> {
                    if (method.getName().equals(
                            "changePortState")) {

                        String command =
                            args[0] + "/" + args[1]
                            + " enabled=" + args[2];

                        commands.add(command);
                        System.out.println(
                            "PORT_COMMAND " + command
                        );

                        return null;
                    }

                    throw new UnsupportedOperationException(
                        method.getName()
                    );
                }
            );

        DeviceAdmin admin = new DeviceAdmin();

        setField(admin, "linkService", linkService);
        setField(admin, "deviceAdminService", adminService);

        NetworkMonitorComponent monitor =
            new NetworkMonitorComponent();

        setField(monitor, "deviceAdmin", admin);
        setField(monitor, "babblingExcessThreshold", 50);
        setField(monitor, "babblingSustainedSamples", 2);
        setField(monitor, "autoMitigate", false);
        setField(monitor, "autoIsolateBabbling",
                 automaticIsolation);
        setField(monitor, "babblingTargetDevice",
                 authorisedTarget);

        Method detector =
            NetworkMonitorComponent.class.getDeclaredMethod(
                "checkDataPlaneBabbling",
                DeviceId.class,
                List.class,
                Set.class,
                Instant.class
            );

        detector.setAccessible(true);

        int firstDetection = -1;
        int firstIsolation = -1;

        for (String row : samples) {

            String[] fields = row.split(",");

            int sampleIndex = Integer.parseInt(fields[0]);
            DeviceId device = DeviceId.deviceId(fields[1]);
            long rate = Long.parseLong(fields[2]);

            Instant timestamp = Instant.EPOCH.plusSeconds(sampleIndex * 5L);

            long rx = 50;
            // Every device receives the supplied synthetic excess rate unchanged.
            long tx = rx + rate * 5;

            PacketDetail detail = new PacketDetail(
                device,
                PortNumber.portNumber(1),
                rx, tx, 0, 0, timestamp
            );

            detector.invoke(
                monitor,
                device,
                List.of(detail),
                Set.of(PortNumber.portNumber(1)),
                timestamp
            );

            boolean detected =
                monitor.getAlerts().stream().anyMatch(
                    a -> a.contains(
                        "ANOMALY BABBLING_DATA " + TARGET
                    )
                );

            if (detected && firstDetection == -1) {
                firstDetection = sampleIndex;
                System.out.println(
                    "DETECTION sampleIndex=" + sampleIndex
                );
            }

            if (admin.isIsolated(TARGET)
                    && firstIsolation == -1) {

                firstIsolation = sampleIndex;

                System.out.println(
                    "ISOLATION sampleIndex=" + sampleIndex
                );
            }

            if (sampleIndex < 11 && admin.isIsolated(TARGET)) {
                throw new AssertionError(
                    "Premature isolation"
                );
            }
        }

        require(
            firstDetection == 11,
            "Detection at expected sample"
        );

        require(
            monitor.getAlerts().stream().filter(
                a -> a.contains("ANOMALY BABBLING_DATA " + TARGET)
            ).count() == 1,
            "Exactly one target anomaly"
        );

        require(
            monitor.getAlerts().stream().filter(
                a -> a.contains("ANOMALY BABBLING_DATA " + S1)
            ).count() == 1 && monitor.getAlerts().stream().noneMatch(
                a -> a.contains("ANOMALY BABBLING_DATA " + S2)),
            "Supplied sustained excess on trusted s1 remains visible as an anomaly"
        );

        require(
            !admin.isIsolated(S1)
                && !admin.isIsolated(S2),
            "Healthy switches remain unisolated"
        );

        boolean recovered =
            monitor.getAlerts().stream().anyMatch(
                a -> a.contains(
                    "RECOVERED BABBLING_DATA " + TARGET
                )
            );

        require(
            recovered == !expectIsolation,
            "Recovery alert matches isolation state"
        );

        if (expectIsolation) {

            require(
                firstIsolation == 11,
                "Automatic isolation at expected sample"
            );

            require(
                admin.isIsolated(TARGET),
                "Target remains isolated until administrative reinstatement"
            );

            require(
                commands.size() == 2,
                "Exactly two port-disable commands"
            );

            require(
                commands.contains(S1 + "/1 enabled=false")
                && commands.contains(S2 + "/1 enabled=false"),
                "Correct neighbouring ports disabled"
            );

            // Link discovery may remove these links after the ports go down.
            links.clear();
            admin.isolate(TARGET);
            require(commands.size() == 2,
                    "Repeated isolation preserves the original saved ports");

            admin.reinstate(TARGET);

            require(
                !admin.isIsolated(TARGET),
                "Manual reinstatement successful"
            );

            require(
                commands.size() == 4
                && commands.contains(S1 + "/1 enabled=true")
                && commands.contains(S2 + "/1 enabled=true"),
                "Both neighbouring ports re-enabled"
            );

            admin.reinstate(TARGET);
            require(commands.size() == 4, "Repeated reinstatement is a no-op");

            try {
                admin.isolate(TARGET);
                throw new AssertionError("Empty-link isolation must fail");
            } catch (org.uni.lu.ServiceException expected) {
                require(!admin.isIsolated(TARGET) && commands.size() == 4,
                        "Missing links cannot produce a false isolation state");
            }

        } else {

            require(
                firstIsolation == -1
                && !admin.isIsolated(TARGET),
                "No automatic isolation"
            );

            require(
                commands.isEmpty(),
                "No port-state changes"
            );
        }
    }

    // Synthetic series, one sample per device every 5 seconds (30 samples each):
    //  - s0 excess rate is 80 packets/s during samples 10-19, otherwise 3
    //  - trusted s1 has two consecutive 73 packets/s samples (5-6), otherwise 7
    //  - trusted s2 stays at 8
    // Rows are "sampleIndex,device,excessRatePacketsPerSecond".
    private static List<String> syntheticSamples() {
        List<String> rows = new ArrayList<>();

        for (int i = 0; i < 30; i++) {
            rows.add(i + "," + TARGET + "," + (i >= 10 && i < 20 ? 80 : 3));
            rows.add(i + "," + S1 + "," + (i == 5 || i == 6 ? 73 : 7));
            rows.add(i + "," + S2 + ",8");
        }

        return rows;
    }

    public static void main(String[] args) throws Exception {
        List<String> samples = syntheticSamples();

        runScenario(
            "DETECTION ONLY",
            false,
            TARGET.toString(),
            false,
            samples
        );

        runScenario(
            "AUTOMATIC ISOLATION",
            true,
            TARGET.toString(),
            true,
            samples
        );

        runScenario(
            "UNAUTHORISED TARGET",
            true,
            "of:00000000000000ff",
            false,
            samples
        );

        System.out.println(
            "\nBABBLING_INTEGRATION_TEST PASSED"
        );
    }
}
