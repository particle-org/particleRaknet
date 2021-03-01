package com.particle.route.jraknet.protocol.message;

import java.util.ArrayList;

public class SendedEncapsulatePacket {
    private int index;
    private int retryTime;
    private long lastSendTime;
    private ArrayList<EncapsulatedPacket> packets;

    public SendedEncapsulatePacket(int index, ArrayList<EncapsulatedPacket> packets) {
        this.index = index;
        this.retryTime = 0;
        this.lastSendTime = System.currentTimeMillis();
        this.packets = packets;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public void increaseRetryTime() {
        this.retryTime++;
    }

    public int getRetryTime() {
        return retryTime;
    }

    public void setLastSendTime(long lastSendTime) {
        this.lastSendTime = lastSendTime;
    }

    public long getLastSendTime() {
        return lastSendTime;
    }

    public ArrayList<EncapsulatedPacket> getPackets() {
        return packets;
    }
}
