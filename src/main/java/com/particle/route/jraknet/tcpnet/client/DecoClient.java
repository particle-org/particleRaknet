package com.particle.route.jraknet.tcpnet.client;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNet;
import com.particle.route.jraknet.RakNetException;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.Reliability;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.session.GeminusRakNetPeer;
import com.particle.route.jraknet.session.RakNetState;
import com.particle.route.jraknet.session.TimeoutException;
import com.particle.route.jraknet.task.IClientUpdateTask;
import com.particle.route.jraknet.task.UpdaterManager;
import com.particle.route.jraknet.tcpnet.protocol.DecoPackage;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;
import com.particle.route.jraknet.tcpnet.protocol.connect.*;
import com.particle.route.jraknet.tcpnet.session.LogicServerSession;
import com.particle.route.jraknet.tcpnet.session.PhysicalServeSession;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.UUID;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;


public class DecoClient implements GeminusRakNetPeer, IClientUpdateTask {

    private static final Logger logger = LoggerFactory.getLogger(DecoClient.class);

    private static ConcurrentHashMap<InetSocketAddress, ArrayList<DecoClient>> decoClientPool = new ConcurrentHashMap<InetSocketAddress, ArrayList<DecoClient>>();

    private int maxSessionPerChannel = 4;

    // Client data
    private final long guid;
    private final long pingId;
    private final long timestamp;
    private final ConcurrentLinkedQueue<DecoClientListener> listeners;

    // Session management
    private Channel channel;

    // server data
    private String serverHost;
    private int serverPort;
    private long serverGuid;

    private Bootstrap clientBootstrap;

    /**
     * physical层session
     */
    private volatile PhysicalServeSession physicalServeSession = null;

    /**
     * 客户端更新任务
     */
    private static UpdaterManager updaterManager = new UpdaterManager("DecoClient");

    /**
     * 连接锁
     */
    private Object connectLock = new Object();

    /**
     * 断开连接锁
     */
    private Object disconectLock = new Object();

    /**
     * 连接状态
     */
    public int tcpLoginStatus = LOGIN_NEW;

    public static final int LOGIN_NEW = 0;

    public static final int LOGIN_FAILED = 1;

    public static final int LOGIN_SUCCEED = 2;

    public static final  int LOGIN_FINISHED = 3;

    public static final int LOGIN_CONNECTING = 4;

    /**
     * 连接异常
     */
    private RakNetException connectException = null;

    private boolean isBootstrapConnected = false;

    private Semaphore semaphore;

    private UUID channelUuid = null;

    volatile private int lossTriggerTime = 0;

    protected DecoClient(String serverHost, int serverPort, Bootstrap bootstrap, int maxSessionPerChannel) {
        this.maxSessionPerChannel = maxSessionPerChannel;
        // Set client data
        UUID uuid = UUID.randomUUID();
        channelUuid = uuid;
        this.guid = uuid.getMostSignificantBits();
        this.pingId = uuid.getLeastSignificantBits();
        this.timestamp = System.currentTimeMillis();
        this.listeners = new ConcurrentLinkedQueue<DecoClientListener>();
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.clientBootstrap = bootstrap;
        this.semaphore = new Semaphore(maxSessionPerChannel);
        // Set networking data
        this.initDecoBoostrap();
    }

    /**
     * 获取当前trigger loss的次数
     * @return
     */
    public int getLossTriggerTime() {
        return lossTriggerTime;
    }

    /**
     * 增加trigger loss的次数
     * @param value
     */
    public void addLossTriggerTime(int value) {
        this.lossTriggerTime = this.lossTriggerTime + value;
    }

    /**
     * 设置lossTriggerTime的值
     * @param lossTriggerTime
     */
    public void setLossTriggerTime(int lossTriggerTime) {
        this.lossTriggerTime = lossTriggerTime;
    }

    /**
     * 判断该client是否允许使用
     * @return
     */
    public boolean tryAcquireDecoClient () {
        if (this.channel != null && !this.channel.isActive()) {
            this.disconnectAndShutdown("tryAcquireDecoClient, the channel is not active");
            return false;
        }
        else if (semaphore.tryAcquire()) {
            return true;
        }
        return false;
    }

    /**
     * 释放该client的使用
     */
    public void releaseDecoClient() {
        this.semaphore.release();
    }

