package com.particle.route.jraknet.tcpnet.protocol.connect;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;

public class TcpConnectedPong extends RakNetPacket {

    public long identifier;

    public long pongId;

    public TcpConnectedPong() {
        super(TcpMessageIdentifier.TCP_PONE);
    }

    public TcpConnectedPong(Packet packet) {
        super(packet);
    }

    @Override
    public void encode() {
        this.writeLong(identifier);
        this.writeLong(pongId);
    }

    @Override
    public void decode() {
        this.identifier = this.readLong();
        this.pongId = this.readLong();
    }
}
