package com.particle.route.jraknet.tcpnet.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class DecoPackage {

    public static  final Byte SHEAD_DATA = 0x76;

    private Byte headData = SHEAD_DATA;

    private int contentLength;

    private ByteBuf content;

    private int retryCounts = 0;

    public DecoPackage (ByteBuf content) {
        this.content = content.retain();
        if (content != null) {
            contentLength = content.readableBytes();
        }
    }

    public DecoPackage (int contentLength, ByteBuf content) {
        this.contentLength = contentLength;
        this.content = content.retain();
    }

    public DecoPackage (int contentLength, byte[] data) {
        this.contentLength = contentLength;
        this.content = Unpooled.wrappedBuffer(data);
    }

    public Byte getHeadData() {
        return headData;
    }

    public void setHeadData(Byte headData) {
        this.headData = headData;
    }

    public int getContentLength() {
        return contentLength;
    }

    public void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }

    public ByteBuf getContent() {
        return content;
    }

    public void setContent(ByteBuf content) {
        this.content = content;
    }

    public int getRetryCounts() {
        return retryCounts;
    }

    public void setRetryCounts(int retryCounts) {
        this.retryCounts = retryCounts;
    }

    @Override
    public String toString() {
        return "DecoPackage{" +
                "headData=" + headData +
                ", contentLength=" + contentLength +
                ", content=" + content +
                '}';
    }
}
