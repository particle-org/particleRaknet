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

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.particle.route.jraknet.RakNetPacket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;

/**
 * Used by the <code>RakNetClient</code> with the sole purpose of sending
 * received packets to the client so they can be handled.
 *
 * @author Trent "Whirvis" Summerlin
 */
public class RakNetClientHandler extends ChannelInboundHandlerAdapter {

	private static final Logger log = LoggerFactory.getLogger(RakNetClientHandler.class);
	// Logger name
	private final String loggerName;

	// Handler data
	private final RakNetClient client;
	private InetSocketAddress causeAddress;

	/**
	 * Constructs a <code>RakNetClientHandler</code> with the specified
	 * <code>RakNetClient</code>.
	 * 
	 * @param client
	 *            the <code>RakNetClient</code> to send received packets to.
	 */
	public RakNetClientHandler(RakNetClient client) {
		this.loggerName = "client handler #" + client.getGloballyUniqueId();
		this.client = client;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof DatagramPacket) {
			// Get packet and sender data
			DatagramPacket datagram = (DatagramPacket) msg;
			InetSocketAddress sender = datagram.sender();
			RakNetPacket packet = new RakNetPacket(datagram);

			// If an exception happens it's because of this address
			this.causeAddress = sender;

			// Handle the packet and release the buffer
			client.handleMessage(packet, sender);
			datagram.content().readerIndex(0); // Reset position
			log.debug(loggerName + " Sent packet to client and reset Datagram buffer read position");
			for (RakNetClientListener listener : client.getListeners()) {
				listener.handleNettyMessage(datagram.content(), sender);
			}
			datagram.content().release(); // No longer needed
			log.debug(loggerName + " Sent Datagram buffer to client and released it");

			// No exceptions occurred, release the suspect
			this.causeAddress = null;
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		client.handleHandlerException(this.causeAddress, cause);
	}

}
