package com.particle.route.jraknet.tcpnet.session;

import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.MessageIdentifier;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Record;
import com.particle.route.jraknet.session.RakNetState;
import com.particle.route.jraknet.tcpnet.server.DecoServer;
import com.particle.route.jraknet.tcpnet.server.DecoServerListener;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class LogicClientSession extends LogicSession {

    private static final Logger logger = LoggerFactory.getLogger(LogicClientSession.class);

    private DecoServer decoServer;
    private final long timeCreated;
    private long timestamp;

    public LogicClientSession(DecoServer server, long timeCreated, long guid, Channel channel, InetSocketAddress address, PhysicalSession physicalSession) {
        super(guid, channel, address, physicalSession);
        this.decoServer = server;
        this.timeCreated = timeCreated;
    }

    public DecoServer getDecoServer() {
        return decoServer;
    }

    public long getTimeCreated() {
        return this.timeCreated;
    }

    public long getTimestamp() {
        return (System.currentTimeMillis() - this.timestamp);
    }

    @Override
    public void onAcknowledge(Record record, EncapsulatedPacket packet) {
        for (DecoServerListener listener : decoServer.getListeners()) {
            listener.onAcknowledge(this, record, packet);
        }
    }

    @Override
    public void onNotAcknowledge(Record record, EncapsulatedPacket packet) {
        for (DecoServerListener listener : decoServer.getListeners()) {
            listener.onNotAcknowledge(this, record, packet);
        }
    }

    @Override
    public void handleMessage(RakNetPacket packet, int channel) {
        short packetId = packet.getId();
        if (packetId == MessageIdentifier.ID_CONNECTION_REQUEST && this.getState() == RakNetState.DISCONNECTED) {

        } else if (packetId == MessageIdentifier.ID_NEW_INCOMING_CONNECTION && this.getState() == RakNetState.HANDSHAKING) {

        } else if (packetId == MessageIdentifier.ID_DISCONNECTION_NOTIFICATION) {

        } else {
            /*
             * If the packet is a user packet, we use handleMessage(). If the ID
             * is not a user packet but it is unknown to the session, we use
             * handleUnknownMessage().
             */
            if (packetId >= MessageIdentifier.ID_USER_PACKET_ENUM) {
                for (DecoServerListener listener : decoServer.getListeners()) {
                    listener.handleMessage(this, packet, channel);
                }
            } else {
                for (DecoServerListener listener : decoServer.getListeners()) {
                    listener.handleUnknownMessage(this, packet, channel);
                }
            }
        }
    }
}
