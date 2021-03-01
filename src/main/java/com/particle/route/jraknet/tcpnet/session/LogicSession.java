package com.particle.route.jraknet.tcpnet.session;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.MessageIdentifier;
import com.particle.route.jraknet.protocol.Reliability;
import com.particle.route.jraknet.protocol.message.CustomPacket;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Acknowledge;
import com.particle.route.jraknet.protocol.message.acknowledge.AcknowledgeType;
import com.particle.route.jraknet.protocol.message.acknowledge.Record;
import com.particle.route.jraknet.protocol.status.ConnectedPing;
import com.particle.route.jraknet.protocol.status.ConnectedPong;
import com.particle.route.jraknet.tcpnet.protocol.DecoPackage;
import com.particle.route.jraknet.tcpnet.protocol.message.McAcknowledge;
import com.particle.route.jraknet.tcpnet.protocol.message.McEncapsulatedPacket;
import com.particle.route.jraknet.tcpnet.protocol.message.McPackage;
import com.particle.route.jraknet.util.map.concurrent.ConcurrentIntMap;
import com.particle.route.jraknet.session.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.internal.ConcurrentSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class LogicSession implements UnumRakNetPeer, GeminusRakNetPeer {

    private static final Logger logger = LoggerFactory.getLogger(LogicSession.class);

    // Session data
    private String loggerName;
    private final long guid;
    private final Channel channel;
    private final InetSocketAddress address;
    private RakNetState state;

    // Timing
    private int packetsSentThisSecond;
    private int packetsReceivedThisSecond;
    private long lastPacketCounterResetTime;
    private long lastPacketSendTime;
    private long lastPacketReceiveTime;
    private long lastRecoverySendTime;
    private long lastKeepAliveSendTime;
    private long lastPingSendTime;

    // Packet data
    private int messageIndex;
    private int splitId;
    private final ConcurrentSet<Integer> reliablePackets;
    private int lastRemovedReliablePacketIndex = -1;   //保证编号为0的数据包被正确清除
    private static final int RELIABLE_PACKETS_SIZE = 1000;
    private final ConcurrentIntMap<SplitPacket> splitQueue;

    protected final ConcurrentLinkedQueue<EncapsulatedPacket> sendQueue;
    private final ConcurrentIntMap<EncapsulatedPacket[]>[] recoveryQueue;
    private int recoveryQueueIndex;
    private final ConcurrentHashMap<EncapsulatedPacket, Integer> ackReceiptPackets;

    // Ordering and sequencing
    private int sendSequenceNumber;
    private int receiveSequenceNumber;
    private final int[] orderSendIndex;
    private final int[] orderReceiveIndex;
    private final int[] sequenceSendIndex;
    private final int[] sequenceReceiveIndex;
    private final ConcurrentIntMap<ConcurrentIntMap<EncapsulatedPacket>> handleQueue;
    private int handleQueueSize = 0;

    // Latency detection
    private boolean latencyEnabled;
    private int pongsReceived;
    private long latencyIdentifier;
    private long totalLatency;
    private long latency;
    private long lastLatency;
    private long lowestLatency;
    private long highestLatency;

    //绑定在这个session上的数据，上层业务可以将自己的数据绑定在这个session中，而不需要建立hashmao索引，提高效率
    private Object context;
    /**
     * 往tcp管道发送失败队列
     */
    protected final ConcurrentLinkedQueue<DecoPackage> retrySendQueue;

    private PhysicalSession physicalSession;

    public LogicSession(long guid, Channel channel,
                        InetSocketAddress address, PhysicalSession physicalSession) {
        // Session data
        this.loggerName = "session #" + guid;
        this.guid = guid;
        this.channel = channel;
        this.address = address;
        this.physicalSession = physicalSession;
        this.state = RakNetState.DISCONNECTED;

        // Timing
        this.lastPacketReceiveTime = System.currentTimeMillis();

        // Packet data
        this.reliablePackets = new ConcurrentSet<Integer>();
        this.splitQueue = new ConcurrentIntMap<SplitPacket>();
        this.sendQueue = new ConcurrentLinkedQueue<EncapsulatedPacket>();
        this.recoveryQueue = new ConcurrentIntMap[2];
        this.recoveryQueue[0] = new ConcurrentIntMap<EncapsulatedPacket[]>();
        this.recoveryQueue[1] = new ConcurrentIntMap<EncapsulatedPacket[]>();
        this.recoveryQueueIndex = 0;
        this.ackReceiptPackets = new ConcurrentHashMap<EncapsulatedPacket, Integer>();

        // Ordering and sequencing
        this.orderSendIndex = new int[RakNet.MAX_CHANNELS];
        this.orderReceiveIndex = new int[RakNet.MAX_CHANNELS];
        this.sequenceSendIndex = new int[RakNet.MAX_CHANNELS];
        this.sequenceReceiveIndex = new int[RakNet.MAX_CHANNELS];
        this.handleQueue = new ConcurrentIntMap<ConcurrentIntMap<EncapsulatedPacket>>();
        for (int i = 0; i < RakNet.MAX_CHANNELS; i++) {
            sequenceReceiveIndex[i] = -1;
            handleQueue.put(i, new ConcurrentIntMap<EncapsulatedPacket>());
        }

        // Latency detection
        this.latencyEnabled = true;
        this.latency = -1; // We can't predict a player's latency
        this.lastLatency = -1;
        this.lowestLatency = -1;
        this.highestLatency = -1;

        this.retrySendQueue = new ConcurrentLinkedQueue<DecoPackage>();
    }

    /**
     * 获取最大的split Size
     * @return
     */
    public final short getMaxSplitPackageSize() {
        return Short.MAX_VALUE;
    }

    /**
     * 获取其附属的PhysicalSession
     * @return
     */
    public final PhysicalSession getPhysicalSession() {
        return this.physicalSession;
    }

    public final long getGloballyUniqueId() {
        return this.guid;
    }

    public final InetSocketAddress getAddress() {
        return this.address;
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
        logger.debug("set state to {}", state);
    }

    public int getPacketsSentThisSecond() {
        return this.packetsSentThisSecond;
    }

    public int getPacketsReceivedThisSecond() {
        return this.packetsReceivedThisSecond;
    }

    public long getLastPacketSendTime() {
        return this.lastPacketSendTime;
    }

    public long getLastPacketReceiveTime() {
        return this.lastPacketReceiveTime;
    }

    public int bumpMessageIndex() {
        logger.debug("Bumped message index from {} to {}", messageIndex, messageIndex + 1);
        return this.messageIndex++;
    }

    public void enableLatencyDetection(boolean enabled) {
        this.latencyEnabled = enabled;
        this.latency = (enabled ? this.latency : -1);
        this.pongsReceived = (enabled ? this.pongsReceived : 0);
        logger.info("{} latency detection.", (enabled ? "Enabled" : "Disabled"));
    }

    public boolean latencyDetectionEnabled() {
        return this.latencyEnabled;
    }

    public long getLatency() {
        return this.latency;
    }

    public long getLastLatency() {
        return this.lastLatency;
    }

    public long getLowestLatency() {
        return this.lowestLatency;
    }

    public long getHighestLatency() {
        return this.highestLatency;
    }

    @Override
    public final EncapsulatedPacket sendMessage(Reliability reliability, int channel, Packet packet)
            throws InvalidChannelException {
        if (channel >= RakNet.MAX_CHANNELS) {
            throw new InvalidChannelException();
        }

        // Set packet properties
        McEncapsulatedPacket encapsulated = new McEncapsulatedPacket();
        encapsulated.reliability = reliability;
        encapsulated.orderChannel = (byte) channel;
        encapsulated.payload = packet;
        if (reliability.isReliable()) {
            encapsulated.messageIndex = this.bumpMessageIndex();
        }
        if (reliability.isOrdered() || reliability.isSequenced()) {
            encapsulated.orderIndex = (reliability.isOrdered() ? this.orderSendIndex[channel]++ : this.sequenceSendIndex[channel]++);
        }
        // Do we need to split the packet?
        sendQueue.add(encapsulated);

        return null;
    }

    @Override
    public final EncapsulatedPacket sendMessage(long guid, Reliability reliability, int channel, Packet packet)
            throws InvalidChannelException {
        if (this.guid == guid) {
            return this.sendMessage(reliability, channel, packet);
        } else {
            throw new IllegalArgumentException("Invalid GUID");
        }
    }

    public final void setAckReceiptPackets(EncapsulatedPacket[] packets) {
        for (EncapsulatedPacket packet : packets) {
            EncapsulatedPacket clone = packet.getClone();
            if (!clone.reliability.requiresAck()) {
                throw new IllegalArgumentException("Invalid reliability " + packet.reliability);
            }
            clone.ackRecord = packet.ackRecord;
            ackReceiptPackets.put(clone, clone.ackRecord.getIndex());
        }
    }

    /**
     * 发送如果失败，会重新放到重试队列中
     * @param channelFuture
     * @param decoPackage
     */
    public void addChannelFlushListener(ChannelFuture channelFuture, DecoPackage decoPackage) {
        if (channelFuture == null || decoPackage == null) {
            return;
        }
        channelFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if(!channelFuture.isSuccess()) {
                    decoPackage.setRetryCounts(decoPackage.getRetryCounts() + 1);
                    retrySendQueue.add(decoPackage);
                    logger.error("{} add retrySendQueue!", loggerName);
                }
            }
        });
    }

    /**
     * 直接发送decoPackage
     * @param decoPackage
     */
    private final void sendDecoPackage(DecoPackage decoPackage) {
        physicalSession.sendDecoPackage(decoPackage, this);
    }

    /**
     * 发送封装的McEncapsulatedPacket，直接发送到physical层
     * @param encapsulated
     * @param updateRecoveryQueue
     * @return
     */
    private final int sendMcEncapsulatedPacket(ArrayList<McEncapsulatedPacket> encapsulated, boolean updateRecoveryQueue) {
        McPackage mcPackage = new McPackage();
        mcPackage.sequenceNumber = this.sendSequenceNumber++;
        mcPackage.messages = encapsulated;
        mcPackage.address = this.getAddress();
        mcPackage.clientGuid = this.getGloballyUniqueId();
        mcPackage.encode();

        // Send packet
        if (this.physicalSession != null) {
            this.physicalSession.sendMcMessage(mcPackage, this);
        }

        this.packetsSentThisSecond++;
        this.lastPacketSendTime = System.currentTimeMillis();
        logger.debug("{} Sent custom packet with sequence number {}", loggerName, mcPackage.sequenceNumber);
        return mcPackage.sequenceNumber;
    }

    /**
     * 发送封装的McEncapsulatedPacket，直接发送到physical层
     * @param encapsulated
     * @param updateRecoveryQueue
     * @return
     */
    private final int sendMcEncapsulatedPacket(McEncapsulatedPacket[] encapsulated, boolean updateRecoveryQueue) {
        ArrayList<McEncapsulatedPacket> encapsulatedArray = new ArrayList<McEncapsulatedPacket>();
        for (McEncapsulatedPacket message : encapsulated) {
            encapsulatedArray.add(message);
        }
        return this.sendMcEncapsulatedPacket(encapsulatedArray, updateRecoveryQueue);
    }

    /**
     * 发送封装的McEncapsulatedPacket，直接发送到physical层
     * @param encapsulated
     * @param updateRecoveryQueue
     * @return
     */
    private final int sendMcEncapsulatedPacket(EncapsulatedPacket[] encapsulated, boolean updateRecoveryQueue) {
        ArrayList<McEncapsulatedPacket> encapsulatedArray = new ArrayList<McEncapsulatedPacket>();
        for (EncapsulatedPacket message : encapsulated) {
            if (message instanceof  McEncapsulatedPacket) {
                encapsulatedArray.add((McEncapsulatedPacket) message);
            } else {
                logger.error("can not send EncapsulatedPacket package!");
            }
        }
        return this.sendMcEncapsulatedPacket(encapsulatedArray, updateRecoveryQueue);
    }

    /**
     * 发送ack消息
     * @param type
     * @param records
     */
    private final void sendAcknowledge(AcknowledgeType type, Record... records) {
        // Create Acknowledge packet
        McAcknowledge acknowledge = new McAcknowledge(type);
        for (Record record : records) {
            acknowledge.records.add(record);
        }
        acknowledge.address = this.getAddress();
        acknowledge.clientGuid = this.getGloballyUniqueId();
        acknowledge.encode();
        if (this.physicalSession != null) {
            this.physicalSession.sendAcknowledgeMessage(acknowledge,this);
        }

        // Update packet data
        this.lastPacketSendTime = System.currentTimeMillis();
        logger.debug("{} Sent {} records in {} packet", loggerName, acknowledge.records.size(), (type == AcknowledgeType.ACKNOWLEDGED ? "ACK" : "NACK"));
    }

    public final void handleMcPackage(McPackage mcPackage) {
        // Update packet data
        this.packetsReceivedThisSecond++;

        // Only handle if it is a newer packet
        this.receiveSequenceNumber = mcPackage.sequenceNumber;
        for (McEncapsulatedPacket encapsulated : mcPackage.messages) {
            this.handleEncapsulated(encapsulated);
        }

        // Update packet data
        this.lastPacketReceiveTime = System.currentTimeMillis();

        logger.debug("Handled custom packet with sequence number {}", mcPackage.sequenceNumber);
    }

    public final void handleAcknowledge(Acknowledge acknowledge) {
        if (acknowledge.getType().equals(AcknowledgeType.ACKNOWLEDGED)) {
            for (Record record : acknowledge.records) {
                // Get record data
                int recordIndex = record.getIndex();

                // Are any packets associated with an ACK receipt tied to
                // this record?
                Iterator<EncapsulatedPacket> ackReceiptPacketsI = ackReceiptPackets.keySet().iterator();
                while (ackReceiptPacketsI.hasNext()) {
                    EncapsulatedPacket packet = ackReceiptPacketsI.next();
                    int packetRecordIndex = ackReceiptPackets.get(packet).intValue();
                    if (recordIndex == packetRecordIndex) {
                        this.onAcknowledge(record, packet);
                        packet.ackRecord = null;
                        ackReceiptPacketsI.remove();
                    }
                }

                // Remove acknowledged packet from the recovery queue
                recoveryQueue[0].remove(recordIndex);
                recoveryQueue[1].remove(recordIndex);
            }
        } else if (acknowledge.getType().equals(AcknowledgeType.NOT_ACKNOWLEDGED)) {
            // Track old sequence numbers so they can be properly renamed
            int[] oldSequenceNumbers = new int[acknowledge.records.size()];
            int[] newSequenceNumbers = new int[oldSequenceNumbers.length];

            for (int i = 0; i < acknowledge.records.size(); i++) {
                // Get record data
                Record record = acknowledge.records.get(i);
                int recordIndex = record.getIndex();

                // Are any packets associated with an ACK receipt tied to
                // this record?
                Iterator<EncapsulatedPacket> ackReceiptPacketsI = ackReceiptPackets.keySet().iterator();
                while (ackReceiptPacketsI.hasNext()) {
                    EncapsulatedPacket packet = ackReceiptPacketsI.next();
                    int packetRecordIndex = ackReceiptPackets.get(packet).intValue();

                    if (recordIndex == packetRecordIndex && !packet.reliability.isReliable()) {
                        this.onNotAcknowledge(record, packet);
                        packet.ackRecord = null;
                        ackReceiptPackets.remove(packet);
                    }
                }

                // Update records and resend lost packets
                //这里注意多线程模式下乒乓缓存的同步
                //首先取得recoveryQueue工作缓存中的包，判断是否需要重发
                //获取的过程中remove掉这个包
                //在重新发送后将这个包重新放入工作缓存中
                EncapsulatedPacket[] recoveryPackets = recoveryQueue[recoveryQueueIndex].remove(recordIndex);
                if (recoveryPackets != null) {
                    oldSequenceNumbers[i] = recordIndex;
                    newSequenceNumbers[i] = this.sendMcEncapsulatedPacket(recoveryPackets, false);

                    recoveryQueue[recoveryQueueIndex].put(newSequenceNumbers[i], recoveryPackets);
                } else {
                    oldSequenceNumbers[i] = -1;
                    newSequenceNumbers[i] = -1;
                }
            }
        }

        // Update packet data
        this.lastPacketReceiveTime = System.currentTimeMillis();
        logger.debug("{} Handled {} packet with {} records", loggerName, (acknowledge.getType() == AcknowledgeType.ACKNOWLEDGED ? "ACK" : "NACK"), acknowledge.records.size());
    }

    private final void handleEncapsulated(McEncapsulatedPacket encapsulated) {
        Reliability reliability = encapsulated.reliability;

        // Make sure we are not handling a duplicate
        if (reliability.isReliable()) {
            if (reliablePackets.contains(encapsulated.messageIndex)) {
                return; // Do not handle, it is a duplicate
            }
            reliablePackets.add(encapsulated.messageIndex);

            //清除过期的数据包
            for (int i = encapsulated.messageIndex - RELIABLE_PACKETS_SIZE; i > lastRemovedReliablePacketIndex; i--) {
                reliablePackets.remove(i);
            }

            lastRemovedReliablePacketIndex = encapsulated.messageIndex - RELIABLE_PACKETS_SIZE;
        }

        // Make sure we are handling everything in an ordered/sequenced fashion
        int orderIndex = encapsulated.orderIndex;
        int orderChannel = encapsulated.orderChannel;
        if (orderChannel >= RakNet.MAX_CHANNELS) {
            throw new InvalidChannelException();
        } else {
            // Channel is valid, it is safe to handle
            if (reliability.isOrdered()) {
                //每个channel都有单独的orderReceiveIndex用于计数，若orderIndex没有按顺序到达，则等待之前的包到了后才处理
                //这里每个channel都有单独的orderIndex，而nukkit错误使用了messageIndex，会导致处理错误
                handleQueue.get(orderChannel).put(orderIndex, encapsulated);

                //handleQueueSize检查，避免因为漏包导致处理队列卡住
                //handleQueue的大小最大为100，若超过100则丢弃多余的数据包
                //玩家高峰时期每秒最多发送100个数据包，若该值太大会导致玩家长时间卡顿，若该值太小会导致错误丢弃玩家数据包
                if (handleQueueSize > 99) {
                    orderReceiveIndex[orderChannel]++;
                } else {
                    handleQueueSize++;
                }

                while (handleQueue.get(orderChannel).containsKey(orderReceiveIndex[orderChannel])) {
                    EncapsulatedPacket orderedEncapsulated = handleQueue.get(orderChannel)
                            .get(orderReceiveIndex[orderChannel]++);
                    handleQueue.get(orderChannel).remove(orderReceiveIndex[orderChannel] - 1);

                    handleQueueSize--;
                    this.handleMessage0(encapsulated.orderChannel, new RakNetPacket(orderedEncapsulated.payload));
                }
            } else if (reliability.isSequenced()) {
                if (orderIndex > sequenceReceiveIndex[orderChannel]) {
                    sequenceReceiveIndex[orderChannel] = orderIndex;
                    this.handleMessage0(encapsulated.orderChannel, new RakNetPacket(encapsulated.payload));
                }
            } else {
                this.handleMessage0(encapsulated.orderChannel, new RakNetPacket(encapsulated.payload));
            }
        }
        logger.debug("Handled encapsulated packet with {} reliability", encapsulated.reliability);
    }

    private final void handleMessage0(int channel, RakNetPacket packet) {
        short packetId = packet.getId();
        logger.debug("handleMessage0:{}", packetId);
        if (packetId == MessageIdentifier.ID_CONNECTED_PING) {
            ConnectedPing ping = new ConnectedPing(packet);
            ping.decode();

            ConnectedPong pong = new ConnectedPong();
            pong.identifier = ping.identifier;
            pong.encode();
            this.sendMessage(Reliability.UNRELIABLE, pong);
        } else if (packetId == MessageIdentifier.ID_CONNECTED_PONG) {
            ConnectedPong pong = new ConnectedPong(packet);
            pong.decode();

            if (latencyEnabled == true) {
                if (latencyIdentifier - pong.identifier == 1) {
                    long latencyRaw = (this.lastPacketReceiveTime - this.lastPingSendTime);

                    // Get last latency result
                    this.lastLatency = latencyRaw;

                    // Get lowest and highest latency
                    if (this.pongsReceived == 0) {
                        this.lowestLatency = latencyRaw;
                        this.highestLatency = latencyRaw;
                    } else {
                        if (latencyRaw < lowestLatency) {
                            this.lowestLatency = latencyRaw;
                        } else if (latencyRaw > highestLatency) {
                            this.highestLatency = latencyRaw;
                        }
                    }

                    // Get average latency
                    this.pongsReceived++;
                    this.totalLatency += latencyRaw;
                    this.latency = (totalLatency / pongsReceived);
                }
            }

            this.latencyIdentifier = (pong.identifier + 1);
        } else {
            this.handleMessage(packet, channel);
        }

        if (MessageIdentifier.hasPacket(packet.getId())) {
            logger.debug("Handled internal packet with ID {} ({})", MessageIdentifier.getName(packet.getId()), packet.getId());
        } else {
            logger.debug("Sent packet with ID {} to session handler", packet.getId());
        }
    }

    public final void update() {
        long currentTime = System.currentTimeMillis();

        // Send next packets in the send queue
        if (!sendQueue.isEmpty() && this.packetsSentThisSecond < RakNet.getMaxPacketsPerSecond()) {
            if (currentTime % 10000 < 2) {
                int queueSize = this.sendQueue.size();
                if (queueSize > 100) {
                    logger.warn("the logicSession[{}] sendQueue size[{}]!", this.address, queueSize);
                }
            }
            ArrayList<McEncapsulatedPacket> send = new ArrayList<McEncapsulatedPacket>();
            int sendLength = CustomPacket.DUMMY_SIZE;
            int logSendCount = 0;
            // Add packets
            Iterator<EncapsulatedPacket> sendQueueI = sendQueue.iterator();
            while (sendQueueI.hasNext()) {
                // Make sure the packet will not cause an overflow
                EncapsulatedPacket encapsulated = sendQueueI.next();
                if (encapsulated == null) {
                    sendQueueI.remove();
                    continue;
                }
                sendLength += encapsulated.calculateSize();
                logSendCount++;
                // Add the packet and remove it from the queue
                if (encapsulated instanceof McEncapsulatedPacket) {
                    send.add((McEncapsulatedPacket)encapsulated);
                } else {
                    logger.error("can not send EncapsulatedPacket package!");
                }
                sendQueueI.remove();

                if (sendLength > this.getMaxSplitPackageSize()) {
                    if (logSendCount == 1) {
                        logger.debug("logicSession update, the logSendCount[{}], the sendLength[{}]", logSendCount, sendLength);
                    }
                    break;
                }
            }

            // Send packet
            if (send.size() > 0) {
                this.sendMcEncapsulatedPacket(send, true);
            }
        }

        if (!retrySendQueue.isEmpty() && this.packetsSentThisSecond < RakNet.getMaxPacketsPerSecond()) {
            ArrayList<DecoPackage> retryQueue = new ArrayList<DecoPackage>();
            Iterator<DecoPackage> retryQueueI = retrySendQueue.iterator();
            while (retryQueueI.hasNext()) {
                DecoPackage tcpCustomPackage = retryQueueI.next();
                if (tcpCustomPackage == null) {
                    retryQueueI.remove();
                    continue;
                } else if (tcpCustomPackage.getRetryCounts() > 3) {
                    retryQueueI.remove();
                    continue;
                }
                retryQueue.add(tcpCustomPackage);
            }
            if (retryQueue.size() > 0) {
                for (DecoPackage tcpCustomPackage : retryQueue) {
                    if (tcpCustomPackage == null) {
                        continue;
                    }
                    this.sendDecoPackage(tcpCustomPackage);
                }
            }
        }

        //切换乒乓缓存的索引，强制清除过期的数据
        if (currentTime - this.lastRecoverySendTime >= RakNet.RECOVERY_SEND_INTERVAL) {
            recoveryQueue[recoveryQueueIndex].clear();
            recoveryQueueIndex = 1 - recoveryQueueIndex;
        }

        // Reset packet data
        if (currentTime - this.lastPacketCounterResetTime >= 1000L) {
            this.packetsSentThisSecond = 0;
            this.packetsReceivedThisSecond = 0;
            this.lastPacketCounterResetTime = currentTime;
        }
    }

    public Object getContext() {
        return context;
    }

    public void setContext(Object context) {
        this.context = context;
    }

    public abstract void onAcknowledge(Record record, EncapsulatedPacket packet);

    public abstract void onNotAcknowledge(Record record, EncapsulatedPacket packet);

    public abstract void handleMessage(RakNetPacket packet, int channel);


}
