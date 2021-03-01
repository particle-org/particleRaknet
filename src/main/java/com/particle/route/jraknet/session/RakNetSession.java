/*
 *       _   _____            _      _   _          _
 *      | | |  __ \          | |    | \ | |        | |
 *      | | | |__) |   __ _  | | __ |  \| |   ___  | |_
 *  _   | | |  _  /   / _` | | |/ / | . ` |  / _ \ | __|
 * | |__| | | | \ \  | (_| | |   <  | |\  | |  __/ | |_
 *  \____/  |_|  \_\  \__,_| |_|\_\ |_| \_|  \___|  \__|
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016-2018 Trent "Whirvis" Summerlin
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.particle.route.jraknet.session;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.ConnectionType;
import com.particle.route.jraknet.protocol.MessageIdentifier;
import com.particle.route.jraknet.protocol.Reliability;
import com.particle.route.jraknet.protocol.message.CustomPacket;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.protocol.message.SendedEncapsulatePacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Acknowledge;
import com.particle.route.jraknet.protocol.message.acknowledge.AcknowledgeType;
import com.particle.route.jraknet.protocol.message.acknowledge.Record;
import com.particle.route.jraknet.protocol.status.ConnectedPing;
import com.particle.route.jraknet.protocol.status.ConnectedPong;
import com.particle.route.jraknet.timing.ProxyTiming;
import com.particle.route.jraknet.util.map.concurrent.ConcurrentIntMap;
import io.netty.util.internal.ConcurrentSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

/**
 * This class is used to easily manage connections in RakNet.
 *
 * @author Trent "Whirvis" Summerlin
 */
public abstract class RakNetSession implements UnumRakNetPeer, GeminusRakNetPeer {

	private static final Logger log = LoggerFactory.getLogger(RakNetSession.class);

	// Session data
	private String loggerName;
	private final ConnectionType connectionType;
	private final long guid;
	private final int maximumTransferUnit;
	private final Channel channel;
	private final InetSocketAddress address;
	private RakNetState state;

	// Timing
	//每秒发包量
	private int packetsSentThisSecond;
	//每秒收包量
	private AtomicInteger packetsReceivedThisSecond = new AtomicInteger(0);
	//每秒重发量
	private long packetsResendThisSecond;
	//每秒丢包量
	private long packetsDropedThisSecond;


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
	/**
	 * Cleared by <code>RakNetServerSession</code> to help make sure the
	 * <code>ID_DISCONNECTION_NOTIFICATION</code> packet is sent out
	 */
	protected final ConcurrentLinkedQueue<EncapsulatedPacket> sendQueue;
	protected final AtomicInteger sendQueueSize;
	private final ConcurrentHashMap<EncapsulatedPacket, Integer> ackReceiptPackets;

	//发包窗口大小
//	private static final int SENDING_QUEUE_SIZE = 256;
//	private static final int SENDING_QUEUE_QUICK_INDEX_MASK = 255;
	//发包缓冲
//	private final SendedEncapsulatePacket[] sendingWindow = new SendedEncapsulatePacket[SENDING_QUEUE_SIZE];
//	private int sendingQueueWriteIndex = 0;
//	private int sendingQueueWaitingIndex = 0;

    private SendingWindowQueue sendingWindow = new SendingWindowQueue();

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
	 * Constructs a <code>RakNetSession</code> with the specified globally
	 * unique ID, maximum transfer unit, <code>Channel</code>, and address.
	 *
	 * @param connectionType
	 *            the connection type of the session.
	 * @param guid
	 *            the globally unique ID.
	 * @param maximumTransferUnit
	 *            the maximum transfer unit.
	 * @param channel
	 *            the <code>Channel</code>.
	 * @param address
	 *            the address.
	 */
	public RakNetSession(ConnectionType connectionType, long guid, int maximumTransferUnit, Channel channel,
			InetSocketAddress address) {
		// Session data
		this.loggerName = "session #" + guid;
		this.connectionType = connectionType;
		this.guid = guid;
		this.maximumTransferUnit = maximumTransferUnit;
		this.channel = channel;
		this.address = address;
		this.state = RakNetState.DISCONNECTED;

		// Timing
		this.lastPacketReceiveTime = System.currentTimeMillis();

		// Packet data
		this.reliablePackets = new ConcurrentSet<Integer>();
		this.splitQueue = new ConcurrentIntMap<SplitPacket>();
		this.sendQueue = new ConcurrentLinkedQueue<EncapsulatedPacket>();
		this.sendQueueSize = new AtomicInteger(0);
//		this.recoveryQueue = new ConcurrentIntMap<SendedEncapsulatePacket>();
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
	}

