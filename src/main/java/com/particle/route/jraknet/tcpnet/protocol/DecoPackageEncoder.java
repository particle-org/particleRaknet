package com.particle.route.jraknet.tcpnet.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DecoPackageEncoder extends MessageToByteEncoder<DecoPackage> {

    private static final Logger logger = LoggerFactory.getLogger(DecoPackageEncoder.class);

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, DecoPackage decoPackage, ByteBuf byteBuf) {

        // 写头标志
        byteBuf.writeByte(decoPackage.getHeadData() & 0xFF);
        // 写长度
        byteBuf.writeInt(decoPackage.getContentLength());
        // 写入消息内容
        byteBuf.writeBytes(decoPackage.getContent());
    }
}
