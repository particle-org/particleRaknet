package com.particle.route.jraknet.bungeenet.session;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BungeePacketEncode extends MessageToByteEncoder<ByteBuf> {

    private static final Logger logger = LoggerFactory.getLogger(BungeePacketEncode.class);

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, ByteBuf writeData, ByteBuf byteBuf) throws Exception {
        byteBuf.writeBytes(writeData);
    }

    private void writeUnsignedVarLong(ByteBuf byteBuf, long value) {
        while((value & -128L) != 0L) {
            byteBuf.writeByte((byte)((int)(value & 127L | 128L)));
            value >>>= 7;
        }

        byteBuf.writeByte((byte)((int)value));
    }
}
