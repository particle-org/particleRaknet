/*
 *       _   _____            _      _   _          _   
 *      | | |  __ \          | |    | \ | |        | |  
 *      | | | |__) |   __ _  | | __ |  \| |   ___  | |_ 
 *  _   | | |  _  /   / _` | | |/ / | . ` |  / _ \ | __|
 * | |__| | | | \ \  | (_| | |   <  | |\  | |  __/ | |_ 
 *  \____/  |_|  \_\  \__,_| |_|\_\ |_| \_|  \___|  \__|
 *                                                  
 * the MIT License (MIT)
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
 * the above copyright notice and this permission notice shall be included in all
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
package com.particle.route.jraknet.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNet;
import com.particle.route.jraknet.RakNetException;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.client.RakNetClient;
import com.particle.route.jraknet.identifier.Identifier;
import com.particle.route.jraknet.protocol.ConnectionType;
import com.particle.route.jraknet.protocol.MessageIdentifier;
import com.particle.route.jraknet.protocol.Reliability;
import com.particle.route.jraknet.protocol.message.CustomPacket;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Acknowledge;
import com.particle.route.jraknet.protocol.status.NeteaseUnconnectedPing;
import com.particle.route.jraknet.protocol.status.NeteaseUnconnectedPong;
import com.particle.route.jraknet.protocol.status.UnconnectedPing;
import com.particle.route.jraknet.protocol.status.UnconnectedPong;
import com.particle.route.jraknet.timing.ProxyTiming;
import com.particle.route.jraknet.util.RakNetUtils;
import com.particle.route.jraknet.session.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.particle.route.jraknet.protocol.login.ConnectionBanned;
import com.particle.route.jraknet.protocol.login.IncompatibleProtocol;
import com.particle.route.jraknet.protocol.login.OpenConnectionRequestOne;
import com.particle.route.jraknet.protocol.login.OpenConnectionRequestTwo;
import com.particle.route.jraknet.protocol.login.OpenConnectionResponseOne;
import com.particle.route.jraknet.protocol.login.OpenConnectionResponseTwo;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

/**
 * Used to easily create servers using the RakNet protocol.
 *
 * @author Trent "Whirvis" Summerlin
 */
public class RakNetServer implements GeminusRakNetPeer, RakNetServerListener {

	private static final Logger log = LoggerFactory.getLogger(RakNetServer.class);

	private static final int SESSION_GROUP_SIZE = 2;
	private static final int SESSION_GROUP_SIZE_MASK = 1;


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
	private final ConcurrentLinkedQueue<RakNetServerListener> listeners;
	private Thread serverThread;

	// Networking data
	private final Bootstrap bootstrap;
	private final EventLoopGroup group;
	private final RakNetServerHandler handler;

	// Session data
	private Channel channel;
	private volatile boolean running;
	private final ConcurrentHashMap<InetSocketAddress, RakNetClientSession> sessions;

	private List<List<RakNetClientSession>> sessionGroup;

	/**
	 * Constructs a <code>RakNetServer</code> with the specified port, maximum
	 * amount connections, maximum transfer unit, and <code>Identifier</code>.
	 *
	 * @param port
	 *            the server port.
	 * @param maxConnections
	 *            the maximum amount of connections.
	 * @param maximumTransferUnit
	 *            the maximum transfer unit.
	 * @param identifier
	 *            the <code>Identifier</code>.
	 */
	public RakNetServer(String address, int port, int maxConnections, int maximumTransferUnit, Identifier identifier) {
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
		this.listeners = new ConcurrentLinkedQueue<RakNetServerListener>();

		// Initiate bootstrap data
		this.bootstrap = new Bootstrap();
		this.group = new NioEventLoopGroup();
		this.handler = new RakNetServerHandler(this);

		// Create session map
		this.sessions = new ConcurrentHashMap<InetSocketAddress, RakNetClientSession>();

		// Check maximum transfer unit
		if (this.maximumTransferUnit < RakNet.MINIMUM_MTU_SIZE) {
			throw new IllegalArgumentException(
					"Maximum transfer unit can be no smaller than " + RakNet.MINIMUM_MTU_SIZE);
		}

		//初始化session组
		this.sessionGroup = new ArrayList<>();
		for (int i = 0; i < SESSION_GROUP_SIZE; i++) {
			sessionGroup.add(Collections.synchronizedList(new LinkedList<>()));
		}
	}

