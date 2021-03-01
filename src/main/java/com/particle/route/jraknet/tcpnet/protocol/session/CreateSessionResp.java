package com.particle.route.jraknet.tcpnet.protocol.session;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.Failable;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class CreateSessionResp extends RakNetPacket implements Failable {

    public boolean magic;
    public long serverGuid;
    public InetSocketAddress clientAddress;
    private boolean failed;

    public CreateSessionResp(Packet packet) {
        super(packet);
    }

    public CreateSessionResp() {
        super(TcpMessageIdentifier.TCP_NEW_SESSION_SUCCEED);
    }

    @Override
    public void encode() {
        try {
            this.writeMagic();
            this.writeLong(serverGuid);
            this.writeAddress(clientAddress);
        } catch (UnknownHostException e) {
            this.magic =false;
            this.serverGuid = 0;
            this.clientAddress = null;
            this.clear();
            failed = true;
        }
    }

    @Override
    public void decode() {
        try {
            this.magic = this.checkMagic();
            this.serverGuid = this.readLong();
            this.clientAddress = this.readAddress();
        } catch (UnknownHostException e) {
            this.magic =false;
            this.serverGuid = 0;
            this.clientAddress = null;
            this.clear();
            failed = true;
        }
    }

    @Override
    public boolean failed() {
        return failed;
    }
}
