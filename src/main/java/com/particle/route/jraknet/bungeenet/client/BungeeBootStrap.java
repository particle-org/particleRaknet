package com.particle.route.jraknet.bungeenet.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;

public class BungeeBootStrap {


    public static final AttributeKey<BungeeClient> BUNGEE_CHANNEL_INDEX = AttributeKey.valueOf("bungee_channel_index");

    private static BungeeBootStrap bungeeBootStrap = new BungeeBootStrap();

    public static BungeeBootStrap getInstance() {
        return bungeeBootStrap;
    }

    // Networking data
    private Bootstrap clientBootStrap;

    private BungeeClientChannelInitializer channelInitializer;

    private BungeeBootStrap () {
        this.clientBootStrap = new Bootstrap();
        EventLoopGroup groupEventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        this.clientBootStrap.group(groupEventLoopGroup);
        this.clientBootStrap.channel(NioSocketChannel.class);
        channelInitializer = new BungeeClientChannelInitializer(new BungeeClientHandler());
        this.clientBootStrap.handler(channelInitializer);
    }

    public Bootstrap getClientBootStrap() {
        return clientBootStrap;
    }

    /**
     * 返回channel initial
     * @return
     */
    public BungeeClientChannelInitializer getChannelInitializer() {
        return this.channelInitializer;
    }

}
