package com.particle.route.jraknet.tcpnet.server;

import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.tcpnet.protocol.DecoPackage;
import com.particle.route.jraknet.tcpnet.util.PropType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

@ChannelHandler.Sharable
public class DecoServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DecoServerHandler.class);


    private final String loggerName;
    private final DecoServer decoServer;
    private InetSocketAddress causeAddress;

    private ConcurrentHashMap<InetSocketAddress, LossStatistics> lossConnects = null;

    public DecoServerHandler(DecoServer server) {
        this.loggerName = "server handler #" + server.getGloballyUniqueId();
        this.decoServer = server;
        this.lossConnects = new ConcurrentHashMap<>();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        SocketAddress remote1 = ctx.channel().remoteAddress();
        InetSocketAddress sender = (InetSocketAddress) remote1;
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.error(String.format("channelInactive, 客户端信息[%s]", ctx.channel().remoteAddress()));
        // 当管道可不用的时候，需要释放指定physical连接
        Channel channel = ctx.channel();
        SocketAddress remote1 = channel.remoteAddress();
        InetSocketAddress sender = (InetSocketAddress) remote1;
        if (this.lossConnects.containsKey(sender)) {
            this.lossConnects.remove(sender);
        }
        if (!decoServer.removePhysicalSession(sender, String.format("channelInactive[%s]", sender))) {
            if (channel.isActive()) {
                channel.close();
            }
        }
    }

    /**
     * 去除所有的session，同时会关闭channel
     * @param ctx
     * @param cause
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (ctx != null && ctx.channel() != null) {
            SocketAddress remote1 = ctx.channel().remoteAddress();
            if (remote1 != null) {
                InetSocketAddress sender = (InetSocketAddress) remote1;
                logger.info(String.format("exceptionCaught, sender[%s], causeAddress[%s]", sender, this.causeAddress));
                decoServer.handleHandlerException(sender, cause);
                return;
            }
        }
        logger.error("exceptionCaught时候，没拿到ChannelHandlerContext的远程IP");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DecoPackage) {
            DecoPackage decoPackage = (DecoPackage) msg;
            SocketAddress remote1 = ctx.channel().remoteAddress();
            InetSocketAddress sender = (InetSocketAddress) remote1;

            RakNetPacket packet = new RakNetPacket(decoPackage.getContent());

            // If an exception happens it's because of this address
            this.causeAddress = sender;

            decoServer.handleMessage(packet, sender, ctx);
            decoPackage.getContent().readerIndex(0);

            for (DecoServerListener listener : decoServer.getListeners()) {
                listener.handleNettyMessage(decoPackage.getContent(), sender);
            }

            ReferenceCountUtil.release(msg);
            this.causeAddress = null;
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                SocketAddress remote1 = ctx.channel().remoteAddress();
                InetSocketAddress sender = (InetSocketAddress) remote1;
                int lossCount = 0;
                if (this.lossConnects.containsKey(sender)) {
                    LossStatistics lossStatistics = this.lossConnects.get(sender);
                    lossCount = lossStatistics.getLossCount();
                    long lossTime = lossStatistics.getLossTime();
                    long now = System.currentTimeMillis();
                    if (now - lossTime < PropType.readerIdleTime * 1000 + 500) {
                        lossStatistics.setLossCount(++lossCount);
                        lossStatistics.setLossTime(now);
                    }
                } else {
                    this.lossConnects.put(sender, new LossStatistics());
                }
                logger.info(String.format("一个idle内没有接收到客户端[%s]的信息了", sender));
                if (lossCount > PropType.MAX_SERVER_IDLE_READ_TIMES) {
                    String errorMsg = String.format("由于服务端在多个idle内没有收到读的数据，准备关掉连接[%s]", sender);
                    logger.info(errorMsg);

                    if (!decoServer.removePhysicalSession(sender, errorMsg)) {
                        Channel channel = ctx.channel();
                        if (channel.isActive()) {
                            channel.close();
                        }
                    }
                }
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    protected class LossStatistics {

        private int lossCount = 1;

        private long lossTime = 0;

        public LossStatistics () {
            lossCount = 1;
            lossTime = System.currentTimeMillis();
        }

        public int getLossCount() {
            return lossCount;
        }

        public void setLossCount(int lossCount) {
            this.lossCount = lossCount;
        }

        public long getLossTime() {
            return lossTime;
        }

        public void setLossTime(long lossTime) {
            this.lossTime = lossTime;
        }
    }
}
