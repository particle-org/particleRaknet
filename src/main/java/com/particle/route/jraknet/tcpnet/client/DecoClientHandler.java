package com.particle.route.jraknet.tcpnet.client;

import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.tcpnet.protocol.DecoPackage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.Attribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@ChannelHandler.Sharable
public class DecoClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DecoClientHandler.class);

    // Logger name
    private final String loggerName;

    // Handler data
    private final DecoClientManager decoClientManager;

    private InetSocketAddress causeAddress;

    /**
     * 最多重试次数
     */
    private static final int TRY_TIMES = 3;

    private int loss_connect_time = 0;

    private int currentTime = 0;

    public DecoClientHandler (DecoClientManager decoClientManager) {
        this.loggerName = "DecoClientHandler:";
        this.decoClientManager = decoClientManager;
    }

    /**
     * 获取uuid
     * @param ctx
     * @return
     */
    private DecoClient getChannelUuid(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        Attribute<DecoClient> channelIndexAttr = channel.attr(DecoClientManager.DECO_CHANNEL_INDEX);
        if (channelIndexAttr == null) {
            return null;
        }
        DecoClient decoClient = channelIndexAttr.get();
        return  decoClient;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        DecoClient decoClient = this.getChannelUuid(ctx);
        if (decoClient != null) {
            logger.debug("channelActive channelIndex: {}", decoClient.getGloballyUniqueId());
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.debug("channel inactive!");
        DecoClient decoClient = this.getChannelUuid(ctx);
        if (decoClient != null) {
            decoClient.disconnectAndShutdown("收到channelInactive消息");
        } else {
            logger.error("channelInactive can not obtain decoclient, so closed channel!");
            Channel channel = ctx.channel();
            if (channel != null) {
                channel.close();
            }
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        DecoClient decoClient = this.getChannelUuid(ctx);
        if (decoClient == null) {
            logger.error("userEventTriggered can not obtain decoclient, so closed channel!");
            Channel channel = ctx.channel();
            if (channel != null) {
                channel.close();
            }
            return;
        }
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.WRITER_IDLE) {
                // 发送监听消息
                decoClient.checkUserEventTriggered();
            } else if (event.state() == IdleState.READER_IDLE) {
                decoClient.addLossTriggerTime(1);
                SocketAddress remote1 = ctx.channel().remoteAddress();
                InetSocketAddress sender = (InetSocketAddress) remote1;
                //logger.info(String.format("一个idle内没有接收到客户端[%s]的信息了", sender));
                // 由于nukkit不处理PingPacket，所以，裸端会长时间收不到包
//                if (decoClient.getLossTriggerTime() > PropType.MAX_CLIENT_IDLE_READ_TIMES) {
//                    logger.info("由于客户端在多个idle内没有收到读的数据，准备关掉连接");
//                    decoClient.disconnectAndShutdown("userEventTriggered 多个idle内没有收到消息");
//                }
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        DecoClient decoClient = this.getChannelUuid(ctx);
        if (decoClient == null) {
            logger.error("channelRead can not obtain decoclient, so closed channel!");
            Channel channel = ctx.channel();
            if (channel != null) {
                channel.close();
            }
            return;
        }
        if (msg instanceof DecoPackage) {
            decoClient.setLossTriggerTime(0);
            DecoPackage decoPackage = (DecoPackage) msg;
            SocketAddress remote1 = ctx.channel().remoteAddress();
            InetSocketAddress sender = (InetSocketAddress) remote1;
            RakNetPacket packet = new RakNetPacket(decoPackage.getContent());

            decoClient.handleMessage(packet, sender);
            decoPackage.getContent().release(); // No longer needed
        }
    }

    /**
     * 会去除所有的session，同时关闭channel
     * @param ctx
     * @param cause
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (ctx != null && ctx.channel() != null) {
            DecoClient decoClient = this.getChannelUuid(ctx);
            if (decoClient == null) {
                logger.error("exceptionCaught can not obtain decoclient, so closed channel!");
                Channel channel = ctx.channel();
                if (channel != null) {
                    channel.close();
                }
                return;
            }
            SocketAddress remote1 = ctx.channel().remoteAddress();
            if (remote1 != null) {
                InetSocketAddress sender = (InetSocketAddress) remote1;
                decoClient.handleHandlerException(sender, cause);
                return;
            }
        }
        logger.error("exceptionCaught时候，没拿到ChannelHandlerContext的远程IP");
    }
}
