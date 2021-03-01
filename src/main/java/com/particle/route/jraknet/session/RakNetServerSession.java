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

import java.net.InetSocketAddress;

import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.client.RakNetClient;
import com.particle.route.jraknet.client.RakNetClientListener;
import com.particle.route.jraknet.protocol.ConnectionType;
import com.particle.route.jraknet.protocol.MessageIdentifier;
import com.particle.route.jraknet.protocol.Reliability;
import com.particle.route.jraknet.protocol.login.ConnectionRequestAccepted;
import com.particle.route.jraknet.protocol.login.NewIncomingConnection;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Record;

import io.netty.channel.Channel;

/**
 * This class represents a server connection and handles the login sequence
 * packets.
 *
 * @author Trent "Whirvis" Summerlin
 */
public class RakNetServerSession extends RakNetSession {

	private final RakNetClient client;
	private EncapsulatedPacket incomingConnectionPacket;

	/**
	 * Called by the client when the connection is closed.
	 */
	@Override
	public void closeConnection() {
		sendQueue.clear(); // Make sure disconnect packet is first in-line
		sendQueueSize.set(0);
		this.sendMessage(Reliability.UNRELIABLE, MessageIdentifier.ID_DISCONNECTION_NOTIFICATION);
		this.update(); // Make sure the packet is sent out
	}

	/**
	 * Constructs a <code>RakNetClientSession</code> with the specified
	 * <code>RakNetClient</code>, globally unique ID, maximum transfer unit,
	 * <code>Channel</code>, and address.
	 * 
	 * @param client
	 *            the <code>RakNetClient</code>.
	 * @param connectionType
	 *            the connection type of the session.
	 * @param guid
	 *            the globally unique ID.
	 * @param maximumTransferUnit
	 *            the maximum transfer unit
	 * @param channel
	 *            the <code>Channel</code>.
	 * @param address
	 *            the address.
	 */
	public RakNetServerSession(RakNetClient client, ConnectionType connectionType, long guid, int maximumTransferUnit,
                               Channel channel, InetSocketAddress address) {
		super(connectionType, guid, maximumTransferUnit, channel, address);
		this.client = client;
		this.setState(RakNetState.HANDSHAKING); // We start at the handshake
	}

	@Override
	public void onAcknowledge(Record record, EncapsulatedPacket packet) {
		for (RakNetClientListener listener : client.getListeners()) {
			listener.onAcknowledge(this, record, packet);
		}

		// If the server received our IncomingConnectionPacket we are connected
//		if (!this.getState().equals(RakNetState.CONNECTED) && incomingConnectionPacket != null) {
//            if (record.equals(incomingConnectionPacket.ackRecord)) {
//                this.setState(RakNetState.CONNECTED);
//                for (RakNetClientListener listener : client.getListeners()) {
//                    listener.onConnect(this);
//                }
//            }
//        }
	}

	@Override
	public void onNotAcknowledge(Record record, EncapsulatedPacket packet) {
		for (RakNetClientListener listener : client.getListeners()) {
			listener.onNotAcknowledge(this, record, packet);
		}
	}

	@Override
	public void handleMessage(RakNetPacket packet, int channel) {
		short packetId = packet.getId();

		if (packetId == MessageIdentifier.ID_CONNECTION_REQUEST_ACCEPTED && this.getState() == RakNetState.HANDSHAKING) {
			ConnectionRequestAccepted serverHandshake = new ConnectionRequestAccepted(packet);
			serverHandshake.decode();

			if (!serverHandshake.failed()) {
				NewIncomingConnection clientHandshake = new NewIncomingConnection();
				clientHandshake.serverAddress = client.getSession().getAddress();
				clientHandshake.clientTimestamp = serverHandshake.clientTimestamp;
				clientHandshake.serverTimestamp = serverHandshake.serverTimestamp;
				clientHandshake.encode();

				if (!clientHandshake.failed()) {
					this.incomingConnectionPacket = this.sendMessage(Reliability.RELIABLE_ORDERED, clientHandshake);

                    this.setState(RakNetState.CONNECTED);
                    for (RakNetClientListener listener : client.getListeners()) {
                        listener.onConnect(this);
                    }
				} else {
					client.disconnect("Failed to login");
				}
			} else {
				client.disconnect("Failed to login");
			}
		} else if (packetId == MessageIdentifier.ID_DISCONNECTION_NOTIFICATION) {
			this.setState(RakNetState.DISCONNECTED);
			client.disconnect("Server disconnected");
		} else {
			/*
			 * If the packet is a user packet, we use handleMessage(). If the ID
			 * is not a user packet but it is unknown to the session, we use
			 * handleUnknownMessage().
			 */
			if (packetId >= MessageIdentifier.ID_USER_PACKET_ENUM) {
				for (RakNetClientListener listener : client.getListeners()) {
					listener.handleMessage(this, packet, channel);
				}
			} else {
				for (RakNetClientListener listener : client.getListeners()) {
					listener.handleUnknownMessage(this, packet, channel);
				}
			}
		}
	}
}