    /**
     * 初始化bootstrap
     */
    private boolean initDecoBoostrap() {
        // Initiate bootstrap data
        if (isBootstrapConnected) {
            logger.warn("it is initDecoBoostraped, so skip it!");
            return true;
        }
        try {
            channel = clientBootstrap.connect(this.serverHost, this.serverPort).sync().channel();
            channel.attr(DecoClientManager.DECO_CHANNEL_INDEX).set(this);

            isBootstrapConnected = true;
            logger.debug("initDecoBoostrap succeed");
            return true;
        } catch (Exception e) {
            logger.error("init DecoBootstartp failed!", e);
            return false;
        }
    }

    /**
     * @return the client's networking protocol version.
     */
    protected final int getProtocolVersion() {
        return RakNet.CLIENT_NETWORK_PROTOCOL;
    }

    /**
     * @return the client's globally unique ID.
     */
    protected final long getGloballyUniqueId() {
        return this.guid;
    }

    /**
     * @return the client's ping ID.
     */
    protected final long getPingId() {
        return this.pingId;
    }

    /**
     * @return the client's timestamp.
     */
    protected final long getTimestamp() {
        return (System.currentTimeMillis() - this.timestamp);
    }

    protected String getServerHost() {
        return serverHost;
    }

    protected int getServerPort() {
        return serverPort;
    }

    /**
     * 获取phisicalServerSession
     * @return
     */
    public final PhysicalServeSession getPhysicalServeSession() {
        return this.physicalServeSession;
    }

    /**
     * @return the client's listeners.
     */
    public final DecoClientListener[] getListeners() {
        return listeners.toArray(new DecoClientListener[listeners.size()]);
    }

    /**
     * 添加listener
     * @param listener
     * @return
     */
    public final DecoClient addListener(DecoClientListener listener) {
        // Validate listener
        if (listener == null) {
            throw new NullPointerException("Listener must not be null");
        }
        if (listeners.contains(listener)) {
            throw new IllegalArgumentException("A listener cannot be added twice");
        }

        // Add listener
        listeners.add(listener);
        logger.info("Added listener " + listener.getClass().getName());

        return this;
    }

    /**
     * 去除listener
     * @param listener
     * @return
     */
    public final DecoClient removeListener(DecoClientListener listener) {
        boolean hadListener = listeners.remove(listener);
        if (hadListener == true) {
            logger.info("Removed listener " + listener.getClass().getName());
        } else {
            logger.warn("Attempted to removed unregistered listener " + listener.getClass().getName());
        }
        return this;
    }

    /**
     * 判断physical是否已连接上
     * @return
     */
    public final boolean isConnected() {
        if (physicalServeSession == null) {
            return false;
        }
        return physicalServeSession.getState().equals(RakNetState.CONNECTED);
    }

    /**
     * 判断channel是否open
     * @return
     */
    public final boolean isChannelOpen() {
        if (channel == null) {
            return false;
        }
        return channel.isOpen();
    }

    /**
     * 供外部调用，判断是否包含某个logicSession
     * @param address
     * @return
     */
    public final boolean hasLogicSession(InetSocketAddress address) {
        if (physicalServeSession == null) {
            logger.error(String.format("physicalServeSession is null ,so hasLogicSession (logicAddress[%s]) return false", address));
            return false;
        }
        return physicalServeSession.hasLogicSession(address);
    }

    /**
     * 供外部接口调用，关闭某个session
     * @param logicAddress
     */
    public final void removeLogicSession(InetSocketAddress logicAddress) {
        if (physicalServeSession == null) {
            logger.error(String.format("physicalServeSession is null ,so logicAddress[%s] can not remove", logicAddress));
            return;
        }
        physicalServeSession.removeSession(logicAddress);
    }

    /**
     * 在netty出现异常额时候会被调用
     * @param address
     * @param cause
     */
    protected final void handleHandlerException(InetSocketAddress address, Throwable cause) {
        // Handle exception based on connection state
        if (physicalServeSession != null) {
            this.disconnect(cause.getClass().getName() + ": " + cause.getMessage());
        }

        // Notify API
        logger.warn("Handled exception " + cause.getClass().getName() + " caused by address " + address);
        for (DecoClientListener listener : listeners) {
            listener.onHandlerException(address, cause);
        }
    }

