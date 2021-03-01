package com.particle.route.jraknet.bungeenet.client;

import com.particle.route.jraknet.bungeenet.session.BungeePacketDecode;
import com.particle.route.jraknet.bungeenet.session.BungeePacketEncode;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;

@ChannelHandler.Sharable
public class BungeeClientChannelInitializer extends ChannelInitializer<Channel> {

    private BungeeClientHandler bungeeClientHandler;

    public BungeeClientChannelInitializer(BungeeClientHandler bungeeClientHandler) {
        this.bungeeClientHandler = bungeeClientHandler;
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new BungeePacketEncode());
        pipeline.addLast(new BungeePacketDecode());
        pipeline.addLast("handler", bungeeClientHandler);
    }

    /**
     * 动态添加心跳的handle
     * @param channel
     */
    public void addHeardBeatIdleHandler(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new HeartbeatIdleStateHandler());
    }
}
