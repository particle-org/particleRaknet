package com.particle.route.jraknet.protocol.status;

import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.identifier.Identifier;
import com.particle.route.jraknet.protocol.ConnectionType;
import com.particle.route.jraknet.protocol.Failable;
import com.particle.route.jraknet.protocol.MessageIdentifier;

/**
 * 解析网易中国版这边的pong包
 */
public class NeteaseUnconnectedPong extends RakNetPacket implements Failable {

    public long pingID;
    public long serverID;
    public Identifier identifier;

    public NeteaseUnconnectedPong() {
        super(MessageIdentifier.ID_UNCONNECTED_PONG);
    }

    @Override
    public void encode() {
        this.writeLong(pingID);
        this.writeLong(serverID);
        this.writeMagic();
        this.writeString(identifier.build());
    }

    @Override
    public void decode() {
        this.pingID = this.readLong();
        this.serverID = this.readLong();
        this.checkMagic();
        this.identifier = new Identifier(this.readString(), ConnectionType.JRAKNET);
    }

    @Override
    public boolean failed() {
        return false;
    }
}
