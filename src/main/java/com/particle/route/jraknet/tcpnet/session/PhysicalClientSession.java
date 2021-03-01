package com.particle.route.jraknet.tcpnet.session;


import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Acknowledge;
import com.particle.route.jraknet.server.BlockedAddress;
import com.particle.route.jraknet.session.RakNetState;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;
import com.particle.route.jraknet.tcpnet.protocol.message.McAcknowledge;
import com.particle.route.jraknet.tcpnet.protocol.message.McPackage;
import com.particle.route.jraknet.tcpnet.server.DecoServer;
import com.particle.route.jraknet.tcpnet.server.DecoServerListener;
import com.particle.route.jraknet.tcpnet.protocol.session.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public class PhysicalClientSession extends PhysicalSession {

    private static final Logger logger = LoggerFactory.getLogger(PhysicalClientSession.class);

    private DecoServer decoServer;
    private final long timeCreated;
    private long timestamp;

    private final ConcurrentHashMap<InetSocketAddress, LogicClientSession> logicSessions;


    public PhysicalClientSession (DecoServer server, long timeCreated, long guid, Channel channel, InetSocketAddress address) {
        super(guid, channel, address);
        this.decoServer = server;
        this.timeCreated = timeCreated;
        timestamp = System.currentTimeMillis();
        this.logicSessions = new ConcurrentHashMap<InetSocketAddress, LogicClientSession>();

    }

    /**
     * 获取decoServer
     * @return
     */
    public DecoServer getDecoServer() {
        return decoServer;
    }

    /**
     * 增加session
     * @param address
     * @param session
     */
    private void addLogicSession(InetSocketAddress address, LogicClientSession session) {
        logicSessions.put(address, session);
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
    public final Collection<LogicClientSession> getLogicSessionCollections() {
        return logicSessions.values();
    }

    /**
     * 获取业务层的session列表
     * @return
     */
    public final LogicClientSession[] getLogicSessions() {
        return logicSessions.values().toArray(new LogicClientSession[logicSessions.size()]);
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
        for (LogicClientSession session : logicSessions.values()) {
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
        return logicSessions.get(address);
    }

    /**
     * 根据guid获取业务层session
     * @param guid
     * @return
     */
    public final LogicClientSession getLogicSession(long guid) {
        for (LogicClientSession session : logicSessions.values()) {
            if (session.getGloballyUniqueId() == guid) {
                return session;
            }
        }
        return null;
    }

    /**
     * 去除该physical session下的所有logicSession
     * @param reason
     */
    public final void removeAllLogicSession(String reason) {
        for (InetSocketAddress address : logicSessions.keySet()) {
            this.removeSession(address, reason);
        }
        logicSessions.clear();
    }

    /**
     * 去除logicSession
     * @param logicAddress
     * @param reason
     */
    public final void removeSession(InetSocketAddress logicAddress, String reason) {
        logger.info(String.format("remove logicAddress[%s] reason:%s", logicAddress, reason));
        if (logicSessions.containsKey(logicAddress)) {
            // Notify client of disconnection
            LogicClientSession session = logicSessions.remove(logicAddress);
            RemoveSessionRsp removeSessionRsp = new RemoveSessionRsp();
            removeSessionRsp.address = logicAddress;
            removeSessionRsp.clientGuid = session.getGloballyUniqueId();
            removeSessionRsp.encode();
            if (!removeSessionRsp.failed()) {
                decoServer.sendNettyMessage(getChannel(), removeSessionRsp, logicAddress);
            } else {
                logger.error("send RemoveSessionRsp failed");
            }

            // Notify API
            logger.debug("Removed session with address " + logicAddress);
            if (session.getState() == RakNetState.CONNECTED) {
                for (DecoServerListener listener : decoServer.getListeners()) {
                    listener.onClientDisconnect(session, reason);
                }
            } else {
                for (DecoServerListener listener : decoServer.getListeners()) {
                    listener.onClientPreDisconnect(logicAddress, reason);
                }
            }
        } else {
            logger.warn("Attempted to remove session that had not been added to the server");
        }
    }

    /**
     * 去除logicSession
     * @param address
     */
    public final void removeSession(InetSocketAddress address) {
        this.removeSession(address, "Disconnected from server");
    }

    /**
     * 去除logicSession
     * @param session
     * @param reason
     */
    public final void removeSession(LogicClientSession session, String reason) {
        this.removeSession(session.getAddress(), reason);
    }

    /**
     * 去除logicSession
     * @param session
     */
    public final void removeSession(LogicClientSession session) {
        this.removeSession(session, "Disconnected from server");
    }

    /**
     * 清除其附属的logicSession
     */
    public void clearLogicSession() {
        logicSessions.clear();
    }

    /**
     * 处理physical层的消息
     * @param packet
     * @param sender
     * @param channelHandlerContext
     */
    public final void handlePhysicalPackage(RakNetPacket packet, InetSocketAddress sender, ChannelHandlerContext channelHandlerContext) {
        short packageId = packet.getId();
        logger.debug("handlePhysicalPackage, packgeId{}", packageId);
        if (packageId == TcpMessageIdentifier.TCP_NEW_SESSION) {
            // 创建logicSession
            CreateSessionPackage createSessionPackage = new CreateSessionPackage(packet);
            createSessionPackage.decode();
            if (createSessionPackage.failed()) {
                return;
            }
            if (createSessionPackage.magic) {
                RakNetPacket errorPackage = null;
                if (this.hasLogicSession(createSessionPackage.address)) {
                    errorPackage = new RakNetPacket(TcpMessageIdentifier.TCP_NEW_SESSION_EXISTED);
                }  else if (decoServer.getLogicSessionCount() >= decoServer.getMaxConnections() && decoServer.getMaxConnections() >= 0) {
                    errorPackage = new RakNetPacket(TcpMessageIdentifier.TCP_NO_FREE_LOGIC_SESSIONS);
                } else if (decoServer.addressBlocked(createSessionPackage.address.getAddress())) {
                    LogicSessionBaned ban = new LogicSessionBaned();
                    ban.serverGuid = this.getGloballyUniqueId();
                    ban.encode();
                    errorPackage = ban;
                }
                if (errorPackage != null) {
                    decoServer.sendNettyMessage(channelHandlerContext.channel(), errorPackage, sender);
                    return;
                }
                CreateSessionResp resp = new CreateSessionResp();
                resp.clientAddress = createSessionPackage.address;
                logger.debug("createSessionPackage.address: {}",  createSessionPackage.address);
                resp.serverGuid = createSessionPackage.clientGuid;
                logger.debug("createSessionPackage.clientGuid:{}",  createSessionPackage.clientGuid);
                resp.encode();
                if (!resp.failed()) {
                    // Create session
                    LogicClientSession clientSession = new LogicClientSession(decoServer,
                            System.currentTimeMillis(), createSessionPackage.clientGuid,
                            channelHandlerContext.channel(), createSessionPackage.address, this);
                    logicSessions.put(createSessionPackage.address, clientSession);
                    clientSession.setState(RakNetState.DISCONNECTED);
                    for (DecoServerListener listener : decoServer.getListeners()) {
                        listener.onClientPreConnect(createSessionPackage.address);
                    }
                    decoServer.sendNettyMessage(channelHandlerContext.channel(), resp, sender);
                }
            }
        } else if (packageId == TcpMessageIdentifier.TCP_SESSION_HANDSHAKE) {
            // 创建logicSession握手完成，标志logicSession可用了
            LogicSessionHandShake logicSessionHandShake = new LogicSessionHandShake(packet);
            logicSessionHandShake.decode();
            if (!logicSessionHandShake.failed()) {
                logger.debug("logicSessionHandShake.address:{}", logicSessionHandShake.address);
                LogicClientSession logicSession = this.getLogicSession(logicSessionHandShake.address);
                if (logicSession != null) {
                    logicSession.setState(RakNetState.CONNECTED);
                    for (DecoServerListener listener : decoServer.getListeners()) {
                        listener.onClientConnect(logicSession);
                    }
                    logger.debug("session handshake succeed!");
                } else {
                    decoServer.sendNettyMessage(getChannel(), new RakNetPacket(TcpMessageIdentifier.TCP_NEW_SESSION_UNEXISTED), logicSessionHandShake.address);
                    logger.error("session handShake，发现未创建logicSession!");
                }
            } else {
                logger.error("session handshake decode failed!");
            }
        } else if (packageId == TcpMessageIdentifier.TCP_REMOVE_SESSION){
            // 删除logicSession
            RemoveSessionReq removeSessionReq = new RemoveSessionReq(packet);
            removeSessionReq.decode();
            if (removeSessionReq.failed()) {
                return;
            }
            if (removeSessionReq.magic) {
                RakNetPacket errorPackage = null;
                if (!this.hasLogicSession(removeSessionReq.address)) {
                    errorPackage = new RakNetPacket(TcpMessageIdentifier.TCP_NEW_SESSION_UNEXISTED);
                }
                if (errorPackage != null) {
                    decoServer.sendNettyMessage(channelHandlerContext.channel(), errorPackage, sender);
                    return;
                }
                this.removeSession(removeSessionReq.address, "logic client disconnect");
            }
        } else if (packageId == TcpMessageIdentifier.TCP_MC_IDENTIFIER) {
            McPackage mcPackage = new McPackage(packet);
            mcPackage.decode();
            if (mcPackage.failed()) {
                logger.error("mcPackage decode failed!");
                return;
            }
            // 检查addres block状态
            if (decoServer.addressBlocked(mcPackage.address.getAddress())) {
                BlockedAddress status = decoServer.getBlockedStatus(sender.getAddress());
                if (status != null && status.getTime() <= BlockedAddress.PERMANENT_BLOCK) {
                    return; // Permanently blocked
                }
                if (status != null && System.currentTimeMillis() - status.getStartTime() < status.getTime()) {
                    return; // Time hasn't expired
                }
            }
            LogicClientSession logicClientSession = this.getLogicSession(mcPackage.address);
            this.handleMcPackage(mcPackage, logicClientSession);
        } else if (packageId == Acknowledge.ACKNOWLEDGED || packageId == Acknowledge.NOT_ACKNOWLEDGED) {
            McAcknowledge mcAcknowledge = new McAcknowledge(packet);
            mcAcknowledge.decode();
            if (mcAcknowledge.failed()) {
                logger.error("mcAcknowledge decode failed!");
                return;
            }
            // 检查addres block状态
            if (decoServer.addressBlocked(mcAcknowledge.address.getAddress())) {
                BlockedAddress status = decoServer.getBlockedStatus(sender.getAddress());
                if (status != null && status.getTime() <= BlockedAddress.PERMANENT_BLOCK) {
                    return; // Permanently blocked
                }
                if (status != null && System.currentTimeMillis() - status.getStartTime() < status.getTime()) {
                    return; // Time hasn't expired
                }
            }
            LogicClientSession logicClientSession = this.getLogicSession(mcAcknowledge.address);
            this.handleMcKnowledgePackage(mcAcknowledge, logicClientSession);
        }
    }

}
