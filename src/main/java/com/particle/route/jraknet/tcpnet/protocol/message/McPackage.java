package com.particle.route.jraknet.tcpnet.protocol.message;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.RakNetPacket;
import com.particle.route.jraknet.protocol.Failable;
import com.particle.route.jraknet.protocol.message.CustomPacket;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import com.particle.route.jraknet.protocol.message.Sizable;
import com.particle.route.jraknet.protocol.message.acknowledge.Record;
import com.particle.route.jraknet.tcpnet.protocol.TcpMessageIdentifier;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;

public class McPackage extends RakNetPacket implements Failable, Sizable {

    private static  final Logger logger = LoggerFactory.getLogger(McPackage.class);

    public int sequenceNumber;
    public InetSocketAddress address;
    public long clientGuid;

    private boolean failed;

    public ArrayList<McEncapsulatedPacket> messages;

    private final ArrayList<McEncapsulatedPacket> ackMessages;

    public static final int SEQUENCE_NUMBER_LENGTH = 0x03;

    /**
     * 为了少拷贝，在encode的时候，将所有的ByteBuf通过wraped形式，组装起来，赋给此对象
     * 只有encode的时候才会用到
     */
    public ByteBuf encodeByteBuf = null;

    public McPackage() {
        super(TcpMessageIdentifier.TCP_MC_IDENTIFIER);
        this.messages = new ArrayList<McEncapsulatedPacket>();
        this.ackMessages = new ArrayList<McEncapsulatedPacket>();
    }

    public McPackage(Packet packet) {
        super(packet);
        this.messages = new ArrayList<McEncapsulatedPacket>();
        this.ackMessages = new ArrayList<McEncapsulatedPacket>();
    }

    @Override
    public void encode() {
        try {
            this.writeTriadLE(sequenceNumber);
            this.writeAddress(address);
            this.writeLong(clientGuid);
            ByteBuf assembleByteBuf = null;
            boolean isFirst = true;
            for (McEncapsulatedPacket packet : messages) {
                /*
                 * We have to use wrap our buffer around a packet otherwise data
                 * will be written incorrectly due to how Netty's ByteBufs work.
                 */
//                packet.buffer = new Packet(this.buffer());
                packet.buffer = new Packet();

                // Set ACK record if the reliability requires an ACK receipt
                /*if (packet.reliability.requiresAck()) {
                    packet.ackRecord = new Record(sequenceNumber);
                    ackMessages.add(packet);
                }*/

                // Encode packet
                packet.encode();

                // 组装ByteBuf
                if (isFirst) {
                    assembleByteBuf = Unpooled.wrappedBuffer(packet.buffer.buffer());
                    isFirst = false;
                } else {
                    assembleByteBuf = Unpooled.wrappedBuffer(assembleByteBuf, packet.buffer.buffer());
                }

                // Nullify buffer so it cannot be abused
                packet.buffer = null;
            }
            // 组装ByteBuf
            if (assembleByteBuf != null) {
                encodeByteBuf = Unpooled.wrappedBuffer(this.buffer(),assembleByteBuf);
            }

        } catch (UnknownHostException e) {
            this.sequenceNumber = 0;
            this.address = null;
            this.clientGuid = 0;
            this.clear();
            failed = true;
        }
    }

    @Override
    public void decode() {
        try {
            this.sequenceNumber = this.readTriadLE();
            this.address = this.readAddress();
            this.clientGuid = this.readLong();
            while (this.remaining() >= EncapsulatedPacket.MINIMUM_BUFFER_LENGTH) {
                // Create encapsulated packet so it can be decoded later
                McEncapsulatedPacket packet = new McEncapsulatedPacket();

                /*
                 * We have to use wrap our buffer around a packet otherwise data
                 * will be read incorrectly due to how Netty's ByteBufs work.
                 */
                packet.buffer = new Packet(this.buffer());

                // Decode packet
                packet.decode();

                // Set ACK record if the reliability requires an ACK receipt
                if (packet.reliability.requiresAck()) {
                    packet.ackRecord = new Record(sequenceNumber);
                    ackMessages.add(packet);
                }

                // Nullify buffer so it can not be abused
                packet.buffer = null;

                // Add packet to list
                messages.add(packet);
            }

        } catch (UnknownHostException e) {
            this.sequenceNumber = 0;
            this.address = null;
            this.clientGuid = 0;
            this.clientGuid = 0;
            this.clear();
            failed = true;
        }
    }

    @Override
    public boolean failed() {
        return failed;
    }

    @Override
    public int calculateSize() {
        int packetSize = 1; // Packet ID
        packetSize += SEQUENCE_NUMBER_LENGTH;
        for (EncapsulatedPacket message : this.messages) {
            packetSize += message.calculateSize();
        }
        return packetSize;
    }

    /**
     * @return <code>true</code> if the packet contains any unreliable messages.
     */
    public boolean containsUnreliables() {
        if (messages.size() <= 0) {
            return false; // Nothing to check
        }

        for (EncapsulatedPacket encapsulated : this.messages) {
            if (!encapsulated.reliability.isReliable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes all the unreliable messages from the packet.
     */
    public void removeUnreliables() {
        if (messages.size() <= 0) {
            return; // Nothing to remove
        }

        ArrayList<EncapsulatedPacket> unreliables = new ArrayList<EncapsulatedPacket>();
        for (EncapsulatedPacket encapsulated : this.messages) {
            if (!encapsulated.reliability.isReliable()) {
                unreliables.add(encapsulated);
            }
        }
        messages.removeAll(unreliables);
    }

    /**
     * @return the size of a <code>CustomPacket</code> without any extra data
     *         written to it.
     */
    public static int calculateDummy() {
        CustomPacket custom = new CustomPacket();
        custom.encode();
        return custom.size();
    }

    /**
     * 针对tcp，其session不一致，ack的处理放外边
     * @return
     */
    public ArrayList<McEncapsulatedPacket> getAckMessages() {
        return ackMessages;
    }
}
