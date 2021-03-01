package com.particle.route.jraknet.tcpnet.protocol.message;

import com.particle.route.jraknet.Packet;
import com.particle.route.jraknet.protocol.Reliability;
import com.particle.route.jraknet.protocol.message.EncapsulatedPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McEncapsulatedPacket extends EncapsulatedPacket {

    private static final Logger logger = LoggerFactory.getLogger(McEncapsulatedPacket.class);

    @Override
    public void encode() {
        buffer.writeByte((byte) ((reliability.getId() << RELIABILITY_POSITION) | (split ? FLAG_SPLIT : 0)));
        buffer.writeInt(payload.size());
        if (reliability.requiresAck() && ackRecord == null) {
            logger.error("No ACK record ID set for encapsulated packet with reliability " + reliability);
        }

        if (reliability.isReliable()) {
            buffer.writeTriadLE(messageIndex);
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            buffer.writeTriadLE(orderIndex);
            buffer.writeUnsignedByte(orderChannel);
        }

        if (split == true) {
            buffer.writeInt(splitCount);
            buffer.writeUnsignedShort(splitId);
            buffer.writeInt(splitIndex);
        }
//        buffer.write(payload.array());
        // 业务传递上来的是RakNetPackage,已经读取了第一位
        payload.buffer().readerIndex(0);
        ByteBuf newByteBuf = Unpooled.wrappedBuffer(buffer.buffer(), payload.buffer());
        buffer = new Packet(newByteBuf);
    }

    @Override
    public void decode() {
        short flags = buffer.readUnsignedByte();
        this.reliability = Reliability.lookup((byte) ((flags & FLAG_RELIABILITY) >> RELIABILITY_POSITION));
        this.split = (flags & FLAG_SPLIT) > 0;
        int length = buffer.readInt();
        if (reliability.isReliable()) {
            this.messageIndex = buffer.readTriadLE();
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            this.orderIndex = buffer.readTriadLE();
            this.orderChannel = buffer.readByte();
        }

        if (split == true) {
            this.splitCount = buffer.readInt();
            this.splitId = buffer.readUnsignedShort();
            this.splitIndex = buffer.readInt();
        }

//        this.payload = new Packet(Unpooled.copiedBuffer(buffer.read(length)));
        int startIndex = buffer.buffer().readerIndex();
        // 通过slice的方式，减少一次拷贝
        length = length > buffer.buffer().readableBytes() ?
                buffer.buffer().readableBytes() : length;
        this.payload = new Packet(buffer.buffer().retainedSlice(startIndex, length));
        // 由于slice不会增加原有的readerIndex，手动增加
        buffer.buffer().readerIndex(startIndex + length);
    }
}
