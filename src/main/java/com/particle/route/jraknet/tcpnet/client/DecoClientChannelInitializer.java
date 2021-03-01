package com.particle.route.jraknet.tcpnet.client;

import com.particle.route.jraknet.tcpnet.protocol.DecoPackageDecode;
import com.particle.route.jraknet.tcpnet.protocol.DecoPackageEncoder;
import com.particle.route.jraknet.tcpnet.util.PropType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

@ChannelHandler.Sharable
public class DecoClientChannelInitializer extends ChannelInitializer<Channel> {

    private DecoClientHandler decoClientHandler;

    public DecoClientChannelInitializer (DecoClientHandler decoClientHandler) {
        this.decoClientHandler = decoClientHandler;
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new IdleStateHandler(PropType.readerIdleTime, PropType.writerIdleTime, PropType.allIdleTime, TimeUnit.SECONDS));
        pipeline.addLast(new DecoPackageEncoder());
        pipeline.addLast(new DecoPackageDecode());
        pipeline.addLast("handler",decoClientHandler);
    }
}