	/**
	 * Constructs a <code>RakNetServer</code> with the specified port, maximum
	 * amount connections, and maximum transfer unit.
	 *
	 * @param port
	 *            the server port.
	 * @param maxConnections
	 *            the maximum amount of connections.
	 * @param maximumTransferUnit
	 *            the maximum transfer unit.
	 */
	public RakNetServer(String address, int port, int maxConnections, int maximumTransferUnit) {
		this(address, port, maxConnections, maximumTransferUnit, null);
	}

	/**
	 * Constructs a <code>RakNetServer</code> with the specified port and
	 * maximum amount of connections.
	 *
	 * @param port
	 *            the server port.
	 * @param maxConnections
	 *            the maximum amount of connections.
	 */
	public RakNetServer(String address, int port, int maxConnections) {
		this(address, port, maxConnections, RakNetUtils.getMaximumTransferUnit());
	}

	/**
	 * Constructs a <code>RakNetServer</code> with the specified port, maximum
	 * amount connections, and <code>Identifier</code>.
	 *
	 * @param port
	 *            the server port.
	 * @param maxConnections
	 *            the maximum amount of connections.
	 * @param identifier
	 *            the <code>Identifier</code>.
	 */
	public RakNetServer(String address, int port, int maxConnections, Identifier identifier) {
		this(address, port, maxConnections);
		this.identifier = identifier;
	}

	/**
	 * @return the server's networking protocol version.
	 */
	public final int getProtocolVersion() {
		return RakNet.SERVER_NETWORK_PROTOCOL;
	}

	/**
	 * @return the server's globally unique ID.
	 */
	public final long getGloballyUniqueId() {
		return this.guid;
	}

	/**
	 * @return the server's timestamp.
	 */
	public final long getTimestamp() {
		return (System.currentTimeMillis() - this.timestamp);
	}

	/**
	 * @return the port the server is bound to.
	 */
	public final int getPort() {
		return this.port;
	}

	/**
	 * @return the maximum amount of connections the server can handle at once.
	 */
	public final int getMaxConnections() {
		return this.maxConnections;
	}

	/**
	 * @return the maximum transfer unit.
	 */
	public final int getMaximumTransferUnit() {
		return this.maximumTransferUnit;
	}

	/**
	 * Enables/disables server broadcasting.
	 * 
	 * @param enabled
	 *            whether or not the server will broadcast.
	 */
	public final void setBroadcastingEnabled(boolean enabled) {
		this.broadcastingEnabled = enabled;
		log.info((enabled ? "Enabled" : "Disabled") + " broadcasting");
	}

	/**
	 * @return <code>true</code> if broadcasting is enabled.
	 */
	public final boolean isBroadcastingEnabled() {
		return this.broadcastingEnabled;
	}

	/**
	 * @return the identifier the server uses for discovery.
	 */
	public final Identifier getIdentifier() {
		return this.identifier;
	}

	/**
	 * Sets the server's identifier used for discovery.
	 * 
	 * @param identifier
	 *            the new identifier.
	 */
	public final void setIdentifier(Identifier identifier) {
		this.identifier = identifier;
		log.info("Set identifier to \"" + identifier.build() + "\"");
	}

	/**
	 * @return the thread the server is running on if it was started using
	 *         <code>startThreaded()</code>.
	 */
	public final Thread getThread() {
		return this.serverThread;
	}

