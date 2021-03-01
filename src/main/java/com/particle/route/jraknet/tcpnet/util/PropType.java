package com.particle.route.jraknet.tcpnet.util;

public interface PropType {

    /**
     * netty idle最长读idle时间，单位是秒
     */
    public static final int readerIdleTime = 5;

    /**
     * netty idle最长写idle时间，单位是秒
     */
    public static final int writerIdleTime = 4;

    /**
     * netty idle最长idle时间
     */
    public static final int allIdleTime = 0;


    /**
     * 客户端最多丢失连接次数
     */
    public static final int MAX_CLIENT_IDLE_READ_TIMES = 3;

    /**
     * 服务端最多丢失连接次数
     */
    public static final int MAX_SERVER_IDLE_READ_TIMES = 5;
}
