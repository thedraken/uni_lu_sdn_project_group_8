import org.uni.lu.DeviceAdmin;
import org.uni.lu.NetworkMonitorComponent;
import org.uni.lu.domain.PacketDetail;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public class DataPlaneRateTest {

    private static final DeviceId TARGET =
        DeviceId.deviceId("of:000000000000000a");

    private static final DeviceId HEALTHY =
        DeviceId.deviceId("of:0000000000000001");

    private static final Instant START =
        Instant.parse("2026-01-01T00:00:00Z");

    private static NetworkMonitorComponent monitor;
    private static Method detector;

    private static void setField(
            String name, Object value) throws Exception {

        Field field = monitor.getClass()
                .getDeclaredField(name);

        field.setAccessible(true);
        field.set(monitor, value);
    }

    private static void require(
            boolean condition, String message) {

        if (!condition) {
            throw new AssertionError(message);
        }

        System.out.println("PASS: " + message);
    }

    private static void sample(
            DeviceId device, long second,
            long rx, long tx) throws Exception {

        Instant timestamp = START.plusSeconds(second);

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
    }

    private static void samplePorts(
            long second, long rx1, long tx1,
            long rx2, long tx2) throws Exception {

        Instant timestamp = START.plusSeconds(second);

        detector.invoke(
                monitor,
                TARGET,
                List.of(
                    new PacketDetail(TARGET, PortNumber.portNumber(1),
                                     rx1, tx1, 0, 0, timestamp),
                    new PacketDetail(TARGET, PortNumber.portNumber(2),
                                     rx2, tx2, 0, 0, timestamp)),
                Set.of(PortNumber.portNumber(1), PortNumber.portNumber(2)),
                timestamp
        );
    }

    private static long count(
            String type, DeviceId device) {

        return monitor.getAlerts().stream()
                .filter(a -> a.contains(
                    type + " BABBLING_DATA " + device))
                .count();
    }

    public static void main(String[] args)
            throws Exception {

        monitor = new NetworkMonitorComponent();

        setField("deviceAdmin", new DeviceAdmin());
        setField("autoIsolateBabbling", false);
        setField("babblingExcessThreshold", 50);
        setField("babblingSustainedSamples", 2);

        detector = monitor.getClass()
                .getDeclaredMethod(
                    "checkDataPlaneBabbling",
                    DeviceId.class,
                    List.class,
                    Set.class,
                    Instant.class
                );

        detector.setAccessible(true);

        sample(TARGET, 0, 50, 50);
        sample(TARGET, 5, 50, 50);

        require(
            monitor.getAlerts().isEmpty(),
            "Normal traffic produces no alert"
        );

        sample(TARGET, 10, 50, 300);
        sample(TARGET, 15, 50, 300);

        require(
            monitor.getAlerts().isEmpty(),
            "Exactly 50 excess packets/s is normal"
        );

        sample(TARGET, 20, 50, 550);

        require(
            monitor.getAlerts().isEmpty(),
            "One abnormal sample produces no alert"
        );

        sample(TARGET, 25, 50, 50);

        require(
            monitor.getAlerts().isEmpty(),
            "Transient spike does not trigger detection"
        );

        sample(HEALTHY, 25, 50, 50);

        sample(TARGET, 30, 0, 500);
        sample(HEALTHY, 30, 50, 50);

        require(
            count("ANOMALY", TARGET) == 0,
            "No premature detection"
        );

        sample(TARGET, 35, 0, 500);

        require(
            count("ANOMALY", TARGET) == 1,
            "Sustained excess detected with zero RX"
        );

        sample(TARGET, 40, 0, 500);
        sample(TARGET, 45, 0, 500);

        require(
            count("ANOMALY", TARGET) == 1,
            "No duplicate anomaly alerts"
        );

        sample(HEALTHY, 35, 50, 50);

        require(
            count("ANOMALY", HEALTHY) == 0,
            "Healthy switch state is independent"
        );

        sample(TARGET, 50, 50, 50);

        require(
            count("RECOVERED", TARGET) == 1,
            "Recovery recorded"
        );

        sample(TARGET, 55, 0, 500);
        sample(TARGET, 80, 0, 500);
        sample(TARGET, 85, 0, 500);

        require(
            count("ANOMALY", TARGET) == 1,
            "Long polling gap resets persistence"
        );

        sample(TARGET, 90, 0, 500);

        require(
            count("ANOMALY", TARGET) == 2,
            "New sustained anomaly detected"
        );

        long previousAnomalies = count("ANOMALY", TARGET);
        monitor.resetCounters();
        sample(TARGET, 100, 0, 500);
        sample(TARGET, 105, 0, 500);
        monitor.resetCounters();
        sample(TARGET, 110, 0, 500);
        sample(TARGET, 115, 0, 500);
        require(count("ANOMALY", TARGET) == previousAnomalies,
                "Counter reset discards the previous sustained sequence");

        detector.invoke(monitor, TARGET, List.of(), Set.of(), START.plusSeconds(120));
        sample(TARGET, 125, 0, 500);
        sample(TARGET, 130, 0, 500);
        require(count("ANOMALY", TARGET) == previousAnomalies,
                "Empty statistics break persistence and require a new interval");

        // Keep the same two ports in every sample, so that a changed port set cannot
        // interrupt the sequence by itself. Port 1 reports a negative counter at 145 s,
        // while the summed counters stay positive and would count as a second abnormal sample.
        samplePorts(135, 50, 300, 50, 300);
        samplePorts(140, 50, 300, 50, 300);
        samplePorts(145, -1, 300, 50, 300);
        samplePorts(150, 50, 300, 50, 300);
        samplePorts(155, 50, 300, 50, 300);
        require(count("ANOMALY", TARGET) == previousAnomalies,
                "An invalid individual port interrupts a sustained sequence");
        samplePorts(160, 50, 300, 50, 300);
        require(count("ANOMALY", TARGET) == previousAnomalies + 1,
                "Detection resumes after two valid abnormal intervals");

        System.out.println(
            "DATA_PLANE_DETECTION_TEST PASSED"
        );
    }
}