	/**
	 * @return the server's listeners.
	 */
	public final RakNetServerListener[] getListeners() {
		return listeners.toArray(new RakNetServerListener[listeners.size()]);
	}

	/**
	 * Adds a listener to the server.
	 * 
	 * @param listener
	 *            the listener to add.
	 * @return the server.
	 */
	public final RakNetServer addListener(RakNetServerListener listener) {
		// Validate listener
		if (listener == null) {
			throw new NullPointerException("Listener must not be null");
		}
		if (listeners.contains(listener)) {
			throw new IllegalArgumentException("A listener cannot be added twice");
		}
		if (listener instanceof RakNetClient && !listener.equals(this)) {
			throw new IllegalArgumentException("A server cannot be used as a listener except for itself");
		}

		// Add listener
		listeners.add(listener);
		log.debug("Added listener " + listener.getClass().getName());

		return this;
	}

	/**
	 * Adds the server to its own set of listeners, used when extending the
	 * <code>RakNetServer</code> directly.
	 * 
	 * @return the server.
	 */
	public final RakNetServer addSelfListener() {
		this.addListener(this);
		return this;
	}

	/**
	 * Removes a listener from the server.
	 * 
	 * @param listener
	 *            the listener to remove.
	 * @return the server.
	 */
	public final RakNetServer removeListener(RakNetServerListener listener) {
		boolean hadListener = listeners.remove(listener);
		if (hadListener == true) {
			log.info("Removed listener " + listener.getClass().getName());
		} else {
			log.warn("Attempted to removed unregistered listener " + listener.getClass().getName());
		}
		return this;
	}

	/**
	 * Removes the server from its own set of listeners, used when extending the
	 * <code>RakNetServer</code> directly.
	 * 
	 * @return the server.
	 */
	public final RakNetServer removeSelfListener() {
		this.removeListener(this);
		return this;
	}

	/**
	 * @return the sessions connected to the server.
	 */
	public final RakNetClientSession[] getSessions() {
		return sessions.values().toArray(new RakNetClientSession[sessions.size()]);
	}

	/**
	 * @return the amount of sessions connected to the server.
	 */
	public final int getSessionCount() {
		return sessions.size();
	}

    public final int getUpdaterSessionCount() {
	    int count = 0;
        for (List<RakNetClientSession> rakNetClientSessions : this.sessionGroup) {
            count += rakNetClientSessions.size();
        }
        return count;
    }

	/**
	 * @param address
	 *            the address to check.
	 * @return true server has a session with the specified address.
	 */
	public final boolean hasSession(InetSocketAddress address) {
		return sessions.containsKey(address);
	}

