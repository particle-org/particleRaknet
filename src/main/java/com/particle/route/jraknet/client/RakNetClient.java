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
package com.particle.route.jraknet.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.particle.route.jraknet.task.UpdaterManager;
import com.particle.route.jraknet.task.IClientUpdateTask;
import com.particle.route.jraknet.protocol.MessageIdentifier;
import com.particle.route.jraknet.protocol.Reliability;
import com.particle.route.jraknet.protocol.login.ConnectionRequest;
import com.particle.route.jraknet.protocol.login.OpenConnectionRequestOne;
import com.particle.route.jraknet.protocol.login.OpenConnectionRequestTwo;
import com.particle.route.jraknet.protocol.message.CustomPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Acknowledge;
import com.particle.route.jraknet.session.*;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.oio.OioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNet;
import com.particle.route.jraknet.RakNetException;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.util.RakNetUtils;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramPacket;

/**
 * Used to connect to servers using the RakNet protocol.
 *
 * @author Trent "Whirvis" Summerlin
 */
public class RakNetClient implements UnumRakNetPeer, RakNetClientListener, IClientUpdateTask {

	private static final Logger log = LoggerFactory.getLogger(RakNetClient.class);

	// Used to discover systems without relying on the main thread
	private static final int[] DEFAULT_TRANSFER_UNITS = new int[] { 1200, 576,
			RakNet.MINIMUM_MTU_SIZE };

	private static UpdaterManager updaterManager = new UpdaterManager("RakNetClientTask");

	// Client data
	private final long guid;
	private final long pingId;
	private final long timestamp;
	private final ConcurrentLinkedQueue<RakNetClientListener> listeners;
	private Thread clientThread;

	// Networking data
	private final Bootstrap bootstrap;
	private final EventLoopGroup group;
	private final RakNetClientHandler handler;
	private MaximumTransferUnit[] maximumTransferUnits;

	// Session management
	private Channel channel;
	private SessionPreparation preparation;
	private volatile RakNetServerSession session;

