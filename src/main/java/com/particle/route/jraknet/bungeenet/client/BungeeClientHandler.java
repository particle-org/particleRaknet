package com.particle.route.jraknet.bungeenet.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.Attribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@ChannelHandler.Sharable
public class BungeeClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BungeeClientHandler.class);

    /**
     * 根据chanel获取bungeeClient
     * @param ctx
     * @return
     */
    private BungeeClient getChannelUuid(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        Attribute<BungeeClient> channelIndexAttr = channel.attr(BungeeBootStrap.BUNGEE_CHANNEL_INDEX);
        if (channelIndexAttr == null) {
            return null;
        }
        BungeeClient decoClient = channelIndexAttr.get();
        return  decoClient;
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.debug("channel inactive!");
        BungeeClient decoClient = this.getChannelUuid(ctx);
        if (decoClient != null) {
            // 处理断掉的连接
            decoClient.shutdown("server channelInactive");
        } else {
            logger.error("channelInactive can not obtain BungeeClient, so closed channel!");
            Channel channel = ctx.channel();
            if (channel != null) {
                channel.close();
            }
        }
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        BungeeClient decoClient = this.getChannelUuid(ctx);
        if (decoClient == null) {
            logger.error("channelRead can not obtain BungeeClient, so closed channel!");
            Channel channel = ctx.channel();
            if (channel != null) {
                channel.close();
            }
            return;
        }

        // 读取数据
        if (msg instanceof ByteBuf) {
            decoClient.onHandleMessage((ByteBuf) msg);
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("bungeeClient chanel exception: ", cause);
        if (ctx != null && ctx.channel() != null) {
            BungeeClient decoClient = this.getChannelUuid(ctx);
            if (decoClient == null) {
                logger.error("exceptionCaught can not obtain BungeeClient, so closed channel!");
                Channel channel = ctx.channel();
                if (channel != null) {
                    channel.close();
                }
                return;
            }
            SocketAddress remote1 = ctx.channel().remoteAddress();
            if (remote1 != null) {
                InetSocketAddress sender = (InetSocketAddress) remote1;
                decoClient.shutdown("server channel exceptionCaught");
                return;
            }
        }
        logger.error("BungeeClientHandler exceptionCaught时候，没拿到ChannelHandlerContext的远程IP");
    }

}
