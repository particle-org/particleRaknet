package com.particle.route.jraknet.tcpnet.session;

import com.particle.route.jraknet.session.RakNetState;
import com.particle.route.jraknet.tcpnet.protocol.DecoPackage;
import com.particle.route.jraknet.tcpnet.protocol.message.McAcknowledge;
import com.particle.route.jraknet.tcpnet.protocol.message.McPackage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public abstract class PhysicalSession {

    private static final Logger logger = LoggerFactory.getLogger(PhysicalSession.class);

    private String loggerName;

    private final long guid;
    private final Channel channel;
    private final InetSocketAddress address;
    private RakNetState state;

    private int sendSequenceNumber;
    private int receiveSequenceNumber;

    public PhysicalSession (long guid, Channel channel, InetSocketAddress address) {
        this.loggerName = "session #" + guid;
        this.guid = guid;
        this.channel = channel;
        this.address = address;
        this.state = RakNetState.DISCONNECTED;
        sendSequenceNumber = 0;
        receiveSequenceNumber = -1;
    }

    public final long getGloballyUniqueId() {
        return this.guid;
    }

    public final InetSocketAddress getAddress() {
        return this.address;
    }

    public Channel getChannel() {
        return channel;
    }

    public final InetAddress getInetAddress() {
        return address.getAddress();
    }

    public final int getInetPort() {
        return address.getPort();
    }

    public RakNetState getState() {
        return this.state;
    }

    public void setState(RakNetState state) {
        this.state = state;
        logger.debug(loggerName + "set state to " + state);
    }

    /**
     * 处理ack消息
     * @param mcAcknowledge
     * @param logicSession
     */
    protected final void handleMcKnowledgePackage(McAcknowledge mcAcknowledge, LogicSession logicSession) {
        if (logicSession != null) {
            logicSession.handleAcknowledge(mcAcknowledge);
        }
    }

    /**
     * 处理mcPackage的消息
     * @param mcPackage
     * @param logicSession
     */
    protected final void handleMcPackage(McPackage mcPackage, LogicSession logicSession) {
        // 增加logicSession是否存在
        if (logicSession != null) {
            short packetId = mcPackage.getId();
            logger.debug("handleMcPackage, packageId[{}]", packetId);
            // 处理CustomPackage消息
            logicSession.handleMcPackage(mcPackage);
        } else {
            logger.debug("handleMcPackage failed, mcPackage pacakgeId[{}] the logicSession is null!", mcPackage.getId());
        }
    }

    /**
     * 发送mcPackage消息
     *
     * @param mcPacket
     * @param logicSession
     * @return
     */
    public final void sendMcMessage(McPackage mcPacket, LogicSession logicSession) {
        if (mcPacket == null || mcPacket.encodeByteBuf == null) {
            logger.error("cant send message with mcPackage==null or mcPackage.encodeByteBuf == null!");
            return;
        }
        DecoPackage decoPackage = new DecoPackage(mcPacket.encodeByteBuf);
        ChannelFuture channelFuture = channel.writeAndFlush(decoPackage);
        logicSession.addChannelFlushListener(channelFuture, decoPackage);
    }

    /**
     * 发送ack消息
     * @param acknowledge
     * @param logicSession
     * @return
     */
    public final void sendAcknowledgeMessage(McAcknowledge acknowledge, LogicSession logicSession) {
        DecoPackage decoPackage = new DecoPackage(acknowledge.buffer());
        ChannelFuture channelFuture = channel.writeAndFlush(decoPackage);
        logicSession.addChannelFlushListener(channelFuture, decoPackage);
    }

    /**
     * 直接发送mcPackage
     * @param decoPackage
     */
    public final void sendDecoPackage(DecoPackage decoPackage, LogicSession logicSession) {
        ChannelFuture channelFuture = channel.writeAndFlush(decoPackage);
        logicSession.addChannelFlushListener(channelFuture, decoPackage);
    }

}
