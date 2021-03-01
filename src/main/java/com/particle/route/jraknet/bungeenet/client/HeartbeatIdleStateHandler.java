package com.particle.route.jraknet.bungeenet.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

@ChannelHandler.Sharable
public class HeartbeatIdleStateHandler  extends IdleStateHandler {


    public HeartbeatIdleStateHandler() {
        super(0, 5, 0);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        ByteBuf rawemptyframedpacket = ctx.alloc().buffer();
        rawemptyframedpacket.writeByte(0);
        ctx.writeAndFlush(rawemptyframedpacket);
    }

    private void writeUnsignedVarLong(ByteBuf byteBuf, long value) {
        while((value & -128L) != 0L) {
            byteBuf.writeByte((byte)((int)(value & 127L | 128L)));
            value >>>= 7;
        }

        byteBuf.writeByte((byte)((int)value));
    }
}
