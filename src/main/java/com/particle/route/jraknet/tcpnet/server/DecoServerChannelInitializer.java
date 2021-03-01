package com.particle.route.jraknet.tcpnet.server;

import com.particle.route.jraknet.tcpnet.protocol.DecoPackageDecode;
import com.particle.route.jraknet.tcpnet.protocol.DecoPackageEncoder;
import com.particle.route.jraknet.tcpnet.util.PropType;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

@ChannelHandler.Sharable
public class DecoServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    private DecoServerHandler decoServerHandler;

    public DecoServerChannelInitializer(DecoServerHandler decoServerHandler) {
        this.decoServerHandler = decoServerHandler;
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) {
        ChannelPipeline pipeline = socketChannel.pipeline();
        pipeline.addLast(new IdleStateHandler(PropType.readerIdleTime, PropType.writerIdleTime, PropType.allIdleTime, TimeUnit.SECONDS));
        pipeline.addLast(new DecoPackageEncoder());
        pipeline.addLast(new DecoPackageDecode());
        pipeline.addLast("handler", decoServerHandler);
    }
}