	/**
	 * @return the connection type of the session.
	 */
	public final ConnectionType getConnectionType() {
		return this.connectionType;
	}

	/**
	 * @return the session's globally unique ID.
	 */
	public final long getGloballyUniqueId() {
		return this.guid;
	}

	/**
	 * @return the session's address.
	 */
	public final InetSocketAddress getAddress() {
		return this.address;
	}

	/**
	 * @return the session's <code>InetAddress</code>.
	 */
	public final InetAddress getInetAddress() {
		return address.getAddress();
	}

	/**
	 * @return the session's port.
	 */
	public final int getInetPort() {
		return address.getPort();
	}

	/**
	 * @return the session's maximum transfer unit.
	 */
	public int getMaximumTransferUnit() {
		return this.maximumTransferUnit;
	}

	/**
	 * @return the session's current state.
	 */
	public RakNetState getState() {
		return this.state;
	}

	/**
	 * Sets the session's current state.
	 *
	 * @param state
	 *            the new state.
	 */
	public void setState(RakNetState state) {
		this.state = state;
		log.debug(loggerName + "set state to " + state);
	}

	/**
	 * @return the amount of packets sent this second.
	 */
	public int getPacketsSentThisSecond() {
		return this.packetsSentThisSecond;
	}

	/**
	 * @return the amount of packets received this second.
	 */
	public int getPacketsReceivedThisSecond() {
		return this.packetsReceivedThisSecond.get();
	}

	/**
	 * @return the last time a packet was sent by the session.
	 */
	public long getLastPacketSendTime() {
		return this.lastPacketSendTime;
	}

	/**
	 * @return the last time a packet was received from the session.
	 */
	public long getLastPacketReceiveTime() {
		return this.lastPacketReceiveTime;
	}

	/**
	 * Bumps the message index and returns the new one, this should only be
	 * called by the <code>SplitPacket</code> and <code>RakNetSession</code>
	 * classes.
	 *
	 * @return the new message index.
	 */
	protected int bumpMessageIndex() {
		return this.messageIndex++;
	}

	/**
	 * Enables/disables latency detection, when disabled the latency will always
	 * return -1. If the session is not yet in the keep alive state then the
	 * packets needed to detect the latency will not be sent until then.
	 *
	 * @param enabled
	 *            whether or not latency detection is enabled
	 */
	public void enableLatencyDetection(boolean enabled) {
		this.latencyEnabled = enabled;
		this.latency = (enabled ? this.latency : -1);
		this.pongsReceived = (enabled ? this.pongsReceived : 0);
	}

	/**
	 * @return whether or not latency detection is enabled.
	 */
	public boolean latencyDetectionEnabled() {
		return this.latencyEnabled;
	}

	/**
	 * @return the average latency for the session.
	 */
	public long getLatency() {
		return this.latency;
	}

	/**
	 * @return the last latency for the session.
	 */
	public long getLastLatency() {
		return this.lastLatency;
	}

	/**
	 * @return the lowest recorded latency for the session.
	 */
	public long getLowestLatency() {
		return this.lowestLatency;
	}

	/**
	 * @return the highest recorded latency for the session.
	 */
	public long getHighestLatency() {
		return this.highestLatency;
	}

