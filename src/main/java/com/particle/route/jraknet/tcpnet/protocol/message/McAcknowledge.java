package com.particle.route.jraknet.tcpnet.protocol.message;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.protocol.Failable;
import com.particle.route.jraknet.protocol.message.acknowledge.Acknowledge;
import com.particle.route.jraknet.protocol.message.acknowledge.AcknowledgeType;
import com.particle.route.jraknet.protocol.message.acknowledge.Record;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;

public class McAcknowledge extends Acknowledge implements Failable {

    public InetSocketAddress address;
    public long clientGuid;

    private boolean failed;

    public McAcknowledge(short type) {
        super(type);
        if (type != ACKNOWLEDGED && type != NOT_ACKNOWLEDGED) {
            throw new IllegalArgumentException("Must be ACKNOWLEDGED or NOT_ACKNOWLEDGED");
        }
        this.records = new ArrayList<Record>();
    }

    public McAcknowledge(AcknowledgeType type) {
        this(type.getId());
    }

    public McAcknowledge(Packet packet) {
        super(packet);
        this.records = new ArrayList<Record>();
    }

    @Override
    public void encode() {
        try {
            this.writeAddress(address);
            this.writeLong(clientGuid);
            super.encode();
        } catch (UnknownHostException e) {
            this.address = null;
            this.clientGuid = 0;
            failed = true;
        }
    }

    @Override
    public void decode() {
        try {
            this.address = this.readAddress();
            this.clientGuid = this.readLong();
            super.decode();
        } catch (UnknownHostException e) {
            this.address = null;
            this.clientGuid = 0;
            failed = true;
        }
    }

    @Override
    public boolean failed() {
        return failed;
    }
}
