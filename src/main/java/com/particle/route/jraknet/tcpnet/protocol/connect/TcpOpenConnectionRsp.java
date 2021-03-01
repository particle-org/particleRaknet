package com.particle.route.jraknet.tcpnet.protocol.connect;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.Failable;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class TcpOpenConnectionRsp extends RakNetPacket implements Failable {

    public boolean magic;
    public long serverGuid;
    public InetSocketAddress clientAddress;
    private boolean failed;

    public TcpOpenConnectionRsp(Packet packet) {
        super(packet);
    }

    public TcpOpenConnectionRsp() {
        super(TcpMessageIdentifier.TCP_CONNECT_SUCCEED);
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