	@Override
	public final EncapsulatedPacket sendMessage(Reliability reliability, int channel, Packet packet)
			throws InvalidChannelException {
		// Make sure channel doesn't exceed RakNet limit
		if (channel >= RakNet.MAX_CHANNELS) {
			throw new InvalidChannelException();
		}

		// Set packet properties
		EncapsulatedPacket encapsulated = new EncapsulatedPacket();
		encapsulated.reliability = reliability;
		encapsulated.orderChannel = (byte) channel;
		encapsulated.payload = packet;
		if (reliability.isReliable()) {
			encapsulated.messageIndex = this.bumpMessageIndex();
		}
		if (reliability.isOrdered() || reliability.isSequenced()) {
			encapsulated.orderIndex = (reliability.isOrdered() ? this.orderSendIndex[channel]++
					: this.sequenceSendIndex[channel]++);
		}

		// Do we need to split the packet?
		if (SplitPacket.needsSplit(reliability, packet, this.maximumTransferUnit)) {
			encapsulated.splitId = ++this.splitId % 65536;
			for (EncapsulatedPacket split : SplitPacket.splitPacket(this, encapsulated)) {
				sendQueue.add(split);
				sendQueueSize.incrementAndGet();
			}
		} else {
			sendQueue.add(encapsulated);
			sendQueueSize.incrementAndGet();
		}

		/*
		 * We return a copy of the encapsulated packet because if a single
		 * variable is modified in the encapsulated packet before it is sent,
		 * the whole API could break.
		 */
		return encapsulated.clone();
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

	/**
	 * Used to tell the session to assign the given packets to an ACK receipt to
	 * be used for when an ACK or NACK arrives.
	 *
	 * @param packets
	 *            the packets.
	 */
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
	 * Sends a raw message.
	 *
	 * @param packet
	 *            The packet to send.
	 */
	public final void sendRawMessage(Packet packet) {
		channel.writeAndFlush(new DatagramPacket(packet.buffer(), this.address));
	}

	/**
	 * Sends a raw message
	 *
	 * @param buf
	 *            the buffer to send.
	 */
	public final void sendRawMessage(ByteBuf buf) {
		channel.writeAndFlush(new DatagramPacket(buf, this.address));
	}

	/**
	 * Sends a <code>CustomPacket</code> with the specified
	 * <code>EncapsulatedPacket</code>'s.
	 *
	 * @param encapsulated
	 *            the encapsulated packets to send.
	 * @return the sequence number of the <code>CustomPacket</code>.
	 */
	private final int sendCustomPacket(ArrayList<EncapsulatedPacket> encapsulated) {
		// Create CustomPacket
		CustomPacket custom = new CustomPacket();
		custom.sequenceNumber = this.sendSequenceNumber++;
		custom.messages = encapsulated;
		custom.session = this;
		custom.encode();

		this.sendingWindow.offer(new SendedEncapsulatePacket(custom.sequenceNumber, custom.messages));

		this.doSendCustomPacket(custom);

		//若不需要ACK，则直接标记
		custom.removeUnreliables();
		if (custom.messages.size() == 0) {
			this.sendingWindow.remove(custom.sequenceNumber);
		}

		return custom.sequenceNumber;
	}

	private final int resendCustomPacket(ArrayList<EncapsulatedPacket> encapsulated, int sequenceNumber) {
		// Create CustomPacket
		CustomPacket custom = new CustomPacket();
		custom.sequenceNumber = sequenceNumber;
		custom.messages = encapsulated;
		custom.session = this;
		custom.encode();

		this.doSendCustomPacket(custom);

		return custom.sequenceNumber;
	}

	private void doSendCustomPacket(CustomPacket custom) {
		// Send packet
		this.sendRawMessage(custom);

		// Update packet data
		this.packetsSentThisSecond++;
		this.lastPacketSendTime = System.currentTimeMillis();
	}

	/**
	 * 采用环形队列后，不允许直接发包，避免环形队列溢出
	 */
//	private final int sendCustomPacket(EncapsulatedPacket[] encapsulated, boolean updateRecoveryQueue) {
//		ArrayList<EncapsulatedPacket> encapsulatedArray = new ArrayList<EncapsulatedPacket>();
//		for (EncapsulatedPacket message : encapsulated) {
//			encapsulatedArray.add(message);
//		}
//		return this.sendCustomPacket(encapsulatedArray, updateRecoveryQueue);
//	}

	/**
	 * Sends an <code>Acknowledge</code> packet with the specified type and
	 * <code>Record</code>s.
	 *
	 * @param type
	 *            the type of the <code>Acknowledge</code> packet.
	 * @param records
	 *            the <code>Record</code>s to send.
	 */
	private final void sendAcknowledge(AcknowledgeType type, Record... records) {
		// Create Acknowledge packet
		Acknowledge acknowledge = new Acknowledge(type);
		for (Record record : records) {
			acknowledge.records.add(record);
		}
		acknowledge.encode();
		this.sendRawMessage(acknowledge);

		// Update packet data
		this.lastPacketSendTime = System.currentTimeMillis();
	}

	/**
	 * Handles a <code>CustomPacket</code>.
	 *
	 * @param custom
	 *            the <code>CustomPacket</code> to handle.
	 */
	public final void handleCustom(CustomPacket custom) {
		// Update packet data
		this.packetsReceivedThisSecond.addAndGet(1);

		/*
		 * There are three important things to note here:
		 */

		/*
		 * 1. The reason we subtract one from the difference is because the last
		 * sequence number we received should always be one less than the next
		 * one
		 */

		/*
		 * 2. The reason we add one to the last sequence number to the record
		 * when the difference is bigger than one is because we have already
		 * received that record, this is also the same reason we subtract one
		 * from the CustomPacket's sequence number even when the difference is
		 * not greater than one
		 */

		/*
		 * 3. We always generate the NACK response first because the previous
		 * sequence number data would be destroyed, making it impossible to
		 * generate it
		 */

		// Generate NACK queue if needed
		int difference = custom.sequenceNumber - this.receiveSequenceNumber - 1;
		if (difference > 0) {
			if (difference > 1) {
				this.sendAcknowledge(AcknowledgeType.NOT_ACKNOWLEDGED,
						new Record(this.receiveSequenceNumber + 1, custom.sequenceNumber - 1));
			} else {
				this.sendAcknowledge(AcknowledgeType.NOT_ACKNOWLEDGED, new Record(custom.sequenceNumber - 1));
			}
		}

		// Only handle if it is a newer packet
		// TODO: 2019/7/23 上面这句话好像并没有作用
		this.receiveSequenceNumber = custom.sequenceNumber;
		for (EncapsulatedPacket encapsulated : custom.messages) {
			this.handleEncapsulated(encapsulated);
		}

		// Update packet data
		this.lastPacketReceiveTime = System.currentTimeMillis();

		// Send ACK
		// 处理完成再发送ACK，因为处理过程中可能有异常
		this.sendAcknowledge(AcknowledgeType.ACKNOWLEDGED, new Record(custom.sequenceNumber));
	}

	/**
	 * Handles an <code>Acknowledge</code> packet and responds accordingly.
	 *
	 * @param acknowledge
	 *            the <code>Acknowledge</code> packet to handle.
	 */
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

				//处理ACK
                this.sendingWindow.remove(recordIndex);
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

					/*
					 * We only call onNotAcknowledge() for unreliable packets,
					 * as they can be lost. However, reliable packets will
					 * always eventually be received.
					 */
					if (recordIndex == packetRecordIndex && !packet.reliability.isReliable()) {
						this.onNotAcknowledge(record, packet);
						packet.ackRecord = null;
						ackReceiptPackets.remove(packet);
					}
				}

