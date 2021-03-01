package com.particle.route.jraknet.protocol.status;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.Failable;
import com.particle.route.jraknet.protocol.MessageIdentifier;

/**
 * 解析网易中国版这边的ping包
 */
public class NeteaseUnconnectedPing extends RakNetPacket implements Failable {

	public long pingId;

	private boolean illegal = false;

	protected NeteaseUnconnectedPing() {
		super(MessageIdentifier.ID_UNCONNECTED_PING);
	}

	public NeteaseUnconnectedPing(Packet packet) {
		super(packet);
	}

	@Override
	public void encode() {
		this.writeLong(pingId);
	}

	@Override
	public void decode() {
		try {
			this.pingId = this.readLong();
		} catch (Exception e) {
			this.illegal = true;
		}
	}

	@Override
	public boolean failed() {
		return this.illegal;
	}
}
