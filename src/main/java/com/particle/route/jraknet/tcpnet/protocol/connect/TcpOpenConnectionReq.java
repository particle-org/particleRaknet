package com.particle.route.jraknet.tcpnet.protocol.connect;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.Failable;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;

public class TcpOpenConnectionReq extends RakNetPacket implements Failable {

    public boolean magic;
    public long clientGuid;
    public long timestamp;
    private boolean failed;

    public TcpOpenConnectionReq() {
        super(TcpMessageIdentifier.TCP_CONNECT);
    }

    public TcpOpenConnectionReq(Packet packet) {
        super(packet);
    }

    @Override
    public void encode() {
        try {
            this.writeMagic();
            this.writeLong(clientGuid);
            this.writeLong(timestamp);
        } catch (Exception e) {
            this.magic = false;
            this.clear();
            this.failed = true;
        }
    }

    @Override
    public void decode() {
        try {
            this.magic = this.checkMagic();
            this.clientGuid = this.readLong();
            this.timestamp = this.readLong();
        } catch (Exception e) {
            this.magic = false;
            this.clear();
            this.failed = true;
        }
    }

    @Override
    public boolean failed() {
        return failed;
    }
}
