package com.particle.route.jraknet.tcpnet.session;

import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.MessageIdentifier;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Record;
import com.particle.route.jraknet.session.RakNetState;
import com.particle.route.jraknet.tcpnet.client.DecoClient;
import com.particle.route.jraknet.tcpnet.client.DecoClientListener;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class LogicServerSession extends LogicSession {

    private static final Logger logger = LoggerFactory.getLogger(LogicServerSession.class);

    private final DecoClient decoClient;

    public LogicServerSession (DecoClient decoClient, long guid,
                               Channel channel, InetSocketAddress address, PhysicalSession physicalSession) {
        super(guid, channel, address, physicalSession);
        this.decoClient = decoClient;
        this.setState(RakNetState.DISCONNECTED);
    }

    /**
     * 清空LogicServerSession的发送队列
     * 具体发送服务端关闭指令，放到DecoClient中
     */
    public void clearLogicServerSession() {
        this.sendQueue.clear();
        this.retrySendQueue.clear();
    }

    @Override
    public void onAcknowledge(Record record, EncapsulatedPacket packet) {
        for (DecoClientListener listener : decoClient.getListeners()) {
            listener.onAcknowledge(this, record, packet);
        }
    }

    @Override
    public void onNotAcknowledge(Record record, EncapsulatedPacket packet) {
        for (DecoClientListener listener : decoClient.getListeners()) {
            listener.onNotAcknowledge(this, record, packet);
        }
    }

    @Override
    public void handleMessage(RakNetPacket packet, int channel) {
        short packetId = packet.getId();

        if (packetId == MessageIdentifier.ID_CONNECTION_REQUEST_ACCEPTED && this.getState() == RakNetState.HANDSHAKING) {

        } else if (packetId == MessageIdentifier.ID_DISCONNECTION_NOTIFICATION) {

        } else {
            /*
             * If the packet is a user packet, we use handleMessage(). If the ID
             * is not a user packet but it is unknown to the session, we use
             * handleUnknownMessage().
             */
            if (packetId >= MessageIdentifier.ID_USER_PACKET_ENUM) {
                for (DecoClientListener listener : decoClient.getListeners()) {
                    listener.handleMessage(this, packet, channel);
                }
            } else {
                for (DecoClientListener listener : decoClient.getListeners()) {
                    listener.handleUnknownMessage(this, packet, channel);
                }
            }
        }
    }
}
