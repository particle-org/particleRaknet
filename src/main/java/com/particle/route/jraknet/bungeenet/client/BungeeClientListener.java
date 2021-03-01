package com.particle.route.jraknet.bungeenet.client;

import io.netty.buffer.ByteBuf;


public interface BungeeClientListener {

    /**
     * 连接服务端
     * @param session
     */
    default void onConnect(BungeeClient session) {
    }

    /**
     * 服务端端来连接
     * @param session
     * @param reason
     */
    default void onDisconnect(BungeeClient session, String reason) {
    }

    /**
     * 处理数据包
     * @param session
     * @param byteBuf
     * @param channel
     */
    default void handleMessage(BungeeClient session, ByteBuf byteBuf, int channel) {
    }
}
