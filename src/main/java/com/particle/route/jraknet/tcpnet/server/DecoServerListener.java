package com.particle.route.jraknet.tcpnet.server;

import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.protocol.message.acknowledge.Record;
import com.particle.route.jraknet.server.ServerPing;
import com.particle.route.jraknet.tcpnet.session.LogicClientSession;
import io.netty.buffer.ByteBuf;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public interface DecoServerListener {

    /**
     * Called when the server has been successfully started.
     */
    public default void onServerStart() {
    }

    /**
     * Called when the server has been shutdown.
     */
    public default void onServerShutdown() {
    }

    /**
     * Called when the server receives a ping from a client.
     *
     * @param ping
     *            the response that will be sent to the client.
     */
    public default void handlePing(ServerPing ping) {
    }

    /**
     * Called when a client has connected to the server but has not logged yet
     * in.
     *
     * @param address
     *            the address of the client.
     */
    public default void onClientPreConnect(InetSocketAddress address) {
    }

    /**
     * Called when a client that has connected to the server fails to log in.
     *
     * @param address
     *            the address of the client.
     * @param reason
     *            the reason the client failed to login.
     */
    public default void onClientPreDisconnect(InetSocketAddress address, String reason) {
    }

    /**
     * Called when a client has connected and logged in to the server.
     *
     * @param session
     *            the session assigned to the client.
     */
    public default void onClientConnect(LogicClientSession session) {
    }

    /**
     * Called when a client has disconnected from the server.
     *
     * @param session
     *            the client that disconnected.
     * @param reason
     *            the reason the client disconnected.
     */
    public default void onClientDisconnect(LogicClientSession session, String reason) {
    }

    /**
     * Called when a session exception has occurred, these normally do not
     * matter as the server will kick the client.
     *
     * @param session
     *            the session that caused the exception.
     * @param throwable
     *            the throwable exception that was caught.
     */
    public default void onSessionException(LogicClientSession session, Throwable throwable) {
    }

    /**
     * Called when an address is blocked by the server.
     *
     * @param address
     *            the address that was blocked.
     * @param reason
     *            the reason the address was blocked.
     * @param time
     *            how long the address is blocked for (Note: -1 is permanent).
     */
    public default void onAddressBlocked(InetAddress address, String reason, long time) {
    }

    /**
     * Called when an address has been unblocked by the server.
     *
     * @param address
     *            the address that has been unblocked.
     */
    public default void onAddressUnblocked(InetAddress address) {
    }

    /**
     * Called when a message is received by a client.
     *
     * @param session
     *            the client that received the packet.
     * @param record
     *            the received record.
     * @param packet
     *            the received packet.
     */
    public default void onAcknowledge(LogicClientSession session, Record record, EncapsulatedPacket packet) {
    }

    /**
     * Called when a message is not received by a client.
     *
     * @param session
     *            the client that lost the packet.
     * @param record
     *            the lost record.
     * @param packet
     *            the lost packet.
     */
    public default void onNotAcknowledge(LogicClientSession session, Record record, EncapsulatedPacket packet) {
    }

    /**
     * Called when a packet has been received from a client and is ready to be
     * handled.
     *
     * @param session
     *            the client that sent the packet.
     * @param packet
     *            the packet received from the client.
     * @param channel
     *            the channel the packet was sent on.
     */
    public default void handleMessage(LogicClientSession session, RakNetPacket packet, int channel) {
    }

    /**
     * Called when a packet with an ID below the user enumeration
     * (<code>ID_USER_PACKET_ENUM</code>) cannot be handled by the session
     * because it is not programmed to handle it. This function can be used to
     * add missing features from the regular RakNet protocol that are absent in
     * JRakNet if needed.
     *
     * @param session
     *            the client that sent the packet.
     * @param packet
     *            the packet received from the client.
     * @param channel
     *            the channel the packet was sent on.
     */
    public default void handleUnknownMessage(LogicClientSession session, RakNetPacket packet, int channel) {
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
     * matter as long as the server handles them on its own.
     *
     * @param address
     *            the address that caused the exception.
     * @param throwable
     *            the throwable exception that was caught.
     */
    public default void onHandlerException(InetSocketAddress address, Throwable throwable) {
        throwable.printStackTrace();
    }

    /**
     * Called when an exception is caught in the external thread the server is
     * running on, this method is only called when the server is started through
     * <code>startThreaded()</code>.
     *
     * @param throwable
     *            the throwable exception that was caught
     */
    public default void onThreadException(Throwable throwable) {
        throwable.printStackTrace();
    }

    /**
     * 处理一些raw message，比如统计、握手相关
     * @param packet
     * @param sender
     */
    public  default  void handleRawMessage(RakNetPacket packet, InetSocketAddress sender) {

    }
}
