package com.particle.route.jraknet.bungeenet.client;

import com.particle.route.jraknet.RakNet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

public class BungeeClient {

    private static final Logger logger = LoggerFactory.getLogger(BungeeClient.class);

    private String serverHost;
    private int serverPort;
    //绑定在这个session上的数据，上层业务可以将自己的数据绑定在这个session中，而不需要建立hashmao索引，提高效率
    private Object context;

    // Session management
    private Channel channel = null;


    private final ConcurrentLinkedQueue<BungeeClientListener> listeners;


    public BungeeClient(String serverHost, int serverPort, Object context) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.context = context;
        this.listeners = new ConcurrentLinkedQueue<BungeeClientListener>();
        this.connect();
    }

    /**
     * 注册监听器
     * @param bungeeClientListener
     */
    public void registerListener(BungeeClientListener bungeeClientListener) {
        this.listeners.add(bungeeClientListener);
    }

    /**
     * 反注册监听器
     * @param bungeeClientListener
     */
    public void unRegisterListener(BungeeClientListener bungeeClientListener) {
        this.listeners.remove(bungeeClientListener);
    }

    /**
     * 获取上层环境
     * @return
     */
    public Object getContext () {
        return this.context;
    }

    public void shutdown(String reason) {
        if (this.channel != null) {
            logger.info("BungeeClient shutdown, {}:{}, reason:{}", this.serverHost, this.serverPort, reason);
            // 向管理中心，反注册处理decoClient
            this.channel.attr(BungeeBootStrap.BUNGEE_CHANNEL_INDEX).set(null);
            this.channel.close();
            this.channel = null;
            // 通知listener创建完毕
            for (BungeeClientListener listener : listeners) {
                listener.onDisconnect(this, "");
            }
        } else {
            logger.info("BungeeClient shutdown retry, {}:{}, reason:{}", this.serverHost, this.serverPort, reason);
        }
    }


    /**
     * 判断channel是否open
     * @return
     */
    private final boolean isChannelOpen() {
        if (channel == null) {
            return false;
        }
        return channel.isOpen();
    }

    /**
     * 发送消息
     * @param byteBuf
     */
    public void sendMessage(ByteBuf byteBuf) {
        if (!this.isChannelOpen()) {
            logger.error("bungeeClient to server[{}:{}] is closed, send message failed!",
                    this.serverHost, this.serverPort);
            return;
        }
        ChannelFuture channelFuture = channel.writeAndFlush(byteBuf);
        channelFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    logger.error("send netty message failed to {}:{}",
                            BungeeClient.this.serverHost, BungeeClient.this.serverPort);
                }
            }
        });
    }

    /**
     * 收到数据包
     * @param buf
     */
    protected void onHandleMessage(ByteBuf buf) {
        // 通知listener创建完毕
        for (BungeeClientListener listener : listeners) {
            listener.handleMessage(this, buf, RakNet.BUNGEECORE_CHANNEL);
        }
    }

    /**
     * 连接
     * @return
     */
    private boolean connect() {
        try {
            channel = BungeeBootStrap.getInstance().getClientBootStrap().
                    connect(this.serverHost, this.serverPort).sync().channel();
            channel.attr(BungeeBootStrap.BUNGEE_CHANNEL_INDEX).set(this);
            // 通知listener创建完毕
            for (BungeeClientListener listener : listeners) {
                listener.onConnect(this);
            }
            logger.info("BungeeClient connect succeed, {}:{}", this.serverHost, this.serverPort);
            return true;
        } catch (Exception e) {
            logger.error("BungeeClient connect failed! {}:{}", this.serverHost, this.serverPort);
            logger.error("exception:", e);
            return false;
        }
    }

    /**
     * 添加 handler
     * @return
     */
    public boolean addIdleHandler() {
        try {
            if (channel == null) {
                return false;
            }
            BungeeClientChannelInitializer channelInitializer = BungeeBootStrap.getInstance().getChannelInitializer();
            channelInitializer.addHeardBeatIdleHandler(this.channel);
        } catch (Exception e) {
            logger.error("BungeeClient addIdleHandler failed! {}:{}", this.serverHost, this.serverPort);
            logger.error("exception:", e);
            return false;
        }
        return true;
    }


    @Override
    public final void finalize() {
        this.shutdown("BungeeClient finalize");
        logger.debug("BungeeClient Finalized and collected by garbage heap");
    }
}