    /**
     * 处理tcp层的消息
     * @param packet
     * @param sender
     */
    public final void handleMessage(RakNetPacket packet, InetSocketAddress sender) {
        short packetId = packet.getId();
        if (packetId != TcpMessageIdentifier.TCP_PONE) {
            logger.debug("decoClient handleMessge:" + packetId);
        }

        // 处理 pong消息
        if (packetId == TcpMessageIdentifier.TCP_PONE) {
            TcpConnectedPong pong = new TcpConnectedPong(packet);
            pong.decode();
            if (this.getPingId() != pong.pongId) {
                logger.error("tcp层的ping/pong通信，其pingId和pongId不一致！");
            }
        } else if (tcpLoginStatus != LOGIN_SUCCEED) {
            // 处理连接信息
            if (packetId == TcpMessageIdentifier.TCP_CONNECT_FAILED) {
                connectException = new RakNetException("TCP发起第一次连接失败，服务端返回，addres信息不对应！");
                // notify 连接锁
                synchronized (connectLock) {
                    tcpLoginStatus = LOGIN_FAILED;
                    connectLock.notify();
                }
            } else if (packetId == TcpMessageIdentifier.TCP_CONNECT_EXISTED) {
                connectException = new RakNetException("TCP发起第一次连接失败，服务端返回，其已存有会话信息！");
                // notify 连接锁
                synchronized (connectLock) {
                    tcpLoginStatus = LOGIN_FAILED;
                    connectLock.notify();
                }
            } else if (packetId == TcpMessageIdentifier.TCP_CONNECT_SUCCEED) {
                TcpOpenConnectionRsp tcpOpenConnectionRsp = new TcpOpenConnectionRsp(packet);
                tcpOpenConnectionRsp.decode();

                if (!tcpOpenConnectionRsp.failed()) {
                    this.serverGuid = tcpOpenConnectionRsp.serverGuid;
                    this.physicalServeSession = new PhysicalServeSession(this, this.timestamp,
                            this.serverGuid, this.channel, sender);
                    // notify 连接锁
                    synchronized (connectLock) {
                        tcpLoginStatus = LOGIN_SUCCEED;
                        connectLock.notify();
                    }
                }
            }
        } else if (packetId == TcpMessageIdentifier.TCP_CONNECTION_UNEXISTED) {
            //服务端在连接handshake过程中，会校验physical session是否存在
            connectException = new RakNetException("服务端返回，physical session不存在!");
        } else if (physicalServeSession != null) {
            // 转发phisical层消息
            physicalServeSession.handlePhysicalPackage(packet, sender);
        } else {
            logger.error(String.format("physicalServeSession 不存在，其来源[%s]", sender));
        }
        return;
    }

    /**
     * 当收到userEvent，首先判断是否需要
     */
    public final void checkUserEventTriggered() {
        if (this.isConnected()) {
            if (this.physicalServeSession.getLogicSessionCollections() == null ||
                    this.physicalServeSession.getLogicSessionCollections().isEmpty()) {
                logger.debug("when checkUserTriggered，the logicSessions is empty, so close the decoClient!");
                this.disconnect("the logicSessions is empty, so close the decoClient!");
                return;
            }
        } else {
            // 延迟一个event关掉channel
            logger.info("when checkUserTriggered，this.isConnected() is false, so close the channel!");
            this.shutdown();
            return;
        }
        this.sendTcpPingMessage();
    }

    /**
     * 发送监听消息， userEventTriggered时候调用
     */
    private final void sendTcpPingMessage() {
        TcpConnectedPing tcpConnectedPing = new TcpConnectedPing();
        tcpConnectedPing.pingId = this.pingId;
        tcpConnectedPing.identifier = this.timestamp;
        tcpConnectedPing.encode();
        this.sendNettyMessage(tcpConnectedPing, new InetSocketAddress(this.serverHost, this.serverPort));
    }

