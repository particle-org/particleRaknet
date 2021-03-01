package com.particle.route.jraknet.tcpnet.protocol.connect;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;

public class TcpConnectedPing extends RakNetPacket {

    public long identifier;
    public long pingId;

    public TcpConnectedPing() {
        super(TcpMessageIdentifier.TCP_PING);
    }

    public TcpConnectedPing(Packet packet) {
        super(packet);
    }

    @Override
    public void encode() {
        this.writeLong(identifier);
        this.writeLong(pingId);
    }

    @Override
    public void decode() {
        this.identifier = this.readLong();
        this.pingId = this.readLong();
    }
}
