package org.uni.lu.domain;

import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

import java.time.Instant;
import java.util.Objects;

public class PacketDetail {


    private final DeviceId deviceId;
    private final PortNumber portNumber;
    private final long packetsReceived;
    private final long packetsSent;
    private final long packetsReceivedDropped;
    private final long packetsSentDropped;
    private final Instant dateTime;


    public PacketDetail(DeviceId deviceId, PortNumber portNumber, long packetsReceived, long packetsSent, long packetsReceivedDropped, long packetsSentDropped, Instant dateTime) {
        this.deviceId = deviceId;
        this.portNumber = portNumber;
        this.packetsReceived = packetsReceived;
        this.packetsSent = packetsSent;
        this.packetsReceivedDropped = packetsReceivedDropped;
        this.packetsSentDropped = packetsSentDropped;
        this.dateTime = dateTime;
    }

    public DeviceId getDeviceId() {
        return deviceId;
    }

    public PortNumber getPortNumber() {
        return portNumber;
    }

    public long getPacketsReceived() {
        return packetsReceived;
    }

    public long getPacketsSent() {
        return packetsSent;
    }

    public long getPacketsSentDropped() {
        return packetsSentDropped;
    }

    public long getPacketsReceivedDropped() {
        return packetsReceivedDropped;
    }

    public Instant getDateTime() {
        return dateTime;
    }

    @Override
    public String toString() {
        return "PacketDetail{" +
                "deviceId=" + deviceId +
                ", portNumber=" + portNumber +
                ", packetsReceived=" + packetsReceived +
                ", packetsSent=" + packetsSent +
                ", packetsReceivedDropped=" + packetsReceivedDropped +
                ", packetsSentDropped=" + packetsSentDropped +
                ", dateTime=" + dateTime +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PacketDetail that = (PacketDetail) o;
        return Objects.equals(deviceId, that.deviceId) && Objects.equals(portNumber, that.portNumber) && Objects.equals(dateTime, that.dateTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, portNumber, dateTime);
    }
}
