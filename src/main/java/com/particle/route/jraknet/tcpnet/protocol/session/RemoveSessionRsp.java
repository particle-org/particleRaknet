package com.particle.route.jraknet.tcpnet.protocol.session;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.Failable;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class RemoveSessionRsp extends RakNetPacket implements Failable {

    public boolean magic;
    public InetSocketAddress address;
    public long clientGuid;
    private boolean failed;

    public RemoveSessionRsp(Packet packet) {
        super(packet);
    }

    public RemoveSessionRsp() {
        super(TcpMessageIdentifier.TCP_REMOVE_SESSION_SUCCEED);
    }

    @Override
    public void encode() {
        try {
            this.writeMagic();
            this.writeAddress(address);
            this.writeLong(clientGuid);
        } catch (UnknownHostException e) {
            this.magic = false;
            this.address = null;
            this.clear();
            this.failed = true;
        }
    }

    @Override
    public void decode() {
        try {
            this.magic = this.checkMagic();
            this.address = this.readAddress();
            this.clientGuid = this.readLong();
        } catch (UnknownHostException e) {
            this.magic = false;
            this.address = null;
            this.clear();
            this.failed = true;
        }
    }

    @Override
    public boolean failed() {
        return failed;
    }
}
