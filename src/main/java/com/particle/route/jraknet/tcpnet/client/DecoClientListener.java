package com.particle.route.jraknet.tcpnet.client;

import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.identifier.Identifier;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Record;
import com.particle.route.jraknet.tcpnet.session.LogicServerSession;
import com.particle.route.jraknet.tcpnet.session.PhysicalServeSession;
import io.netty.buffer.ByteBuf;

import java.net.InetSocketAddress;

public interface DecoClientListener {

    /**
     * physical层的tcp连接
     * @param session
     */
    public default void onPhysicalConnect(PhysicalServeSession session) {

    }

    /**
     * logic的session已建立连接
     *
     * @param session
     *            the session assigned to the server.
     */
    public default void onLogicConnect(LogicServerSession session) {
    }

    /**
     * physical层的连接断开
     * @param session
     * @param reason
     */
    public default void onPhysicalDisconnect(PhysicalServeSession session, String reason) {

    }

    /**
     * logic的连接已断开
     *
     * @param session
     *            the server the client disconnected from.
     * @param reason
     *            the reason for disconnection.
     */
    public default void onLogicDisconnect(LogicServerSession session, String reason) {
    }

    /**
     * Called when the client has been shutdown.
     */
    public default void onClientShutdown() {
    }

    /**
     * Called when a server is discovered on the local network.
     *
     * @param address
     *            the address of the server.
     * @param identifier
     *            the <code>Identifier</code> of the server.
     */
    public default void onServerDiscovered(InetSocketAddress address, Identifier identifier) {
    }

    /**
     * Called when the <code>Identifier</code> of an already discovered server
     * changes.
     *
     * @param address
     *            the address of the server.
     * @param identifier
     *            the new <code>Identifier</code>.
     */
    public default void onServerIdentifierUpdate(InetSocketAddress address, Identifier identifier) {
    }

    /**
     * Called when a previously discovered server has been forgotten by the
     * client.
     *
     * @param address
     *            the address of the server.
     */
    public default void onServerForgotten(InetSocketAddress address) {
    }

    /**
     * Called when an external server is added to the client's external server
     * list.
     *
     * @param address
     *            the address of the server.
     */
    public default void onExternalServerAdded(InetSocketAddress address) {
    }

    /**
     * Called when the identifier of an external server changes.
     *
     * @param address
     *            the address of the server.
     * @param identifier
     *            the new identifier.
     */
    public default void onExternalServerIdentifierUpdate(InetSocketAddress address, Identifier identifier) {
    }

    /**
     * Called when an external server is removed from the client's external
     * server list.
     *
     * @param address
     *            the address of the server.
     */
    public default void onExternalServerRemoved(InetSocketAddress address) {
    }

    /**
     * Called when a message is received by the server.
     *
     * @param session
     *            the server that received the packet.
     * @param record
     *            the received record.
     * @param packet
     *            the received packet.
     */
    public default void onAcknowledge(LogicServerSession session, Record record, EncapsulatedPacket packet) {
    }

    /**
     * Called when a message is not received by the server.
     *
     * @param session
     *            the server that lost the packet.
     * @param record
     *            the lost record.
     * @param packet
     *            the lost packet.
     */
    public default void onNotAcknowledge(LogicServerSession session, Record record, EncapsulatedPacket packet) {
    }

    /**
     * Called when a packet has been received from the server and is ready to be
     * handled.
     *
     * @param session
     *            the server that sent the packet.
     * @param packet
     *            the packet received from the server.
     * @param channel
     *            the channel the packet was sent on.
     */
    public default void handleMessage(LogicServerSession session, RakNetPacket packet, int channel) {
    }

    /**
     * Called when a packet with an ID below the user enumeration
     * (<code>ID_USER_PACKET_ENUM</code>) cannot be handled by the session
     * because it is not programmed to handle it. This function can be used to
     * add missing features from the regular RakNet protocol that are absent in
     * JRakNet if needed.
     *
     * @param session
     *            the server that sent the packet.
     * @param packet
     *            the packet received from the server.
     * @param channel
     *            the channel the packet was sent on.
     */
    public default void handleUnknownMessage(LogicServerSession session, RakNetPacket packet, int channel) {
    }

    /**
     * Called when the handler receives a packet after the server has already
     * handled it, this method is useful for handling packets outside of the
     * RakNet protocol. However, be weary when using this as packets meant for
     * the server will have already been handled by the client; and it is not a
     * good idea to try to manipulate JRakNet's RakNet protocol implementation
     * using this method.
     *
     * @param buf
     *            the packet buffer.
     * @param address
     *            the address of the sender.
     */
    public default void handleNettyMessage(ByteBuf buf, InetSocketAddress address) {
    }

    /**
     * Called when a handler exception has occurred, these normally do not
     * matter as long as it does not come the address of the server the client
     * is connecting or is connected to.
     *
     * @param address
     *            the address that caused the exception.
     * @param throwable
     *            the <code>Throwable</code> that was caught.
     */
    public default void onHandlerException(InetSocketAddress address, Throwable throwable) {
        throwable.printStackTrace();
    }

    /**
     * Called when an exception is caught in the external thread the client is
     * running on, this method is only called when the client is started through
     * <code>connectThreaded()</code>.
     *
     * @param throwable
     *            the <code>Throwable</code> that was caught.
     */
    public default void onThreadException(Throwable throwable) {
        throwable.printStackTrace();
    }
}