	/**
	 * @param guid
	 *            the globally unique ID to check.
	 * @return <code>true</code> if the server has a session with the specified
	 *         globally unique ID.
	 */
	public final boolean hasSession(long guid) {
		for (RakNetClientSession session : sessions.values()) {
			if (session.getGloballyUniqueId() == guid) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @param address
	 *            the address of the session.
	 * @return a session connected to the server by their address.
	 */
	public final RakNetClientSession getSession(InetSocketAddress address) {
		return sessions.get(address);
	}

	/**
	 * @param guid
	 *            the globally unique ID of the session.
	 * @return a session connected to the server by their address.
	 */
	public final RakNetClientSession getSession(long guid) {
		for (RakNetClientSession session : sessions.values()) {
			if (session.getGloballyUniqueId() == guid) {
				return session;
			}
		}
		return null;
	}

	@Override
	public final EncapsulatedPacket sendMessage(long guid, Reliability reliability, int channel, Packet packet) {
		if (this.hasSession(guid)) {
			return this.getSession(guid).sendMessage(reliability, channel, packet);
		} else {
			throw new IllegalArgumentException("No such session with GUID");
		}
	}

	/**
	 * Removes a session from the server with the specified reason.
	 * 
	 * @param address
	 *            the address of the session.
	 * @param reason
	 *            the reason the session was removed.
	 */
	public final void removeSession(InetSocketAddress address, String reason) {
		if (sessions.containsKey(address)) {
			// Notify client of disconnection
			RakNetClientSession session = sessions.remove(address);
			session.sendMessage(Reliability.UNRELIABLE, MessageIdentifier.ID_DISCONNECTION_NOTIFICATION);

			// Notify API
			log.debug("Removed session with address " + address);
			if (session.getState() == RakNetState.CONNECTED) {
				for (RakNetServerListener listener : listeners) {
					listener.onClientDisconnect(session, reason);
				}
			} else {
				for (RakNetServerListener listener : listeners) {
					listener.onClientPreDisconnect(address, reason);
				}
			}

            this.sessionGroup.get(session.getAddress().hashCode() & SESSION_GROUP_SIZE_MASK).remove(session);
		} else {
			log.warn("Attempted to remove session that had not been added to the server");

            for (List<RakNetClientSession> rakNetClientSessions : this.sessionGroup) {
                for (int i = 0; i < rakNetClientSessions.size(); i++) {
                    if (rakNetClientSessions.get(i).getAddress().equals(address)) {
                        rakNetClientSessions.remove(i);

                        return;
                    }
                }
            }
		}
	}

	/**
	 * Removes a session from the server.
	 * 
	 * @param address
	 *            the address of the session.
	 */
	public final void removeSession(InetSocketAddress address) {
		this.removeSession(address, "Disconnected from server");
	}

	/**
	 * Removes a session from the server with the specified reason.
	 * 
	 * @param session
	 *            the session to remove.
	 * @param reason
	 *            the reason the session was removed.
	 */
	public final void removeSession(RakNetClientSession session, String reason) {
		this.removeSession(session.getAddress(), reason);
	}

	/**
	 * Removes a session from the server.
	 * 
	 * @param session
	 *            the session to remove.
	 */
	public final void removeSession(RakNetClientSession session) {
		this.removeSession(session, "Disconnected from server");
	}

	/**
	 * Blocks the address and disconnects all the clients on the address with
	 * the specified reason for the specified amount of time.
	 * 
	 * @param address
	 *            the address to block.
	 * @param reason
	 *            the reason the address was blocked.
	 * @param time
	 *            how long the address will blocked in milliseconds.
	 */
	public final void blockAddress(InetAddress address, String reason, long time) {
		for (InetSocketAddress clientAddress : sessions.keySet()) {
			if (clientAddress.getAddress().equals(address)) {
				this.removeSession(clientAddress, reason);
			}
		}
		handler.blockAddress(address, reason, time);
	}

	/**
	 * Blocks the address and disconnects all the clients on the address for the
	 * specified amount of time.
	 * 
	 * @param address
	 *            the address to block.
	 * @param time
	 *            how long the address will blocked in milliseconds.
	 */
	public final void blockAddress(InetAddress address, long time) {
		this.blockAddress(address, "Blocked", time);
	}

	/**
	 * Unblocks the specified address.
	 * 
	 * @param address
	 *            the address to unblock.
	 */
	public final void unblockAddress(InetAddress address) {
		handler.unblockAddress(address);
	}

	/**
	 * @param address
	 *            the address to check.
	 * @return <code>true</code> if the specified address is blocked.
	 */
	public final boolean addressBlocked(InetAddress address) {
		return handler.addressBlocked(address);
	}

	/**
	 * Called whenever the handler catches an exception in Netty.
	 * 
	 * @param address
	 *            the address that caused the exception.
	 * @param cause
	 *            the exception caught by the handler.
	 */
	protected final void handleHandlerException(InetSocketAddress address, Throwable cause) {
		// Remove session that caused the error
		if (this.hasSession(address)) {
			this.removeSession(address, cause.getClass().getName());
		}

		// Notify API
		log.warn("Handled exception " + cause.getClass().getName() + " caused by address " + address);
		for (RakNetServerListener listener : listeners) {
			listener.onHandlerException(address, cause);
		}
	}

	/**
	 * Handles a packet received by the handler.
	 * 
	 * @param packet
	 *            the packet to handle.
	 * @param sender
	 *            the address of the sender.
	 */
	protected final void handleMessage(RakNetPacket packet, InetSocketAddress sender) {
		short packetId = packet.getId();
		if (packetId == MessageIdentifier.ID_UNCONNECTED_PING) {
			// 针对网易ping包的特殊处理
			NeteaseUnconnectedPing ping = new NeteaseUnconnectedPing(packet);
			ping.decode();
			if (ping.failed()) {
				return; // Bad packet, ignore
			}

			// Make sure parameters match and that broadcasting is enabled
			if (this.broadcastingEnabled == true) {
				ServerPing pingEvent = new ServerPing(sender, identifier, ConnectionType.JRAKNET);
				for (RakNetServerListener listener : listeners) {
					listener.handlePing(pingEvent);
				}

				if (pingEvent.getIdentifier() != null) {
					NeteaseUnconnectedPong pong = new NeteaseUnconnectedPong();
					pong.pingID = ping.pingId;
					pong.serverID = this.pongId;
					pong.identifier = pingEvent.getIdentifier();

					pong.encode();
					if (!pong.failed()) {
						this.sendNettyMessage(pong, sender);
					} else {
						log.error(UnconnectedPong.class.getSimpleName() + " packet failed to encode");
					}
				}
			}
		} else if (packetId == MessageIdentifier.ID_UNCONNECTED_PING_OPEN_CONNECTIONS) {
			// 针对通用情况的处理
			UnconnectedPing ping = new UnconnectedPing(packet);
			ping.decode();
			if (ping.failed()) {
				return; // Bad packet, ignore
			}

			// Make sure parameters match and that broadcasting is enabled
			if ((sessions.size() < this.maxConnections || this.maxConnections < 0) && this.broadcastingEnabled == true) {
				ServerPing pingEvent = new ServerPing(sender, identifier, ping.connectionType);
				for (RakNetServerListener listener : listeners) {
					listener.handlePing(pingEvent);
				}

				if (ping.magic == true && pingEvent.getIdentifier() != null) {
					UnconnectedPong pong = new UnconnectedPong();
					pong.timestamp = ping.timestamp;
					pong.pongId = this.pongId;
					pong.identifier = pingEvent.getIdentifier();

					pong.encode();
					if (!pong.failed()) {
						this.sendNettyMessage(pong, sender);
					} else {
						log.error(UnconnectedPong.class.getSimpleName() + " packet failed to encode");
					}
				}
			}
		} else if (packetId == MessageIdentifier.ID_OPEN_CONNECTION_REQUEST_1) {
			OpenConnectionRequestOne connectionRequestOne = new OpenConnectionRequestOne(packet);
			connectionRequestOne.decode();

			if (sessions.containsKey(sender)) {
				if (sessions.get(sender).getState().equals(RakNetState.CONNECTED)) {
					this.removeSession(sender, "Client re-instantiated connection");
				}
			}

			if (connectionRequestOne.magic == true) {
				// Are there any problems?
				RakNetPacket errorPacket = this.validateSender(sender);
				if (errorPacket == null) {
				    //暂时关闭版本检查，兼容1.6
					if (false && connectionRequestOne.protocolVersion != this.getProtocolVersion()) {
						// Incompatible protocol
						IncompatibleProtocol incompatibleProtocol = new IncompatibleProtocol();
						incompatibleProtocol.networkProtocol = this.getProtocolVersion();
						incompatibleProtocol.serverGuid = this.guid;
						incompatibleProtocol.encode();
						this.sendNettyMessage(incompatibleProtocol, sender);
					} else {
						// Everything passed, one last check...
//						if (connectionRequestOne.maximumTransferUnit <= this.maximumTransferUnit) {
//
//						} else {
//							log.error("Client connection request one transfer unit too large, expect {}, actually {}.", this.maximumTransferUnit, connectionRequestOne.maximumTransferUnit);
//						}

						OpenConnectionResponseOne connectionResponseOne = new OpenConnectionResponseOne();
						connectionResponseOne.serverGuid = this.guid;
						connectionResponseOne.maximumTransferUnit =
								connectionRequestOne.maximumTransferUnit > this.maximumTransferUnit ?
										this.maximumTransferUnit : connectionRequestOne.maximumTransferUnit;
						connectionResponseOne.encode();

						log.debug("Client {} connect1 with transfer unit {}", sender.getAddress().getHostAddress(), connectionRequestOne.maximumTransferUnit);
						this.sendNettyMessage(connectionResponseOne, sender);
					}
				} else {
					this.sendNettyMessage(errorPacket, sender);
				}
			}
		} else if (packetId == MessageIdentifier.ID_OPEN_CONNECTION_REQUEST_2) {
			OpenConnectionRequestTwo connectionRequestTwo = new OpenConnectionRequestTwo(packet);
			connectionRequestTwo.decode();

			if (!connectionRequestTwo.failed() && connectionRequestTwo.magic == true) {
				// Are there any problems?
				RakNetPacket errorPacket = this.validateSender(sender);
				if (errorPacket == null) {
					OpenConnectionResponseTwo connectionResponseTwo = new OpenConnectionResponseTwo();
					connectionResponseTwo.serverGuid = this.guid;
					connectionResponseTwo.clientAddress = sender;
//						connectionResponseTwo.maximumTransferUnit = connectionRequestTwo.maximumTransferUnit;
					connectionResponseTwo.maximumTransferUnit =
							connectionRequestTwo.maximumTransferUnit > this.maximumTransferUnit ?
									this.maximumTransferUnit : connectionRequestTwo.maximumTransferUnit;
					connectionResponseTwo.encryptionEnabled = false;
					connectionResponseTwo.encode();

					log.debug("Client {} connect2 with transfer unit {}", sender.getAddress().getHostAddress(), connectionRequestTwo.maximumTransferUnit);

					if (!connectionResponseTwo.failed()) {
						// Call event
						for (RakNetServerListener listener : listeners) {
							listener.onClientPreConnect(sender);
						}

						// Create session
						RakNetClientSession clientSession = new RakNetClientSession(this,
																							System.currentTimeMillis(),
																							connectionRequestTwo.connectionType,
																							connectionRequestTwo.clientGuid,
																							connectionRequestTwo.maximumTransferUnit,
																							channel,
																							sender);
						sessions.put(sender, clientSession);


						//将新的session缓存到group中
						sessionGroup.get(clientSession.getAddress().hashCode() & SESSION_GROUP_SIZE_MASK).add(clientSession);

						log.info("Client {} connect with session {}", sender.getAddress().getHostAddress(), clientSession.getGloballyUniqueId());

						// Send response, we are ready for login
						this.sendNettyMessage(connectionResponseTwo, sender);
					} else {
						log.error("Client connection response two packet encode fail.");
					}
				} else {
					this.sendNettyMessage(errorPacket, sender);
				}
			}
		} else if (packetId >= MessageIdentifier.ID_CUSTOM_0 && packetId <= MessageIdentifier.ID_CUSTOM_F) {
			if (sessions.containsKey(sender)) {
				CustomPacket custom = new CustomPacket(packet);
				custom.decode();

				RakNetClientSession session = sessions.get(sender);
				session.handleCustom(custom);
			}
		} else if (packetId == Acknowledge.ACKNOWLEDGED || packetId == Acknowledge.NOT_ACKNOWLEDGED) {
			if (sessions.containsKey(sender)) {
				Acknowledge acknowledge = new Acknowledge(packet);
				acknowledge.decode();

				RakNetClientSession session = sessions.get(sender);
				session.handleAcknowledge(acknowledge);
			}
		}

		if (!MessageIdentifier.hasRaknetPackage(packet.getId())) {
			// 另外处理 handshake和static的相关信息，发送给listener处理
			for (RakNetServerListener listener : listeners) {
				listener.handleRawMessage(packet, sender);
			}
		}
	}

	/**
	 * Validates the sender during login to make sure there are no problems.
	 * 
	 * @param sender
	 *            the address of the packet sender.
	 * @return the packet to respond with if there was an error.
	 */
	private final RakNetPacket validateSender(InetSocketAddress sender) {
		// Checked throughout all login
		if (this.hasSession(sender)) {
			return new RakNetPacket(MessageIdentifier.ID_ALREADY_CONNECTED);
		} else if (this.getSessionCount() >= this.maxConnections && this.maxConnections >= 0) {
			// We have no free connections
			return new RakNetPacket(MessageIdentifier.ID_NO_FREE_INCOMING_CONNECTIONS);
		} else if (this.addressBlocked(sender.getAddress())) {
			// Address is blocked
			ConnectionBanned connectionBanned = new ConnectionBanned();
			connectionBanned.serverGuid = this.guid;
			connectionBanned.encode();
			return connectionBanned;
		}

		// There were no errors
		return null;
	}

	/**
	 * Sends a raw message to the specified address. Be careful when using this
	 * method, because if it is used incorrectly it could break server sessions
	 * entirely! If you are wanting to send a message to a session, you are
	 * probably looking for the
	 * {@link RakNetSession#sendMessage(Reliability, Packet)
	 * sendMessage} method.
	 * 
	 * @param buf
	 *            the buffer to send.
	 * @param address
	 *            the address to send the buffer to.
	 */
	public final void sendNettyMessage(ByteBuf buf, InetSocketAddress address) {
		channel.writeAndFlush(new DatagramPacket(buf, address));
	}

	/**
	 * Sends a raw message to the specified address. Be careful when using this
	 * method, because if it is used incorrectly it could break server sessions
	 * entirely! If you are wanting to send a message to a session, you are
	 * probably looking for the
	 * {@link RakNetSession#sendMessage(Reliability, Packet)
	 * sendMessage} method.
	 * 
	 * @param packet
	 *            the buffer to send.
	 * @param address
	 *            the address to send the buffer to.
	 */
	public final void sendNettyMessage(Packet packet, InetSocketAddress address) {
		this.sendNettyMessage(packet.buffer(), address);
	}

	/**
	 * Sends a raw message to the specified address. Be careful when using this
	 * method, because if it is used incorrectly it could break server sessions
	 * entirely! If you are wanting to send a message to a session, you are
	 * probably looking for the
	 * {@link RakNetSession#sendMessage(Reliability, int)
	 * sendMessage} method.
	 * 
	 * @param packetId
	 *            the ID of the packet to send.
	 * @param address
	 *            the address to send the packet to.
	 */
	public final void sendNettyMessage(int packetId, InetSocketAddress address) {
		this.sendNettyMessage(new RakNetPacket(packetId), address);
	}

	/**
	 * Starts the server.
	 * 
	 * @throws RakNetException
	 *             if an error occurs during startup.
	 */
	public final void start() throws RakNetException {
		// Make sure we have a listener
		if (listeners.size() <= 0) {
			log.warn("Server has no listeners");
		}

		// Create bootstrap and bind the channel
		try {
			bootstrap.channel(NioDatagramChannel.class).group(group).handler(handler);
			bootstrap.option(ChannelOption.SO_BROADCAST, true).option(ChannelOption.SO_REUSEADDR, false);
			this.channel = bootstrap.bind(address, port).sync().channel();
			this.running = true;
			log.debug("Created and bound bootstrap");

			// Notify API
			log.info("Started server");
			for (RakNetServerListener listener : listeners) {
				listener.onServerStart();
			}

			for (int i = 0; i < SESSION_GROUP_SIZE; i++) {
                List<RakNetClientSession> rakNetClientSessions = this.sessionGroup.get(i);

                Thread sessionUpdateThread = new Thread(() -> {
                    while (this.running == true) {
                        List<RakNetClientSession> updatedSessions = new LinkedList<>(rakNetClientSessions);
                        for (RakNetClientSession session : updatedSessions) {
                            ProxyTiming.getInstance().recorderServerSessionUpdateCounting();

                            try {
                                // Update session and make sure it isn't DOSing us
                                session.update();
                                if (session.getPacketsReceivedThisSecond() >= RakNet.getMaxPacketsPerSecond()) {
                                    this.blockAddress(session.getInetAddress(), "Too many packets",
                                            RakNet.MAX_PACKETS_PER_SECOND_BLOCK);
                                }
                            } catch (TimeoutException e) {
                                log.info("Player quit because of timeout!");
                                // An error related to the session occurred
                                for (RakNetServerListener listener : listeners) {
                                    listener.onSessionException(session, e);
                                }
                                this.removeSession(session, e.getMessage());
                            } catch (Throwable throwable) {
                                log.error("Exception ", throwable);
                                // An error related to the session occurred
                                for (RakNetServerListener listener : listeners) {
                                    listener.onSessionException(session, throwable);
                                }
                                this.removeSession(session, throwable.getMessage());
                            }
                        }

                        try {
                            Thread.sleep(0, 1); // Lower CPU usage
                        } catch (InterruptedException e) {
                            log.warn("Server sleep interrupted");
                        }
                    }
				});
                sessionUpdateThread.setDaemon(true);
                sessionUpdateThread.setName("RakNetSessionUpdater" + i);
                sessionUpdateThread.start();
			}

			// Update system
			while (this.running == true) {
				try {
					Thread.sleep(1000); // Lower CPU usage
				} catch (InterruptedException e) {
					log.warn("Server sleep interrupted");
				}
			}
		} catch (InterruptedException e) {
			this.running = false;
			throw new RakNetException(e);
		}
	}

	/**
	 * Starts the server on its own <code>Thread</code>.
	 * 
	 * @return the <code>Thread</code> the server is running on.
	 */
	public final Thread startThreaded() {
		// Give the thread a reference
		RakNetServer server = this;

		// Create thread and start it
		Thread thread = new Thread() {
			@Override
			public void run() {
				try {
					server.start();
				} catch (Throwable throwable) {
					if (server.getListeners().length > 0) {
						for (RakNetServerListener listener : server.getListeners()) {
							listener.onThreadException(throwable);
						}
					} else {
						throwable.printStackTrace();
					}
				}
			}
		};
		thread.setName("JRAKNET_SERVER_" + server.getGloballyUniqueId());
		thread.start();
		this.serverThread = thread;
		log.info("Started on thread with name " + thread.getName());

		// Return the thread so it can be modified
		return thread;
	}

	/**
	 * Stops the server.
	 * 
	 * @param reason
	 *            the reason the server shutdown.
	 */
	public final void shutdown(String reason) {
		// Tell the server to stop running
		this.running = false;

		// Disconnect sessions
		for (RakNetClientSession session : sessions.values()) {
			this.removeSession(session, reason);
		}
		sessions.clear();
		this.sessionGroup = new ArrayList<>();

		// Interrupt its thread if it owns one
		if (this.serverThread != null) {
			serverThread.interrupt();
		}

		// Notify API
		log.info("Shutdown server");
		for (RakNetServerListener listener : listeners) {
			listener.onServerShutdown();
		}

		this.group.shutdownGracefully();
	}

	/**
	 * Stops the server.
	 */
	public final void shutdown() {
		this.shutdown("Server shutdown");
	}

}