	/**
	 * Constructs a <code>RakNetClient</code> with the specified
	 * <code>DiscoveryMode</code> and server discovery port.
	 * 
	 */
	public RakNetClient() {
		// Set client data
		UUID uuid = UUID.randomUUID();
		this.guid = uuid.getMostSignificantBits();
		this.pingId = uuid.getLeastSignificantBits();
		this.timestamp = System.currentTimeMillis();
		this.listeners = new ConcurrentLinkedQueue<RakNetClientListener>();

		// Set networking data
		this.bootstrap = new Bootstrap();
		this.group = new OioEventLoopGroup();
		this.handler = new RakNetClientHandler(this);

		// Add maximum transfer units
		this.setMaximumTransferUnits(DEFAULT_TRANSFER_UNITS);

		// Initiate bootstrap data
		try {
			bootstrap.channel(OioDatagramChannel.class).group(group).handler(handler);
			bootstrap.option(ChannelOption.SO_BROADCAST, false).option(ChannelOption.SO_REUSEADDR, false);
			this.channel = bootstrap.bind(0).sync().channel();
			log.debug("Created and bound bootstrap");
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @return the client's networking protocol version.
	 */
	public final int getProtocolVersion() {
		return RakNet.CLIENT_NETWORK_PROTOCOL;
	}

	/**
	 * @return the client's globally unique ID.
	 */
	public final long getGloballyUniqueId() {
		return this.guid;
	}

	/**
	 * @return the client's ping ID.
	 */
	public final long getPingId() {
		return this.pingId;
	}

	/**
	 * @return the client's timestamp.
	 */
	public final long getTimestamp() {
		return (System.currentTimeMillis() - this.timestamp);
	}

	/**
	 * Sets the client's discovery ports.
	 * 
	 * @param discoveryPorts
	 *            the new discovery ports.
	 */
	public final void setDiscoveryPorts(int... discoveryPorts) {
		// We make a new set to prevent duplicates
		HashSet<Integer> discoverySet = new HashSet<Integer>();
		for (int discoveryPort : discoveryPorts) {
			if (discoveryPort < 0 || discoveryPort > 65535) {
				throw new IllegalArgumentException("Invalid port range for discovery port");
			}
			discoverySet.add(discoveryPort);
		}
	}

	/**
	 * @return the thread the server is running on if it was started using
	 *         <code>startThreaded()</code>.
	 */
	public final Thread getThread() {
		return this.clientThread;
	}

	/**
	 * Sets the size of the maximum transfer units that will be used by the
	 * client during in.
	 * 
	 * @param maximumTransferUnitSizes
	 *            the maximum transfer unit sizes.
	 */
	public final void setMaximumTransferUnits(int... maximumTransferUnitSizes) {
		boolean foundTransferUnit = false;
		this.maximumTransferUnits = new MaximumTransferUnit[maximumTransferUnitSizes.length];
		for (int i = 0; i < maximumTransferUnits.length; i++) {
			int maximumTransferUnitSize = maximumTransferUnitSizes[i];
			if (maximumTransferUnitSize > RakNet.MAXIMUM_MTU_SIZE
					|| maximumTransferUnitSize < RakNet.MINIMUM_MTU_SIZE) {
				throw new IllegalArgumentException("Maximum transfer unit size must be between "
						+ RakNet.MINIMUM_MTU_SIZE + "-" + RakNet.MAXIMUM_MTU_SIZE);
			}
			if (RakNetUtils.getMaximumTransferUnit() >= maximumTransferUnitSize) {
				maximumTransferUnits[i] = new MaximumTransferUnit(maximumTransferUnitSize,
						(i * 2) + (i + 1 < maximumTransferUnits.length ? 2 : 1));
				foundTransferUnit = true;
			} else {
				log.warn("Valid maximum transfer unit " + maximumTransferUnitSize
						+ " failed to register due to network card limitations");
			}
		}
		if (foundTransferUnit == false) {
			throw new RuntimeException("No compatible maximum transfer unit found for machine network cards");
		}
	}

	/**
	 * @return the maximum transfer units the client will use during in.
	 */
	public final int[] getMaximumTransferUnits() {
		int[] maximumTransferUnitSizes = new int[maximumTransferUnits.length];
		for (int i = 0; i < maximumTransferUnitSizes.length; i++) {
			maximumTransferUnitSizes[i] = maximumTransferUnits[i].getSize();
		}
		return maximumTransferUnitSizes;
	}

	/**
	 * @return the session the client is connected to.
	 */
	public final RakNetServerSession getSession() {
		return this.session;
	}

	/**
	 * @return the client's listeners.
	 */
	public final RakNetClientListener[] getListeners() {
		return listeners.toArray(new RakNetClientListener[listeners.size()]);
	}

	/**
	 * Adds a listener to the client.
	 * 
	 * @param listener
	 *            the listener to add.
	 * @return the client.
	 */
	public final RakNetClient addListener(RakNetClientListener listener) {
		// Validate listener
		if (listener == null) {
			throw new NullPointerException("Listener must not be null");
		}
		if (listeners.contains(listener)) {
			throw new IllegalArgumentException("A listener cannot be added twice");
		}
		if (listener instanceof RakNetClient && !listener.equals(this)) {
			throw new IllegalArgumentException("A client cannot be used as a listener except for itself");
		}

		// Add listener
		listeners.add(listener);
		log.debug("Added listener " + listener.getClass().getName());

		return this;
	}

	/**
	 * Adds the client to its own set of listeners, used when extending the
	 * <code>RakNetClient</code> directly.
	 * 
	 * @return the client.
	 */
	public final RakNetClient addSelfListener() {
		this.addListener(this);
		return this;
	}

	/**
	 * Removes a listener from the client.
	 * 
	 * @param listener
	 *            the listener to remove.
	 * @return the client.
	 */
	public final RakNetClient removeListener(RakNetClientListener listener) {
		boolean hadListener = listeners.remove(listener);
		if (hadListener == true) {
			log.info("Removed listener " + listener.getClass().getName());
		} else {
			log.warn("Attempted to removed unregistered listener " + listener.getClass().getName());
		}
		return this;
	}

	/**
	 * Removes the client from its own set of listeners, used when extending the
	 * <code>RakNetClient</code> directly.
	 * 
	 * @return the client.
	 */
	public final RakNetClient removeSelfListener() {
		this.removeListener(this);
		return this;
	}

	/**
	 * @return <code>true</code> if the client is currently connected to a
	 *         server.
	 */
	public final boolean isConnected() {
		if (session == null) {
			return false;
		}
		return session.getState().equals(RakNetState.CONNECTED);
	}

	/**
	 * Returns whether or not the client is doing something on a thread. This
	 * can mean multiple things, with being connected to a server or looking for
	 * servers on the local network to name a few.
	 * 
	 * @return <code>true</code> if the client is running.
	 */
	public final boolean isRunning() {
		if (channel == null) {
			return false;
		}
		return channel.isOpen();
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
		// Handle exception based on connection state
		if (address.equals(preparation.address)) {
			if (preparation != null) {
				preparation.cancelReason = new NettyHandlerException(this, handler, cause);
			} else if (session != null) {
				this.disconnect(cause.getClass().getName() + ": " + cause.getLocalizedMessage());
			}
		}

		// Notify API
		log.warn("Handled exception " + cause.getClass().getName() + " caused by address " + address);
		for (RakNetClientListener listener : listeners) {
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
	public final void handleMessage(RakNetPacket packet, InetSocketAddress sender) {
		short packetId = packet.getId();

		// Are we still ging in?
		if (preparation != null) {
			if (sender.equals(preparation.address)) {
				preparation.handleMessage(packet);
				return;
			}
		}

		// Only handle these from the server we're connected to!
		if (session != null) {
			if (sender.equals(session.getAddress())) {
				if (packetId >= MessageIdentifier.ID_CUSTOM_0 && packetId <= MessageIdentifier.ID_CUSTOM_F) {
					CustomPacket custom = new CustomPacket(packet);
					custom.decode();

					session.handleCustom(custom);
				} else if (packetId == Acknowledge.ACKNOWLEDGED || packetId == Acknowledge.NOT_ACKNOWLEDGED) {
					Acknowledge acknowledge = new Acknowledge(packet);
					acknowledge.decode();

					session.handleAcknowledge(acknowledge);
				}
			}
		}

		if (MessageIdentifier.hasPacket(packet.getId())) {
			log.debug("Handled internal packet with ID " + MessageIdentifier.getName(packet.getId()) + " ("
					+ packet.getId() + ")");
		} else {
			log.debug("Sent packet with ID " + packet.getId() + " to session handler");
		}
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
		log.debug("Sent netty message with size of " + buf.capacity() + " bytes (" + (buf.capacity() * 8) + " bits) to "
				+ address);
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
	 *            the packet to send.
	 * @param address
	 *            the address to send the packet to.
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
	 * Connects the client to a server with the specified address.
	 * 
	 * @param address
	 *            the address of the server to connect to.
	 * @throws RakNetException
	 *             if an error occurs during connection or in.
	 */
	public final void connect(InetSocketAddress address) throws RakNetException {
		// Make sure we have a listener
		if (listeners.size() <= 0) {
			log.warn("Client has no listeners");
		}

		// Reset client data
		if (this.isConnected()) {
			this.disconnect("Disconnected");
		}
		MaximumTransferUnit[] units = MaximumTransferUnit.sort(maximumTransferUnits);
		this.preparation = new SessionPreparation(this, units[0].getSize());
		preparation.address = address;

		// Send OPEN_CONNECTION_REQUEST_ONE with a decreasing MTU
		int retriesLeft = 0;
		try {
			for (MaximumTransferUnit unit : units) {
				retriesLeft += unit.getRetries();
				while (unit.retry() > 0 && preparation.loginPackets[0] == false && preparation.cancelReason == null) {
					OpenConnectionRequestOne connectionRequestOne = new OpenConnectionRequestOne();
					connectionRequestOne.maximumTransferUnit = unit.getSize();
					connectionRequestOne.protocolVersion = this.getProtocolVersion();
					connectionRequestOne.encode();
					this.sendNettyMessage(connectionRequestOne, address);

					for (int i = 0; i < 5 && preparation.loginPackets[0] == false && preparation.cancelReason == null; i++) {
						Thread.sleep(1000);
					}
				}
			}
		} catch (InterruptedException e) {
			throw new RakNetException(e);
		}

		// Reset maximum transfer units so they can be used again
		for (MaximumTransferUnit unit : maximumTransferUnits) {
			unit.reset();
		}

		// If the server didn't respond then it is offline
		if (preparation.loginPackets[0] == false && preparation.cancelReason == null) {
			preparation.cancelReason = new ServerOfflineException(this, preparation.address);
		}

		// Send OPEN_CONNECTION_REQUEST_TWO until a response is received
		try {
			while (retriesLeft > 0 && preparation.loginPackets[1] == false && preparation.cancelReason == null) {
				OpenConnectionRequestTwo connectionRequestTwo = new OpenConnectionRequestTwo();
				connectionRequestTwo.clientGuid = this.guid;
				connectionRequestTwo.address = preparation.address;
				connectionRequestTwo.maximumTransferUnit = preparation.maximumTransferUnit;
				connectionRequestTwo.encode();

				if (!connectionRequestTwo.failed()) {
					this.sendNettyMessage(connectionRequestTwo, address);

					for (int i = 0; i < 5 && preparation.loginPackets[1] == false && preparation.cancelReason == null; i++) {
						Thread.sleep(7000);
					}
				} else {
					preparation.cancelReason = new PacketBufferException(this, connectionRequestTwo);
				}
			}
		} catch (InterruptedException e) {
			throw new RakNetException(e);
		}

		// If the server didn't respond then it is offline
		if (preparation.loginPackets[1] == false && preparation.cancelReason == null) {
			preparation.cancelReason = new ServerOfflineException(this, preparation.address);
		}

		// If the session was set we are connected
		if (preparation.readyForSession()) {
			// Set session and delete preparation data
			this.session = preparation.createSession(channel);
			this.preparation = null;

			// Send connection packet
			ConnectionRequest connectionRequest = new ConnectionRequest();
			connectionRequest.clientGuid = this.guid;
			connectionRequest.timestamp = (System.currentTimeMillis() - this.timestamp);
			connectionRequest.encode();
			session.sendMessage(Reliability.RELIABLE_ORDERED, connectionRequest);
			log.debug("Sent connection packet to server");

			// Initiate connection loop required for the session to function
			this.initConnection();
		} else {
			// Reset the connection data, it failed
			RakNetException cancelReason = preparation.cancelReason;
			this.preparation = null;
			this.session = null;
			throw cancelReason;
		}
	}

	/**
	 * Connects the client to a server with the specified address.
	 * 
	 * @param address
	 *            the address of the server to connect to.
	 * @param port
	 *            the port of the server to connect to.
	 * @throws RakNetException
	 *             if an error occurs during connection or in.
	 */
	public final void connect(InetAddress address, int port) throws RakNetException {
		this.connect(new InetSocketAddress(address, port));
	}

	/**
	 * Connects the client to a server with the specified address.
	 * 
	 * @param address
	 *            the address of the server to connect to.
	 * @param port
	 *            the port of the server to connect to.
	 * @throws RakNetException
	 *             if an error occurs during connection or in.
	 * @throws UnknownHostException
	 *             if the specified address is an unknown host.
	 */
	public final void connect(String address, int port) throws RakNetException, UnknownHostException {
		this.connect(InetAddress.getByName(address), port);
	}

	/**
	 * Connects the client to a server with the specified address on its own
	 * <code>Thread</code>.
	 * 
	 * @param address
	 *            the address of the server to connect to.
	 * @return the Thread the client is running on.
	 */
	public final Thread connectThreaded(InetSocketAddress address) {
		// Give the thread a reference
		RakNetClient client = this;

		// Create and start the thread
		Thread thread = new Thread() {
			@Override
			public void run() {
				try {
					client.connect(address);
				} catch (Throwable throwable) {
					if (client.getListeners().length > 0) {
						for (RakNetClientListener listener : client.getListeners()) {
							listener.onThreadException(throwable);
						}
					} else {
						throwable.printStackTrace();
					}
				}
			}
		};
		thread.setName("JRAKNET_CLIENT_" + client.getGloballyUniqueId());
		thread.start();
		this.clientThread = thread;
		log.info("Started on thread with name " + thread.getName());

		// Return the thread so it can be modified
		return thread;
	}

	/**
	 * Connects the client to a server with the specified address on its own
	 * <code>Thread</code>.
	 * 
	 * @param address
	 *            the address of the server to connect to.
	 * @param port
	 *            the port of the server to connect to.
	 * @return the Thread the client is running on.
	 */
	public final Thread connectThreaded(InetAddress address, int port) {
		return this.connectThreaded(new InetSocketAddress(address, port));
	}

	/**
	 * Connects the client to a server with the specified address on its own
	 * <code>Thread</code>.
	 * 
	 * @param address
	 *            the address of the server to connect to.
	 * @param port
	 *            the port of the server to connect to.
	 * @throws UnknownHostException
	 *             if the specified address is an unknown host.
	 * @return the Thread the client is running on.
	 */
	public final Thread connectThreaded(String address, int port) throws UnknownHostException {
		return this.connectThreaded(InetAddress.getByName(address), port);
	}

	/**
	 * Starts the loop needed for the client to stay connected to the server.
	 * 
	 * @throws RakNetException
	 *             if any problems occur during connection.
	 */
	private final void initConnection() throws RakNetException {
		if (session != null) {
			log.debug("Initiated connected with server");

			//这里由统一的Client更新线程更新Client，减少线程争用
			RakNetClient.updaterManager.addTask(this);

//			while (session != null) {
//				session.update();
//
//				/*
//				 * The order here is important, as the session could end up
//				 * becoming null if we sleep before we actually update it.
//				 */
//				try {
//					Thread.sleep(0, 1); // Lower CPU usage
//				} catch (InterruptedException e) {
//					log.warn("Client sleep interrupted");
//				}
//			}
		} else {
			throw new RakNetClientException(this, "Attempted to initiate connection without session");
		}
	}

	@Override
	public final EncapsulatedPacket sendMessage(Reliability reliability, int channel, Packet packet) {
		if (this.isConnected()) {
			return session.sendMessage(reliability, channel, packet);
		}
		return null;
	}

	/**
	 * Disconnects the client from the server if it is connected to one.
	 * 
	 * @param reason
	 *            the reason the client disconnected from the server.
	 */
	public final void disconnect(String reason) {
		if (session != null) {
			try {
				// Disconnect session
				session.closeConnection();
			} catch (TimeoutException exception) {
				log.info("Close timeout session");
			}


			// Interrupt its thread if it owns one
			if (this.clientThread != null) {
				clientThread.interrupt();
			}

			// Notify API
			log.info("Disconnected from server with address " + session.getAddress() + " with reason \"" + reason
					+ "\"");
			for (RakNetClientListener listener : listeners) {
				listener.onDisconnect(session, reason);
			}

			// Destroy session
			this.session = null;
		} else {
			log.warn(
					"Attempted to disconnect from server even though it was not connected to as server in the first place");
		}
	}

	/**
	 * Disconnects the client from the server if it is connected to one.
	 */
	public final void disconnect() {
		this.disconnect("Disconnected");
	}

	/**
	 * Shuts down the client for good, once this is called the client can no
	 * longer connect to servers.
	 */
	public final void shutdown() {
		// Close channel
		if (this.isRunning()) {
			channel.close();
			group.shutdownGracefully();

			// Notify API
			log.debug("Shutdown client");
			for (RakNetClientListener listener : listeners) {
				listener.onClientShutdown();
			}
		} else {
			log.warn("Client attempted to shutdown after it was already shutdown");
		}
	}

	/**
	 * Disconnects from the server and shuts down the client for good, once this
	 * is called the client can no longer connect to servers.
	 * 
	 * @param reason
	 *            the reason the client shutdown.
	 */
	public final void disconnectAndShutdown(String reason) {
		// Disconnect from server
		if (this.isConnected()) {
			this.disconnect(reason);
		}
		this.shutdown();
	}

	/**
	 * Disconnects from the server and shuts down the client for good, once this
	 * is called the client can no longer connect to servers.
	 */
	public final void disconnectAndShutdown() {
		this.disconnectAndShutdown("Client shutdown");
	}

	@Override
	public final void finalize() {
		if (session != null)
			this.shutdown();
		log.debug("Finalized and collected by garbage heap");
	}

	@Override
	public void update() {
		try {
			if (this.session != null) {
				this.session.update();
			}
		} catch (TimeoutException exception) {
			this.disconnect("Time Out");
		}

	}

	@Override
	public boolean isStopping() {
		return !this.isRunning();
	}

	@Override
	public long getUUID() {
		return this.getGloballyUniqueId();
	}
}