    /**
     * 发送消息，DecoPackage封装
     * @param buf
     * @param address
     */
    public final void sendNettyMessage(ByteBuf buf, InetSocketAddress address) {
        if (channel != null && address != null) {
            ChannelFuture channelFuture = channel.writeAndFlush(new DecoPackage(buf));
            channelFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        logger.error("send netty message failed to " + address);
                    }
                }
            });
        }
    }

    /**
     * 发送消息，DecoPackage封装
     * @param packet
     * @param address
     */
    public final void sendNettyMessage(Packet packet, InetSocketAddress address) {
        this.sendNettyMessage(packet.buffer(), address);
    }

    /**
     * 发送消息，DecoPackage封装
     * @param packetId
     * @param address
     */
    public final void sendNettyMessage(int packetId, InetSocketAddress address) {
        this.sendNettyMessage(new RakNetPacket(packetId), address);
    }

    /**
     * 对外接口，发送消息
     * @param guid
     *            玩家真正的guid
     * @param reliability
     *            the reliability of the packet.
     * @param channel
     *            the channel to send the packet on.
     * @param packet
     *            the packet to send.
     * @return
     */
    @Override
    public final EncapsulatedPacket sendMessage(long guid, Reliability reliability, int channel, Packet packet) {
        if (this.isConnected()) {
            LogicServerSession logicServerSession = this.physicalServeSession.getLogicSession(guid);
            if (logicServerSession != null && logicServerSession.getState() == RakNetState.CONNECTED) {
                return logicServerSession.sendMessage(reliability, channel, packet);
            } else {
                return null;
            }
        }
        return null;
    }

    /**
     * 创建logicSession，如果先前没有tcp连接，可以强制新建
     * @param logicAddress
     *          逻辑地址
     * @param logicGuid
     *          逻辑guid
     * @param forceConnect
     *          不存在连接时，是否需要强制建立连接
     * @return
     */
    public final boolean connectLogicSession(InetSocketAddress logicAddress, long logicGuid, boolean forceConnect) {
        InetSocketAddress tcpAddress = new InetSocketAddress(this.serverHost, this.serverPort);
        if (!this.isConnected()) {
            if (forceConnect && tcpAddress != null) {
                if (!this.connect(tcpAddress)) {
                    return false;
                }
            } else {
                logger.error("connectLogicSession时候，尚未进行tcp连接！");
                return false;
            }
        }
        if (this.isConnected()) {
            if (logicAddress == null) {
                logger.error("connectLogicSession时候，logic参数不合法");
                return false;
            }
            return this.physicalServeSession.createLogicSession(logicAddress, logicGuid);
        }
        return false;
    }

    /**
     * 创建logicSession，如果先前没有tcp连接，可以强制新建
     * 阻塞连接操作，会等待服务端返回
     * @param address
     * @throws RakNetException
     */
    private final boolean connect(InetSocketAddress address) {
        if (!isBootstrapConnected) {
            // 若没有初始化tcp连接，初始化
            if (!this.initDecoBoostrap()) {
                return false;
            }
        }
        // Make sure we have a listener
        if (listeners.size() <= 0) {
            logger.warn("Client has no listeners");
        }

        // Reset client data
        if (this.isConnected()) {
            logger.warn(String.format("tcp server address[%s] has connected!", address));
            return true;
        }

        int retryCounts = 10;
        // 第一次连接
        synchronized (connectLock) {
            // 循环等待服务端返回信息
            while (tcpLoginStatus != LOGIN_SUCCEED && retryCounts-- > 0) {
                if (tcpLoginStatus == LOGIN_FAILED) {
                    logger.warn("第一次tcp连接，返回失败，重新发送连接请求");
                }
                TcpOpenConnectionReq tcpOpenConnectionReq = new TcpOpenConnectionReq();
                tcpOpenConnectionReq.clientGuid = this.guid;
                tcpOpenConnectionReq.timestamp = this.timestamp;
                tcpOpenConnectionReq.encode();
                if (tcpOpenConnectionReq.failed()) {
                    logger.error("发起第一次连接，编码失败！");
                    return false;
                } else {
                    this.sendNettyMessage(tcpOpenConnectionReq, address);
                }

                try {
                    //最多等待两秒
                    connectLock.wait(2000);
                } catch (InterruptedException ie) {
                    logger.error("第一次连接失败，异常：", ie);
                    return false;
                }
            }
        }

        if (tcpLoginStatus == LOGIN_SUCCEED) {
            TcpOpenConnectionHandShake tcpOpenConnectionHandShake = new TcpOpenConnectionHandShake();
            tcpOpenConnectionHandShake.timestamp = this.timestamp;
            tcpOpenConnectionHandShake.clientGuid = this.guid;
            tcpOpenConnectionHandShake.encode();
            if (tcpOpenConnectionHandShake.failed()) {
                logger.error("发起第二次连接，编码失败！");
                return false;
            } else {
                this.sendNettyMessage(tcpOpenConnectionHandShake, address);
            }
        }

        if (tcpLoginStatus != LOGIN_SUCCEED) {
            this.disconnect("connect failed!");
            return false;
        } else {
            // 设置physicalServeSession可用
            // 发送消息时候，请先调用isConnected()方法，确认session是否可用
            if (physicalServeSession != null) {
                physicalServeSession.setState(RakNetState.CONNECTED);
            }
            // 通知listener创建完毕
            for (DecoClientListener listener : listeners) {
                listener.onPhysicalConnect(physicalServeSession);
            }
        }
        try {
            this.initConnection();
        } catch (RakNetException rne) {
            logger.error("initConnection failed!", rne);
            return false;
        }
        return true;
    }

    /**
     * connect
     * @param address
     * @param port
     */
    private final void connect(InetAddress address, int port) {
        this.connect(new InetSocketAddress(address, port));
    }

    /**
     * connect
     * @param address
     * @param port
     * @throws UnknownHostException
     */
    private final void connect(String address, int port) throws UnknownHostException {
        this.connect(InetAddress.getByName(address), port);
    }

    /**
     * 代理专用接口
     * @throws RakNetException
     */
    private final void initConnection() throws RakNetException {
        if (physicalServeSession != null) {
            logger.debug("Initiated connected with server");

            //这里由统一的Client更新线程更新Client，减少线程争用
            DecoClient.updaterManager.addTask(this);
        } else {
            throw new RakNetException("Attempted to initiate connection without session");
        }
    }

    /**
     * 断开连接
     * 清除physical层的session，清除logic层的session
     * 发送断开指令
     * @param reason
     */
    private final void disconnect(String reason) {
        synchronized (disconectLock) {
            if (physicalServeSession != null) {
                try {
                    // Disconnect session
                    physicalServeSession.removeAllLogicSession(reason);
                } catch (TimeoutException exception) {
                    logger.info("Close timeout session");
                }
                if (physicalServeSession == null) {
                    return;
                }

                // 通知服务端，tcp连接断开
                this.sendNettyMessage(new RakNetPacket(TcpMessageIdentifier.TCP_DISCONNECT), physicalServeSession.getAddress());

                // Notify API
                logger.info("Disconnected from server with address " + physicalServeSession.getAddress() + " with reason \"" + reason
                        + "\"");
                for (DecoClientListener listener : listeners) {
                    listener.onPhysicalDisconnect(physicalServeSession, reason);
                }

                // Destroy session
                tcpLoginStatus = LOGIN_NEW;
                this.physicalServeSession = null;
            } else {
                logger.warn(
                        "Attempted to disconnect from server even though it was not connected to as server in the first place");
            }
        }

    }

    /**
     * 断开连接
     */
    private final void disconnect() {
        this.disconnect("Disconnected");
    }

    /**
     * 关闭tcp channel
     */
    private final void shutdown() {

        if (this.channel != null) {
            // 向管理中心，反注册处理decoClient
            logger.debug("remove channel attribute decoClient!");
            this.channel.attr(DecoClientManager.DECO_CHANNEL_INDEX).set(null);
        }

        // 在shutdown的时候，统一清空信号量
        if (semaphore != null) {
            int limit = semaphore.availablePermits();
            if (limit < maxSessionPerChannel) {
                semaphore.release(maxSessionPerChannel - limit);
            }
        }

        // Close channel
        if (this.channel != null) {
            this.channel.close();
            this.channel = null;
            for (DecoClientListener listener : listeners) {
                listener.onClientShutdown();
            }
            // Notify API
            logger.debug("Shutdown client");
        } else {
            logger.warn("Client attempted to shutdown after it was already shutdown");
        }
        isBootstrapConnected = false;
    }

    /**
     * 关闭physical的处理，同时关闭连接
     * @param reason
     */
    public final void disconnectAndShutdown(String reason) {
        // Disconnect from server
        if (this.isConnected()) {
            this.disconnect(reason);
        }
        this.shutdown();
    }

    /**
     * 关闭连接
     */
    public final void disconnectAndShutdown() {
        this.disconnectAndShutdown("Client shutdown");
    }

    @Override
    public final void finalize() {
        this.shutdown();
        logger.debug("DecoClient Finalized and collected by garbage heap");
    }

    @Override
    public void update() {
        try {
            if (this.physicalServeSession != null && this.physicalServeSession.getState() == RakNetState.CONNECTED) {
                if (this.physicalServeSession.getLogicSessionCount() > 0) {
                    for (LogicServerSession logicServerSession : this.physicalServeSession.getLogicSessionCollections()) {
                        if (logicServerSession != null && logicServerSession.getState() == RakNetState.CONNECTED) {
                            logicServerSession.update();
                        }
                    }
                }
            }
        } catch (TimeoutException exception) {
            this.disconnect("Time Out");
        }
    }

    @Override
    public boolean isStopping() {
        return !this.isChannelOpen();
    }

    @Override
    public long getUUID() {
        return this.getGloballyUniqueId();
    }

}
