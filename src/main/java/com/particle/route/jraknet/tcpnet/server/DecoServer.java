package com.particle.route.jraknet.tcpnet.server;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNet;
import com.particle.route.jraknet.RakNetException;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.identifier.Identifier;
import com.particle.route.jraknet.protocol.Reliability;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.server.BlockedAddress;
import com.particle.route.jraknet.session.GeminusRakNetPeer;
import com.particle.route.jraknet.session.RakNetState;
import com.particle.route.jraknet.tcpnet.protocol.DecoPackage;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;
import com.particle.route.jraknet.tcpnet.session.LogicClientSession;
import com.particle.route.jraknet.tcpnet.session.PhysicalClientSession;
import com.particle.route.jraknet.util.RakNetUtils;
import com.particle.route.jraknet.tcpnet.protocol.connect.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DecoServer implements GeminusRakNetPeer {

    private static final Logger logger = LoggerFactory.getLogger(DecoServer.class);

    // Server data
    private final long guid;
    private final long pongId;
    private final long timestamp;
    private final String address;
    private final int port;
    private final int maxConnections;
    private final int maximumTransferUnit;
    private boolean broadcastingEnabled;
    private Identifier identifier;
    private final ConcurrentLinkedQueue<DecoServerListener> listeners;
    private Thread serverThread;

    /**
     * bossGroup size
     */
    private static final int BIZ_GROUP_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    /**
     * workerGroup size
     */
    private static final int BIZ_THREAD_SIZE = 4;

    private final ServerBootstrap bootstrap;
    private final EventLoopGroup bossGroup;

    private DecoServerHandler decoServerHandler;

    private volatile boolean running;

    private final ConcurrentHashMap<InetSocketAddress, PhysicalClientSession> physicalSessions;

    private final ConcurrentHashMap<InetAddress, BlockedAddress> blocked;

    public DecoServer(String address, int port, int maxConnections, int maximumTransferUnit, Identifier identifier) {
        // Set server data
        UUID uuid = UUID.randomUUID();
        this.guid = uuid.getMostSignificantBits();
        this.pongId = uuid.getLeastSignificantBits();
        this.timestamp = System.currentTimeMillis();
        this.address = address;
        this.port = port;
        this.maxConnections = maxConnections;
        this.maximumTransferUnit = maximumTransferUnit;
        this.broadcastingEnabled = true;
        this.identifier = identifier;
        this.listeners = new ConcurrentLinkedQueue<DecoServerListener>();

        // Initiate bootstrap data
        this.bootstrap = new ServerBootstrap();
        this.bossGroup = new NioEventLoopGroup(BIZ_GROUP_SIZE);

        this.decoServerHandler = new DecoServerHandler(this);

        // Create session map
        this.physicalSessions = new ConcurrentHashMap<InetSocketAddress, PhysicalClientSession>();
        this.blocked = new ConcurrentHashMap<InetAddress, BlockedAddress>();
    }

    public DecoServer(String address, int port, int maxConnections, int maximumTransferUnit) {
        this(address, port, maxConnections, maximumTransferUnit, null);
    }

    public DecoServer(String address, int port, int maxConnections) {
        this(address, port, maxConnections, RakNetUtils.getMaximumTransferUnit());
    }

    public DecoServer(String address, int port, int maxConnections, Identifier identifier) {
        this(address, port, maxConnections);
        this.identifier = identifier;
    }

    public final int getProtocolVersion() {
        return RakNet.SERVER_NETWORK_PROTOCOL;
    }

    public final long getGloballyUniqueId() {
        return this.guid;
    }

    public final long getTimestamp() {
        return (System.currentTimeMillis() - this.timestamp);
    }

    public final int getPort() {
        return this.port;
    }

    public final int getMaxConnections() {
        return this.maxConnections;
    }

    public final int getMaximumTransferUnit() {
        return this.maximumTransferUnit;
    }

    public final void setBroadcastingEnabled(boolean enabled) {
        this.broadcastingEnabled = enabled;
        logger.info((enabled ? "Enabled" : "Disabled") + " broadcasting");
    }

    public final boolean isBroadcastingEnabled() {
        return this.broadcastingEnabled;
    }

    public final Identifier getIdentifier() {
        return this.identifier;
    }

    public final void setIdentifier(Identifier identifier) {
        this.identifier = identifier;
        logger.info("Set identifier to \"" + identifier.build() + "\"");
    }

    public final Thread getThread() {
        return this.serverThread;
    }

    public final DecoServerListener[] getListeners() {
        return listeners.toArray(new DecoServerListener[listeners.size()]);
    }

    public final DecoServer addListener(DecoServerListener decoServerListener) {
        // Validate listener
        if (decoServerListener == null) {
            throw new NullPointerException("Listener must not be null");
        }
        if (listeners.contains(decoServerListener)) {
            throw new IllegalArgumentException("A listener cannot be added twice");
        }

        // Add listener
        listeners.add(decoServerListener);
        logger.debug("Added listener {}", decoServerListener.getClass().getName());

        return this;
    }

    public final DecoServer removeListener(DecoServerListener listener) {
        boolean hadListener = listeners.remove(listener);
        if (hadListener == true) {
            logger.info("Removed listener " + listener.getClass().getName());
        } else {
            logger.warn("Attempted to removed unregistered listener " + listener.getClass().getName());
        }
        return this;
    }

    /**
     * 获取所有业务的ip信息
     * @return
     */
    private final List<InetSocketAddress> getLogicAddres() {
        List<InetSocketAddress> totalLogicAddress = new ArrayList<>();
        Collection<PhysicalClientSession> pSessions =  physicalSessions.values();
        if (pSessions != null && !pSessions.isEmpty()) {
            for (PhysicalClientSession pSession : pSessions) {
                if (pSession == null) {
                    continue;
                }
                totalLogicAddress.addAll(pSession.getLogicSocketAddres());
            }
        }
        return totalLogicAddress;
    }

    /**
     * 获取业务层的session列表
     * @return
     */
    public final List<LogicClientSession> getLogicSessions() {
        if (this.physicalSessions == null || this.physicalSessions.isEmpty()) {
            return null;
        }
        List<LogicClientSession> totalLogicClientSessions = new ArrayList<LogicClientSession>();
        Collection<PhysicalClientSession> pSessions =  this.physicalSessions.values();
        if (pSessions != null && !pSessions.isEmpty()) {
            for (PhysicalClientSession pSession : pSessions) {
                if (pSession == null) {
                    continue;
                }
                totalLogicClientSessions.addAll(pSession.getLogicSessionCollections());
            }
        }
        return totalLogicClientSessions;
    }

    /**
     * 获取tcp层通信的session列表
     * @return
     */
    public final PhysicalClientSession[] getPhysicalSessions() {
        return physicalSessions.values().toArray(new PhysicalClientSession[physicalSessions.size()]);
    }

    /**
     * 获取业务层的session总数
     * @return
     */
    public final int getLogicSessionCount() {
        int totalSize = 0;
        Collection<PhysicalClientSession> pSessions =  physicalSessions.values();
        if (pSessions != null && !pSessions.isEmpty()) {
            for (PhysicalClientSession pSession : pSessions) {
                if (pSession == null) {
                    continue;
                }
                totalSize += pSession.getLogicSessionCount();
            }
        }
        return totalSize;
    }

    /**
     * 获取tcp层通信的session总数
     * @return
     */
    public final int getPhysicalCounts() {
        return physicalSessions.size();
    }

    /**
     * 业务层是否包含某ip
     * @param address
     * @return
     */
    public final boolean hasLogicSession(InetSocketAddress address) {
        Collection<PhysicalClientSession> pSessions =  physicalSessions.values();
        if (pSessions != null && !pSessions.isEmpty()) {
            for (PhysicalClientSession pSession : pSessions) {
                if (pSession == null) {
                    continue;
                }
                if (pSession.hasLogicSession(address)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * tcp通信层是否包含某ip
     * @param address
     * @return
     */
    public final boolean hasPhysicalSession(InetSocketAddress address) {
        return physicalSessions.containsKey(address);
    }

    /**
     * 业务层是否包含某guid
     * @param guid
     * @return
     */
    public final boolean hasLogicSession(long guid) {
        Collection<PhysicalClientSession> pSessions =  physicalSessions.values();
        if (pSessions != null && !pSessions.isEmpty()) {
            for (PhysicalClientSession pSession : pSessions) {
                if (pSession == null) {
                    continue;
                }
                if (pSession.hasLogicSession(guid)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * tcp通信层是否包含某guid
     * @param guid
     * @return
     */
    public final boolean hasPhysicalSession(long guid) {
        for (PhysicalClientSession session : physicalSessions.values()) {
            if (session.getGloballyUniqueId() == guid) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据ip获取业务层session
     * @param address
     * @return
     */
    public final LogicClientSession getLogicSession(InetSocketAddress address) {
        Collection<PhysicalClientSession> pSessions =  physicalSessions.values();
        if (pSessions != null && !pSessions.isEmpty()) {
            for (PhysicalClientSession pSession : pSessions) {
                if (pSession == null) {
                    continue;
                }
                LogicClientSession lcSession = pSession.getLogicSession(address);
                if (lcSession != null) {
                    return lcSession;
                }
            }
        }
        return null;
    }

    /**
     * 根据ip获取tcp通信层session
     * @param address
     * @return
     */
    public final PhysicalClientSession getPhysicalSession(InetSocketAddress address) {
        return physicalSessions.get(address);
    }

    /**
     * 根据guid获取业务层session
     * @param guid
     * @return
     */
    public final LogicClientSession getLogicSession(long guid) {
        Collection<PhysicalClientSession> pSessions =  physicalSessions.values();
        if (pSessions != null && !pSessions.isEmpty()) {
            for (PhysicalClientSession pSession : pSessions) {
                if (pSession == null) {
                    continue;
                }
                LogicClientSession lcSession = pSession.getLogicSession(guid);
                if (lcSession != null) {
                    return lcSession;
                }
            }
        }
        return null;
    }

    /**
     * 根据guid获取tcp通信层session
     * @param guid
     * @return
     */
    public final PhysicalClientSession getPhysicalSession(long guid) {
        for (PhysicalClientSession session : physicalSessions.values()) {
            if (session.getGloballyUniqueId() == guid) {
                return session;
            }
        }
        return null;
    }

    @Override
    public final EncapsulatedPacket sendMessage(long guid, Reliability reliability, int channel, Packet packet) {
        if (this.hasLogicSession(guid)) {
            return this.getLogicSession(guid).sendMessage(reliability, channel, packet);
        } else {
            throw new IllegalArgumentException("No such session with GUID");
        }
    }

    /**
     * 对外接口，去除logic session
     */
    public final void removeSession(InetSocketAddress address, String reason) {
        LogicClientSession logicClientSession = this.getLogicSession(address);
        if (logicClientSession != null) {
            PhysicalClientSession physicalSession = (PhysicalClientSession) logicClientSession.getPhysicalSession();
            physicalSession.removeSession(address, reason);
        }
    }

    /**
     * 对外接口，去除logic session
     * @param address
     */
    public final void removeSession(InetSocketAddress address) {
        this.removeSession(address, "Disconnected from server");
    }

    /**
     * 对外接口，去除logic session
     * @param session
     * @param reason
     */
    public final void removeSession(LogicClientSession session, String reason) {
        this.removeSession(session.getAddress(), reason);
    }

    /**
     * 对外接口，去除logic session
     * @param session
     */
    public final void removeSession(LogicClientSession session) {
        this.removeSession(session, "Disconnected from server");
    }

    /**
     * 去除某个physical session，会同时把他的logicSession去除掉
     * @param address
     * @param reason
     */
    public final boolean removePhysicalSession(InetSocketAddress address, String reason) {
        PhysicalClientSession session = this.getPhysicalSession(address);
        if (session != null) {
            session.removeAllLogicSession(reason);
            session.setState(RakNetState.DISCONNECTED);
            physicalSessions.remove(address);
            if (session.getChannel().isActive()) {
                session.getChannel().close();
            }
            return true;
        }

        return false;
    }

    /**
     * 去除某个physical session，会同时把他的logicSession去除掉
     * @param address
     */
    public final void removePhysicalSession(InetSocketAddress address) {
        this.removePhysicalSession(address, "Physical session Disconnected from server");
    }

    /**
     * 去除所有的physical的session，会同时把logicSession去除掉
     * @param reason
     */
    public final void removeAllPhysicalSession(String reason) {
        for (InetSocketAddress address : physicalSessions.keySet()) {
            this.removePhysicalSession(address, reason);
        }
        physicalSessions.clear();
    }

    /**
     * 去除所有的physical的session，会同时把logicSession去除掉
     */
    public final void removaAllPhysicalSession() {
        for (InetSocketAddress address : physicalSessions.keySet()) {
            this.removePhysicalSession(address, "all hysical session Disconnected from server");
        }
        physicalSessions.clear();
    }

    /**
     * 获取某个addres的block状态
     * @param address
     * @return
     */
    public final BlockedAddress getBlockedStatus(InetAddress address) {
        BlockedAddress status = blocked.get(address);
        return status;
    }

    /**
     * ban掉某个业务logic address
     * @param address
     * @param reason
     * @param time
     */
    public final void blockAddress(InetAddress address, String reason, long time) {
        for (InetSocketAddress clientAddress : this.getLogicAddres()) {
            if (clientAddress.getAddress().equals(address)) {
                this.removeSession(clientAddress, reason);
            }
        }
        blocked.put(address, new BlockedAddress(System.currentTimeMillis(), time));
        for (DecoServerListener listener : listeners) {
            listener.onAddressBlocked(address, reason, time);
        }
        logger.info("Blocked address " + address + " due to \"" + reason + "\" for " + time + " milliseconds");
    }

    /**
     * ban掉某个业务logic address
     * @param address
     * @param time
     */
    public final void blockAddress(InetAddress address, long time) {
        this.blockAddress(address, "Blocked", time);
    }

    /**
     * 解ban某个业务logic address
     * @param address
     */
    public final void unblockAddress(InetAddress address) {
        blocked.remove(address);
        for (DecoServerListener listener : listeners) {
            listener.onAddressUnblocked(address);
        }
    }

    /**
     * 判断某个业务logic address是否被ban掉
     * @param address
     * @return
     */
    public final boolean addressBlocked(InetAddress address) {
        return blocked.containsKey(address);
    }

    /**
     * 当netty出现异常的时候，会去除掉这个physicalAddress的下的所有logicSession
     * 同时，会关闭channel
     * @param address
     * @param cause
     */
    protected final void handleHandlerException(InetSocketAddress address, Throwable cause) {
        // Remove session that caused the error
        this.removePhysicalSession(address, cause.getClass().getName());

        // Notify API
        logger.warn("Handled exception " + cause.getClass().getName() + " caused by address " + address);
        for (DecoServerListener listener : listeners) {
            listener.onHandlerException(address, cause);
        }
    }

    /**
     * 处理tcp连接相关的消息，并转发其他消息至physical层
     * @param packet
     * @param sender
     * @param channelHandlerContext
     */
    protected final void handleMessage(RakNetPacket packet, InetSocketAddress sender, ChannelHandlerContext channelHandlerContext) throws Exception{
        short packetId = packet.getId();
        if (packetId != TcpMessageIdentifier.TCP_PING) {
            logger.debug(String.format("DecoServer handleMessge, packgeId[%s]", packetId));
        }
        if (packetId == TcpMessageIdentifier.TCP_PING) {
            // 客户端发送tcp的ping消息，回pong消息，建议100毫秒内没有数据包发送，就会发送一次
            // 服务端监控到300ms内没有数据包过来，会主动断开，并清除附属logicSession
            TcpConnectedPing tcpConnectedPing = new TcpConnectedPing(packet);
            tcpConnectedPing.decode();
            TcpConnectedPong pong = new TcpConnectedPong();
            pong.identifier = tcpConnectedPing.identifier;
            pong.pongId = tcpConnectedPing.pingId;
            pong.encode();
            this.sendNettyMessage(channelHandlerContext.channel(), pong, sender);
            return;
        } else if (packetId == TcpMessageIdentifier.TCP_CONNECT) {
            //建立tcp连接，如果建立成功，会创建physicalSession，此时状态为disconnected
            TcpOpenConnectionReq tcpOpenConnectionReq = new TcpOpenConnectionReq(packet);
            tcpOpenConnectionReq.decode();
            if (tcpOpenConnectionReq.failed()) {
                return;
            }
            if (tcpOpenConnectionReq.magic) {
                RakNetPacket errorPackage = null;
                if (this.hasPhysicalSession(sender)) {
                    errorPackage = new RakNetPacket(TcpMessageIdentifier.TCP_CONNECT_EXISTED);
                }
                if (errorPackage != null) {
                    this.sendNettyMessage(channelHandlerContext.channel(), errorPackage, sender);
                    return;
                }
                TcpOpenConnectionRsp tcpOpenConnectionRsp = new TcpOpenConnectionRsp();
                tcpOpenConnectionRsp.serverGuid = this.guid;
                tcpOpenConnectionRsp.clientAddress = sender;
                tcpOpenConnectionRsp.encode();
                if (!tcpOpenConnectionRsp.failed()) {
                    // 初始化physicalSession
                    PhysicalClientSession physicalClientSession = new PhysicalClientSession(this, System.currentTimeMillis(), guid, channelHandlerContext.channel(), sender);
                    physicalSessions.put(sender, physicalClientSession);
                    // Send response, we are ready for connected
                    this.sendNettyMessage(channelHandlerContext.channel(), tcpOpenConnectionRsp, sender);
                    return;
                }
            }
        } else if (packetId == TcpMessageIdentifier.TCP_CONNECT_HANDSHAKE) {
            TcpOpenConnectionHandShake tcpOpenConnectionHandShake = new TcpOpenConnectionHandShake(packet);
            tcpOpenConnectionHandShake.decode();
            if (tcpOpenConnectionHandShake.failed()) {
                return;
            }
            if (tcpOpenConnectionHandShake.magic) {
                RakNetPacket errorPackage = null;
                PhysicalClientSession physicalClientSession = this.getPhysicalSession(sender);
                if (physicalClientSession == null) {
                    errorPackage = new RakNetPacket(TcpMessageIdentifier.TCP_CONNECTION_UNEXISTED);
                }
                if (errorPackage != null) {
                    this.sendNettyMessage(channelHandlerContext.channel(), errorPackage, sender);
                    return;
                }
                physicalClientSession.setState(RakNetState.CONNECTED);
            }
        }
        else if (packetId == TcpMessageIdentifier.TCP_DISCONNECT){
            // 关掉tcp连接指令，会同时把其附属的logicSession关掉
            this.removePhysicalSession(sender, "receieve client TCP_DISCONNECT!");
        } else {
            PhysicalClientSession physicalClientSession = this.getPhysicalSession(sender);
            if (physicalClientSession != null) {
                physicalClientSession.handlePhysicalPackage(packet, sender, channelHandlerContext);
            } else {
                logger.error(String.format("physicalClientSession 不存在，其来源[%s]", sender));
            }
        }
    }

    /**
     * 发送消息，DecoPackage封装
     * @param channel
     * @param buf
     * @param address
     */
    public final void sendNettyMessage(Channel channel, ByteBuf buf, InetSocketAddress address) {
        if (channel != null) {
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
     * @param channel
     * @param packet
     * @param address
     */
    public final void sendNettyMessage(Channel channel,  Packet packet, InetSocketAddress address) {
        this.sendNettyMessage(channel, packet.buffer(), address);
    }

    /**
     * 发送消息，DecoPackage封装
     * @param channel
     * @param packetId
     * @param address
     */
    public final void sendNettyMessage(Channel channel, int packetId, InetSocketAddress address) {
        this.sendNettyMessage(channel, new RakNetPacket(packetId), address);
    }

    /**
     * server开始监听
     * @throws RakNetException
     */
    public final void start() throws RakNetException {
        // Make sure we have a listener
        if (listeners.size() <= 0) {
            logger.warn("Server has no listeners");
        }

        // Create bootstrap and bind the channel
        try {
            bootstrap.channel(NioServerSocketChannel.class).group(bossGroup);
            bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);

            bootstrap.childHandler(new DecoServerChannelInitializer(decoServerHandler));
            // 绑定接口，同步等待成功
            logger.info("start tcp server at port[" + port + "].");
            ChannelFuture future = bootstrap.bind(port).sync();

            ChannelFuture channelFuture = future.addListener(new ChannelFutureListener() {
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        logger.info("Server have success bind to " + port);
                    } else {
                        logger.error("Server fail bind to " + port);
                        throw new RuntimeException("Server start fail !", future.cause());
                    }
                }
            });
            this.running = true;
            logger.info("Created and bound bootstrap");

            // Notify API
            logger.info("Started server");
            for (DecoServerListener listener : listeners) {
                listener.onServerStart();
            }

            // Update system
            while (this.running) {
                try {
                    List<LogicClientSession> logicClientSessions = this.getLogicSessions();
                    if (logicClientSessions == null || logicClientSessions.isEmpty()) {
                        Thread.sleep(10, 0); // Lower CPU usage
                        continue; // Do not loop through non-existent sessions
                    }
                    for (LogicClientSession session : logicClientSessions) {
                        try {
                            // 更新logicSession
                            session.update();
                            if (session.getPacketsReceivedThisSecond() >= RakNet.getMaxPacketsPerSecond()) {
                                this.blockAddress(session.getInetAddress(), "Too many packets",
                                        RakNet.MAX_PACKETS_PER_SECOND_BLOCK);
                            }
                        } catch (Throwable throwable) {
                            // 回调listener,并去除掉session
                            for (DecoServerListener listener : listeners) {
                                listener.onSessionException(session, throwable);
                            }
                            this.removeSession(session, throwable.getMessage());
                        }
                    }
                    Thread.sleep(1, 1); // Lower CPU usage
                } catch (Exception e) {
                    logger.error("decoServer loop running failed!", e);
                }
            }
        } catch (InterruptedException e) {
            this.running = false;
            throw new RakNetException(e);
        }
    }

    public final Thread startThreaded() {
        // Give the thread a reference
        DecoServer server = this;

        // Create thread and start it
        Thread thread = new Thread() {
            @Override
            public void run() {
                try {
                    server.start();
                } catch (Throwable throwable) {
                    if (server.getListeners().length > 0) {
                        for (DecoServerListener listener : server.getListeners()) {
                            listener.onThreadException(throwable);
                        }
                    } else {
                        throwable.printStackTrace();
                    }
                }
            }
        };
        thread.setName("TCPRAKNET_SERVER_" + server.getGloballyUniqueId());
        thread.start();
        this.serverThread = thread;
        logger.info("Started on thread with name " + thread.getName());

        // Return the thread so it can be modified
        return thread;
    }

    public final void shutdown(String reason) {
        // Tell the server to stop running
        this.running = false;

        // Disconnect sessions
        this.removeAllPhysicalSession("shut down server");

        // Interrupt its thread if it owns one
        if (this.serverThread != null) {
            serverThread.interrupt();
        }

        // Notify API
        logger.info("Shutdown server");
        for (DecoServerListener listener : listeners) {
            listener.onServerShutdown();
        }

        // close bootstrap
        this.bossGroup.shutdownGracefully();
    }

    /**
     * Stops the server.
     */
    public final void shutdown() {
        this.shutdown("Server shutdown");
    }

}
