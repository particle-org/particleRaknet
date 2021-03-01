package com.particle.route.jraknet.bungeenet.session;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;


public class BungeePacketDecode extends ByteToMessageDecoder {


    private boolean hasNextPacket(ByteBuf byteBuf) {
        // 下一个包的长度
        long length = 0L;
        int bytes = 0;
        byte in;
        do {
            // 一个字节都没有
            if (byteBuf.readableBytes() < 1) {
                return false;
            }
            in = byteBuf.readByte();
            length = length | (((long)in & 127) << bytes++ * 7);
            // 出异常了
            if (bytes > 10) {
                throw new RuntimeException("VarLong too big");
            }
        } while((in & 128) == 128);

        // 如果数据的长度不够，返回false
        if (byteBuf.readableBytes() < length) {
            return false;
        }
        return true;
    }

    private long readUnsignedVarLong(ByteBuf byteBuf) {
        long out = 0L;
        int bytes = 0;

        byte in;
        do {
            in = byteBuf.readByte();
            out = out | (((long)in & 127) << bytes++ * 7);
            if (bytes > 10) {
                throw new RuntimeException("VarLong too big");
            }
        } while((in & 128) == 128);

        return out;
    }

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
        int beginReader = byteBuf.readerIndex();
        // 如果存在数据
        if (this.hasNextPacket(byteBuf)) {
            // 还原读指针
            byteBuf.readerIndex(beginReader);
            int length = (int)this.readUnsignedVarLong(byteBuf);
            // 读取data数据
            byte[] data = new byte[length];
            byteBuf.readBytes(data);
            list.add(Unpooled.wrappedBuffer(data));
        } else {
            // 还原读指针
            byteBuf.readerIndex(beginReader);
            return;
        }
    }
}