                //处理ACK
                SendedEncapsulatePacket removed = this.sendingWindow.remove(recordIndex);

				if (removed != null) {
					this.sendQueue.addAll(removed.getPackets());
					this.sendQueueSize.addAndGet(removed.getPackets().size());
				}

			}
		}

		// Update packet data
		this.lastPacketReceiveTime = System.currentTimeMillis();
	}

	/**
	 * Handles an <code>EncapsulatedPacket</code> and makes sure all the data is
	 * handled correctly.
	 *
	 * @param encapsulated
	 *            the <code>EncapsualtedPacket</code> to handle.
	 */
	private final void handleEncapsulated(EncapsulatedPacket encapsulated) {
		Reliability reliability = encapsulated.reliability;

		// Put together split packet
		if (encapsulated.split == true) {
			if (!splitQueue.containsKey(encapsulated.splitId)) {
				// Prevent queue from overflowing
				if (splitQueue.size() + 1 > RakNet.MAX_SPLITS_PER_QUEUE) {
					// Remove unreliable packets from the queue
					Iterator<SplitPacket> splitQueueI = splitQueue.values().iterator();
					while (splitQueueI.hasNext()) {
						SplitPacket splitPacket = splitQueueI.next();
						if (!splitPacket.getReliability().isReliable()) {
							splitQueueI.remove();
						}
					}

					// The queue is filled with reliable packets
					if (splitQueue.size() + 1 > RakNet.MAX_SPLITS_PER_QUEUE) {
						throw new SplitQueueOverloadException();
					}
				}
				splitQueue.put(encapsulated.splitId,
						new SplitPacket(encapsulated.splitId, encapsulated.splitCount, encapsulated.reliability));
			}


			SplitPacket splitPacket = splitQueue.get(encapsulated.splitId);
			Packet finalPayload = splitPacket.update(encapsulated);
			if (finalPayload == null) {
				return; // Do not handle, the split packet is not complete
			}

			/*
			 * It is safe to set the payload here because the old payload is no
			 * longer needed and split EncapsulatedPackets share the exact same
			 * data except for split data and payload.
			 */
			encapsulated.payload = finalPayload;
			splitQueue.remove(encapsulated.splitId);
		}

		//todo: 重新设计记录机制
		// Make sure we are not handling a duplicate
		if (reliability.isReliable()) {
			if (reliablePackets.contains(encapsulated.messageIndex)) {
				return; // Do not handle, it is a duplicate
			}
			reliablePackets.add(encapsulated.messageIndex);

			// 清除过期的数据包，避免玩家挂机挂蹦服务端
			/*
			 * 这里的消耗预计在0.2%左右，可以考虑优化
			 */
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
//					log.error("Missing packet! order index {}!", orderReceiveIndex[orderChannel]);
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
	}

	/**
	 * Handles an internal packet related to RakNet, if the ID is unrecognized
	 * it is passed on to the underlying session class.
	 *
	 * @param channel
	 *            the channel the packet was sent on.
	 * @param packet
	 *            the packet.
	 */
	private final void handleMessage0(int channel, RakNetPacket packet) {
		short packetId = packet.getId();

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
	}

	/**
	 * Updates the session.
	 */
	public final void update() {
		long currentTime = System.currentTimeMillis();

		/*
		重发数据包
		重发条件：
		1）重发次数没有超过阈值
		2）上次重发间隔小于阈值
		 */
		SendingWindowQueue.SendingIterator iterator = this.sendingWindow.getIterator();
		int count = 0;
		SendedEncapsulatePacket packet = null;
		while (((packet = iterator.next()) != null) && count++ < RakNet.RECOVERY_SEND_AMOUNT_PRESEND) {
			if (currentTime - packet.getLastSendTime() >= (RakNet.RECOVERY_SEND_INTERVAL << packet.getRetryTime())) {
				if (packet.getRetryTime() < RakNet.RECOVERY_SEND_MAX_TIME) {

					this.resendCustomPacket(packet.getPackets(), packet.getIndex());
					packet.setLastSendTime(currentTime);
					packet.increaseRetryTime();

					this.packetsResendThisSecond++;
				} else {
					this.sendingWindow.remove(packet.getIndex());

					this.packetsDropedThisSecond++;
				}
			}
		}

		/*
		发送数据包
		发送条件：
		1）发送队列不为空
		2）环形队列有空余位置
		3）每次Update发送一个大包
		 */
		if (!sendQueue.isEmpty() && !this.sendingWindow.isFull()) {
			ArrayList<EncapsulatedPacket> send = new ArrayList<EncapsulatedPacket>();
			int sendLength = CustomPacket.DUMMY_SIZE;

			//将小包拼接成大包后发送，拼接的大包小于mtu
			Iterator<EncapsulatedPacket> sendQueueI = sendQueue.iterator();
			while (sendQueueI.hasNext()) {
				// Make sure the packet will not cause an overflow
				EncapsulatedPacket encapsulated = sendQueueI.next();
				if (encapsulated == null) {
					sendQueueI.remove();
					sendQueueSize.decrementAndGet();
					continue;
				}
				sendLength += encapsulated.calculateSize();
				if (sendLength > this.maximumTransferUnit) {
					break;
				}

				send.add(encapsulated);
				sendQueueI.remove();
				sendQueueSize.decrementAndGet();
			}

			// Send packet
			if (send.size() > 0) {
				this.sendCustomPacket(send);
			}
		}

		/**
		 * 检查发包队列长度，过长的队列会导致玩家被踢出游戏
		 */
		if (sendQueueSize.get() > RakNet.MAX_SENDQUEUE_SIZE) {
			log.error("Session {} sending queue too long ({})!", guid, sendQueueSize.get());

			this.closeConnection();

			return;
		}

		// Send ping to detect latency if it is enabled
		if (this.latencyEnabled == true && currentTime - this.lastPingSendTime >= RakNet.PING_SEND_INTERVAL
				&& state.equals(RakNetState.CONNECTED)) {
			ConnectedPing ping = new ConnectedPing();
			ping.identifier = this.latencyIdentifier++;
			ping.encode();

			this.sendMessage(Reliability.UNRELIABLE, ping);
			this.lastPingSendTime = currentTime;
		}

		// Make sure the client is still connected
		if (currentTime - this.lastPacketReceiveTime >= RakNet.DETECTION_SEND_INTERVAL
				&& currentTime - this.lastKeepAliveSendTime >= RakNet.DETECTION_SEND_INTERVAL
				&& state.equals(RakNetState.CONNECTED)) {
			this.sendMessage(Reliability.UNRELIABLE, MessageIdentifier.ID_DETECT_LOST_CONNECTIONS);
			this.lastKeepAliveSendTime = currentTime;
		}

		// Client timed out
		if (currentTime - this.lastPacketReceiveTime >= RakNet.SESSION_TIMEOUT) {
			throw new TimeoutException();
		}

		// 每分钟的统计
		if (currentTime - this.lastPacketCounterResetTime >= 1000L) {
			ProxyTiming proxyTiming = ProxyTiming.getInstance();
			proxyTiming.recorderServerPacketSendCounting(this.packetsSentThisSecond);
			proxyTiming.recorderServerPacketReceivedCounting(this.packetsReceivedThisSecond.getAndSet(0));
			proxyTiming.recorderServerPacketResendCounting(this.packetsResendThisSecond);
			proxyTiming.recorderServerPacketDropCounting(this.packetsDropedThisSecond);

			this.packetsSentThisSecond = 0;
			this.packetsResendThisSecond = 0;
			this.packetsDropedThisSecond = 0;
			this.lastPacketCounterResetTime = currentTime;
		}
	}

	public Object getContext() {
		return context;
	}

	public void setContext(Object context) {
		this.context = context;
	}

	/**
	 * This function is called when a acknowledge receipt is received for the
	 * packet.
	 *
	 * @param record
	 *            the received record.
	 * @param packet
	 *            the received packet.
	 */
	public abstract void onAcknowledge(Record record, EncapsulatedPacket packet);

	/**
	 * This function is called when a not acknowledged receipt is received for
	 * the packet.
	 *
	 * @param record
	 *            the lost record.
	 * @param packet
	 *            the lost packet.
	 */
	public abstract void onNotAcknowledge(Record record, EncapsulatedPacket packet);

	/**
	 * This function is called when a packet is received by the session.
	 *
	 * @param packet
	 *            the packet to handle.
	 * @param channel
	 *            the packet the channel was sent on.
	 */
	public abstract void handleMessage(RakNetPacket packet, int channel);

	public abstract void closeConnection();
}
