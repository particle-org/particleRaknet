package com.particle.route.jraknet.tcpnet.session;

import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Acknowledge;
import com.particle.route.jraknet.session.RakNetState;
import com.particle.route.jraknet.tcpnet.client.DecoClient;
import com.particle.route.jraknet.tcpnet.client.DecoClientListener;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;
import com.particle.route.jraknet.tcpnet.protocol.message.McAcknowledge;
import com.particle.route.jraknet.tcpnet.protocol.message.McPackage;
import com.particle.route.jraknet.tcpnet.protocol.session.*;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PhysicalServeSession extends PhysicalSession {

    private static final Logger logger = LoggerFactory.getLogger(PhysicalServeSession.class);

    private final DecoClient client;

    private final long timeCreated;

    private long timestamp;

    private final ConcurrentHashMap<InetSocketAddress, LogicServerSession> logicSessions;

    private final ConcurrentLinkedQueue <LogicServerSession> logicSessionQueues;

    private int createSessionStatus = DecoClient.LOGIN_NEW;

    /**
     * 连接session锁
     */
    private Object connectLock = new Object();

    /**
     *
     * @param decoClient
     * @param timeCreated
     * @param guid 服务端的guid
     * @param channel 跟服务端的channel
     * @param address 服务端的address
     */
    public PhysicalServeSession(DecoClient decoClient, long timeCreated, long guid, Channel channel, InetSocketAddress address) {
        super(guid, channel, address);
        this.client = decoClient;
        this.timeCreated = timeCreated;
        this.timestamp = timeCreated;
        this.logicSessions = new ConcurrentHashMap<InetSocketAddress, LogicServerSession>();
        this.logicSessionQueues = new ConcurrentLinkedQueue<>();
    }

    /**
     * 获取所有业务层的ip信息
     * @return
     */
    public final Collection<InetSocketAddress> getLogicSocketAddres() {
        return logicSessions.keySet();
    }

    /**
     * 获取业务层的session列表
     * @return
     */
    public final ConcurrentLinkedQueue<LogicServerSession> getLogicSessionCollections() {
        return logicSessionQueues;
    }

    /**
     * 获取业务层的session列表
     * @return
     */
    public final LogicServerSession[] getLogicSessions() {
        return logicSessionQueues.toArray(new LogicServerSession[logicSessions.size()]);
    }

    /**
     * 获取业务层的session总数
     * @return
     */
    public final int getLogicSessionCount() {
        return logicSessions.size();
    }

    /**
     * 业务层是否包含某ip
     * @param address
     * @return
     */
    public final boolean hasLogicSession(InetSocketAddress address) {
        return logicSessions.containsKey(address);
    }

    /**
     * 业务层是否包含某guid
     * @param guid
     * @return
     */
    public final boolean hasLogicSession(long guid) {
        for (LogicServerSession session : logicSessions.values()) {
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
    public final LogicServerSession getLogicSession(InetSocketAddress address) {
        return logicSessions.get(address);
    }

    /**
     * 根据guid获取业务层session
     * @param guid
     * @return
     */
    public final LogicServerSession getLogicSession(long guid) {
        for (LogicServerSession session : logicSessions.values()) {
            if (session.getGloballyUniqueId() == guid) {
                return session;
            }
        }
        return null;
    }

    /**
     * 去除单个logicSession
     * @param logicAddress
     * @param reason
     */
    private final void removeSession(InetSocketAddress logicAddress, String reason, boolean isSeverCmd) {
        logger.info("remove session， the logicAddress[{}], the reason[{}]", logicAddress, reason);
        if (logicSessions.containsKey(logicAddress)) {
            // Notify client of disconnection
            LogicServerSession session = logicSessions.remove(logicAddress);
            if (!isSeverCmd) {
                RemoveSessionReq removeSessionReq = new RemoveSessionReq();
                removeSessionReq.address = session.getAddress();
                removeSessionReq.clientGuid = session.getGloballyUniqueId();
                removeSessionReq.encode();
                if (!removeSessionReq.failed()) {
                    this.client.sendNettyMessage(removeSessionReq, this.getAddress());
                }
            }
            session.clearLogicServerSession();
            logicSessionQueues.remove(session);
            // 通知listener创建logicSession完毕
            for (DecoClientListener listener : client.getListeners()) {
                listener.onLogicDisconnect(session, "remove session");
            }
            client.releaseDecoClient();
        } else {
            logger.warn("Attempted to remove session that had not been added to the client");
        }
    }

    /**
     * 去除单个logicSession
     * @param logicAddress
     */
    public final void removeSession(InetSocketAddress logicAddress) {
        this.removeSession(logicAddress, "Disconnected from client", false);
    }

    /**
     * 去除所有的logicSession
     * 关闭连接，放到client层去做
     */
    public void removeAllLogicSession(String reason) {
        for (InetSocketAddress address : logicSessions.keySet()) {
            this.removeSession(address, reason, false);
        }
        logicSessions.clear();
    }

    /**
     * 去除logicSession
     * @param session
     * @param reason
     */
    public final void removeSession(LogicServerSession session, String reason) {
        this.removeSession(session.getAddress(), reason, false);
    }

    /**
     * 去除logicSession
     * @param session
     */
    public final void removeSession(LogicServerSession session) {
        this.removeSession(session, "Disconnected from client");
    }

    /**
     * 会阻塞等待，最多尝试次数为10次
     * 当多个玩家同时连接的时候，需要等待前一个玩家完成
     * @param logicAddress 玩家真正的地址信息，从客户端传递过来
     * @param logicGuid 玩家真正的guid，从客户端传递过来
     * @return
     */
    public final boolean createLogicSession(InetSocketAddress logicAddress, long logicGuid) {
        synchronized (connectLock) {
            while (createSessionStatus != DecoClient.LOGIN_NEW) {
                try {
                    //最多等待100毫秒
                    connectLock.wait(100);
                } catch (InterruptedException ie) {
                    logger.error("等待其他连接完成失败，异常：", ie);
                    return false;
                }
            }
        }
        createSessionStatus = DecoClient.LOGIN_CONNECTING;
        try {
            if (this.hasLogicSession(logicAddress)) {
                logger.warn("已存在address[{}]的logicAddres", logicAddress);
                return false;
            }
            LogicServerSession logicServerSession = new LogicServerSession(this.client, logicGuid, this.getChannel(), logicAddress, this);
            logicSessions.put(logicAddress, logicServerSession);
            logicSessionQueues.add(logicServerSession);
            int retryCounts = 10;
            synchronized (connectLock) {
                while (createSessionStatus != DecoClient.LOGIN_SUCCEED && retryCounts-- > 0) {
                    CreateSessionPackage createSessionPackage = new CreateSessionPackage();
                    logger.debug("logicAddress:{}", logicAddress);
                    createSessionPackage.address = logicAddress;
                    createSessionPackage.clientGuid = logicGuid;
                    createSessionPackage.encode();
                    if (createSessionPackage.failed()) {
                        logger.warn("创建createSessionPackage时候编码失败!");
                        return false;
                    }
                    this.client.sendNettyMessage(createSessionPackage, getAddress());
                    try {
                        //最多等待两秒
                        connectLock.wait(2000);
                    } catch (InterruptedException ie) {
                        logger.error("第一次连接失败，异常：", ie);
                        return false;
                    }
                }
            }

            if (createSessionStatus == DecoClient.LOGIN_SUCCEED) {
                LogicSessionHandShake logicSessionHandShake = new LogicSessionHandShake();
                logicSessionHandShake.address = logicAddress;
                logicSessionHandShake.clientGuid = logicGuid;
                logicSessionHandShake.encode();
                if (logicSessionHandShake.failed()) {
                    logger.warn("创建logicSessionHandShake时候编码失败!");
                    return false;
                } else {
                    logger.debug("logicSessionHandShake, packageId[{}], address[{}]", logicSessionHandShake.getId(), logicSessionHandShake.address);
                    this.client.sendNettyMessage(logicSessionHandShake, getAddress());
                }
            }

            if (createSessionStatus != DecoClient.LOGIN_SUCCEED) {
                this.removeSession(logicAddress, "can not create logic session", false);
                return false;
            } else {
                // 设置physicalServeSession可用
                // 发送消息时候，请先调用isConnected()方法，确认session是否可用
                if (logicServerSession != null) {
                    logicServerSession.setState(RakNetState.CONNECTED);
                }
                // 通知listener创建logicSession完毕
                for (DecoClientListener listener : client.getListeners()) {
                    listener.onLogicConnect(logicServerSession);
                }
                logger.info("onLogicConnect succeed! address[{}]", logicAddress);
            }
            return true;
        } finally {
            createSessionStatus = DecoClient.LOGIN_NEW;
        }
    }

    /**
     * 处理physicalPackage
     * @param packet
     * @param sender
     */
    public final void handlePhysicalPackage(RakNetPacket packet, InetSocketAddress sender) {
        short packageId = packet.getId();
        logger.debug("handlePhysicalPackage, packageId[{}], createSessionStatus[{}]", packageId, createSessionStatus);

        if (packageId == TcpMessageIdentifier.TCP_NEW_SESSION_EXISTED || packageId == TcpMessageIdentifier.TCP_NO_FREE_LOGIC_SESSIONS
                || packageId == TcpMessageIdentifier.TCP_CONNECT_BAN) {
            // notify 连接锁
            synchronized (connectLock) {
                createSessionStatus = DecoClient.LOGIN_FAILED;
                connectLock.notify();
            }
            return;
        } else if (packageId == TcpMessageIdentifier.TCP_NEW_SESSION_SUCCEED) {
            CreateSessionResp createSessionResp = new CreateSessionResp(packet);
            createSessionResp.decode();
            if (createSessionResp.failed()) {
                // notify 连接锁
                synchronized (connectLock) {
                    createSessionStatus = DecoClient.LOGIN_FAILED;
                    connectLock.notify();
                }
                return;
            }
            LogicServerSession logicServerSession = this.getLogicSession(createSessionResp.clientAddress);
            if (logicServerSession == null) {
                // notify 连接锁
                synchronized (connectLock) {
                    createSessionStatus = DecoClient.LOGIN_FAILED;
                    connectLock.notify();
                }
                return;
            }
            logicServerSession.setState(RakNetState.CONNECTED);
            // notify 连接锁
            synchronized (connectLock) {
                createSessionStatus = DecoClient.LOGIN_SUCCEED;
                connectLock.notify();
            }
        } else if (packageId == TcpMessageIdentifier.TCP_NEW_SESSION_UNEXISTED) {
            // 服务端不存在logic session，在create session握手阶段和删除session阶段会返回
            logger.error("服务端不存在此logic session!");
            return;
        } else if (packageId == TcpMessageIdentifier.TCP_REMOVE_SESSION_SUCCEED) {
            // 服务端发送remove logic session的消息
            RemoveSessionRsp removeSessionRsp = new RemoveSessionRsp(packet);
            removeSessionRsp.decode();
            if (!removeSessionRsp.failed()) {
                // 如果是主动发起的remove的动作，下面这个函数会跑两次
                this.removeSession(removeSessionRsp.address, "removed by server", true);
            }
        } else if (packageId == TcpMessageIdentifier.TCP_MC_IDENTIFIER) {
            McPackage mcPackage = new McPackage(packet);
            mcPackage.decode();
            if (mcPackage.failed()) {
                logger.error("mcPackage decode failed!");
                return;
            }
            LogicServerSession logicServerSession = this.getLogicSession(mcPackage.address);
            this.handleMcPackage(mcPackage, logicServerSession);
        }  else if (packageId == Acknowledge.ACKNOWLEDGED || packageId == Acknowledge.NOT_ACKNOWLEDGED) {
            McAcknowledge mcAcknowledge = new McAcknowledge(packet);
            mcAcknowledge.decode();
            if (mcAcknowledge.failed()) {
                logger.error("mcAcknowledge decode failed!");
                return;
            }
            LogicServerSession logicServerSession = this.getLogicSession(mcAcknowledge.address);
            this.handleMcKnowledgePackage(mcAcknowledge, logicServerSession);
        }
    }
}
