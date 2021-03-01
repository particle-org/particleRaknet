package com.particle.route.jraknet.tcpnet.client;

import com.particle.route.jraknet.RakNetPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class DecoClientManager {

    private static final Logger logger = LoggerFactory.getLogger(DecoClientManager.class);

    public static final AttributeKey<DecoClient> DECO_CHANNEL_INDEX = AttributeKey.valueOf("deco_channel_index");

    // 用于分配decoClient的池
    private static ConcurrentHashMap<InetSocketAddress, ArrayList<DecoClient>> decoClientPool = new ConcurrentHashMap<InetSocketAddress, ArrayList<DecoClient>>();

    // 当处理消息来临时，分配哪个DecoClient给处理
    // ip地址用本地ip和端口来区分
    private static ConcurrentHashMap<InetSocketAddress, DecoClient> handleDecoClient = new ConcurrentHashMap<InetSocketAddress, DecoClient>();

    // Networking data
    private Bootstrap clientBootStrap;

    private EventLoopGroup groupEventLoopGroup;

    private DecoClientHandler decoClientHandler = null;

    private static DecoClientManager sDecoClientManager = new DecoClientManager();

    private static final int MAX_LOGIC_SESSION_PER_CHANNEL = 5;

    /**
     * 获取单例
     * @return
     */
    public synchronized static DecoClientManager getSingleInstance() {
        if (sDecoClientManager == null) {
            sDecoClientManager = new DecoClientManager();
        }
        return sDecoClientManager;
    }

    /**
     * 统一获取DecoClient的方法
     * 暂时没有考虑到回收池
     * @param serverHost
     * @param serverPort
     * @return
     */
    public DecoClient getDecoClientFromPool(String serverHost, int serverPort, DecoClientListener listener, int maxSessionPerChannel) {
        synchronized (decoClientPool) {
            if (serverHost == null || serverHost.isEmpty()) {
                logger.error(String.format("IP和端口信息不合法，ip[%s]，端口[%s]", serverHost, serverPort));
                return null;
            }
            InetSocketAddress address = new InetSocketAddress(serverHost, serverPort);
            ArrayList<DecoClient> decoClientQueue = decoClientPool.get(address);
            if (decoClientQueue == null) {
                decoClientQueue = new ArrayList<DecoClient>();
                decoClientPool.put(address, decoClientQueue);
            }
            if (decoClientQueue.isEmpty()) {
                DecoClient decoClient = new DecoClient(serverHost, serverPort, clientBootStrap, maxSessionPerChannel);
                if (listener != null) {
                    decoClient.addListener(listener);
                }
                decoClientQueue.add(decoClient);
                decoClient.tryAcquireDecoClient();
                return decoClient;
            } else {
                for (DecoClient client : decoClientQueue) {
                    if (client == null) {
                        continue;
                    }
                    if (client.tryAcquireDecoClient()) {
                        return client;
                    }
                }
                DecoClient decoClient = new DecoClient(serverHost, serverPort, clientBootStrap, maxSessionPerChannel);
                if (listener != null) {
                    decoClient.addListener(listener);
                }
                decoClientQueue.add(decoClient);
                decoClient.tryAcquireDecoClient();
                return decoClient;
            }
        }
    }

    private DecoClientManager () {
        this.clientBootStrap = new Bootstrap();
        this.groupEventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        this.clientBootStrap.group(groupEventLoopGroup);
        this.clientBootStrap.channel(NioSocketChannel.class);
        this.decoClientHandler = new DecoClientHandler(this);
        this.clientBootStrap.handler(new DecoClientChannelInitializer(decoClientHandler));
    }

    /**
     * 注册handleClient
     * @param sourceAddress
     * @param decoClient
     */
    protected void registerHandlerDecoClient(InetSocketAddress sourceAddress, DecoClient decoClient) {
        synchronized (handleDecoClient) {
            if (sourceAddress != null && decoClient != null) {
                if (!handleDecoClient.containsKey(sourceAddress)) {
                    handleDecoClient.put(sourceAddress, decoClient);
                }
            } else {
                logger.error(String.format("regiserHandlerDecoClient， 参数非法"));
            }
        }

    }

    /**
     * 反注册handleClient
     * @param sourceAddress
     */
    protected void unregisterHandlerDecoClient(InetSocketAddress sourceAddress) {
        synchronized (handleDecoClient) {
            if (sourceAddress != null) {
                if (handleDecoClient.containsKey(sourceAddress)) {
                    handleDecoClient.remove(sourceAddress);
                }
            }
        }
    }

    /**
     * 处理具体的消息
     * @param packet
     * @param sender
     * @param localAddress
     */
    protected final void handleMessage(RakNetPacket packet, InetSocketAddress sender, InetSocketAddress localAddress) {
        if (localAddress == null) {
            logger.error("handleMessage， localAddress is null!");
            return;
        }
        DecoClient decoClient = handleDecoClient.get(localAddress);
        if (decoClient == null) {
            logger.error(String.format("handleMessage， 尚未创建该地址[%s]的decoClient!", localAddress));
            return;
        }
        decoClient.handleMessage(packet, sender);
    }

    /**
     * 断开连接
     * @param localAddress
     * @param reason
     */
    protected final void disconnectAndShutdown(InetSocketAddress localAddress, String reason) {
        if (localAddress == null) {
            logger.error("disconnectAndShutdown， localAddress is null!");
            return;
        }
        DecoClient decoClient = handleDecoClient.get(localAddress);
        if (decoClient == null) {
            logger.error(String.format("disconnectAndShutdown， 尚未创建该地址[%s]的decoClient!", localAddress));
            return;
        }
        decoClient.disconnectAndShutdown(reason);
    }

    /**
     * idelEvent心跳检测
     * @param localAddress
     */
    protected final void checkUserEventTriggered(InetSocketAddress localAddress) {
        if (localAddress == null) {
            logger.error("checkUserEventTriggered， localAddress is null!");
            return;
        }
        DecoClient decoClient = handleDecoClient.get(localAddress);
        if (decoClient == null) {
            logger.error(String.format("checkUserEventTriggered， 尚未创建该地址[%s]的decoClient!", localAddress));
            return;
        }
        decoClient.checkUserEventTriggered();
    }

    /**
     * 处理异常
     * @param localAddress
     * @param sender
     * @param cause
     */
    protected final void handleHandlerException(InetSocketAddress localAddress, InetSocketAddress sender, Throwable cause) {
        if (localAddress == null) {
            logger.error("handleHandlerException， localAddress is null!");
            return;
        }
        DecoClient decoClient = handleDecoClient.get(localAddress);
        if (decoClient == null) {
            logger.error(String.format("handleHandlerException， 尚未创建该地址[%s]的decoClient!", localAddress));
            return;
        }
        decoClient.handleHandlerException(sender, cause);
    }

    /**
     * 可以调用decoClient的listener的handleNettyMessage方法
     * @param buf
     * @param address
     * @param localAddress
     */
    protected final void handleNettyMessage(ByteBuf buf, InetSocketAddress address, InetSocketAddress localAddress) {
        if (localAddress == null) {
            logger.error("handleNettyMessage， localAddress is null!");
            return;
        }
        DecoClient decoClient = handleDecoClient.get(localAddress);
        if (decoClient == null) {
            logger.error(String.format("handleNettyMessage， 尚未创建该地址[%s]的decoClient!", localAddress));
            return;
        }
        for (DecoClientListener listener : decoClient.getListeners()) {
            listener.handleNettyMessage(buf, address);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (!this.groupEventLoopGroup.isShutdown()) {
            this.groupEventLoopGroup.shutdownGracefully();
        }

        super.finalize();
    }
}
