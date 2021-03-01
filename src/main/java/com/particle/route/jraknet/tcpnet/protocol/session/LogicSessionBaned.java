package com.particle.route.jraknet.tcpnet.protocol.session;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;

public class LogicSessionBaned extends RakNetPacket {

    public long serverGuid;

    public LogicSessionBaned() {
        super(TcpMessageIdentifier.TCP_CONNECT_BAN);
    }

    public LogicSessionBaned(Packet packet) {
        super(packet);
    }

    @Override
    public void encode() {
        this.writeLong(serverGuid);
    }

    @Override
    public void decode() {
        this.serverGuid = this.readLong();
    }
}
