package com.particle.route.jraknet.tcpnet.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class TcpMessageIdentifier {

    private static final Logger logger = LoggerFactory.getLogger(TcpMessageIdentifier.class);

    /**
     * tcp的ping连接
     */
    public static final short TCP_PING = 0x01;
    /**
     * tcp的pong连接
     */
    public static final short TCP_PONE = 0x02;

    /**
     * tcp新的连接
     */
    public static final short TCP_CONNECT = 0x03;
    /**
     * tcp连接已存在-error
     */
    public static final short TCP_CONNECT_EXISTED = 0x04;
    /**
     * tcp连接不存在
     */
    public static final short TCP_CONNECTION_UNEXISTED = 0x05;
    /**
     * tcp连接失败
     */
    public static final short TCP_CONNECT_FAILED = 0x06;
    /**
     * tcp连接成功
     */
    public static final short TCP_CONNECT_SUCCEED = 0x07;
    /**
     * tcp关掉连接
     */
    public static final short TCP_DISCONNECT = 0x08;
    /**
     * tcp成功关掉连接
     */
    public static final short TCP_DISCONNECTED = 0x09;
    /**
     * tcp关掉连接失败
     */
    public static final short TCP_DISCONNECT_FEAILED = 0x0A;

    /**
     * tcp连接握手，表示最终连接成功
     */
    public static final short TCP_CONNECT_HANDSHAKE = 0x0B;

    /**
     * 新的用户连接到游戏
     */
    public static final short TCP_NEW_SESSION = 0x20;
    /**
     * session已存在-error
     */
    public static final short TCP_NEW_SESSION_EXISTED = 0x21;
    /**
     * session不存在
     */
    public static final short TCP_NEW_SESSION_UNEXISTED = 0x22;
    /**
     * 接受新的用户连接到游戏
     */
    public static final short TCP_NEW_SESSION_SUCCEED = 0x23;
    /**
     * 没有空闲的session
     */
    public static final short TCP_NO_FREE_SESSION = 0x24;
    /**
     * logicSession ip被ban掉
     */
    public static final short TCP_CONNECT_BAN = 0x25;
    /**
     * 某个用户session断开连接
     */
    public static final short TCP_REMOVE_SESSION = 0x26;
    /**
     * 某个用户session断开连接成功
     */
    public static final short TCP_REMOVE_SESSION_SUCCEED = 0x27;
    /**
     * 某个用户session断开连接失败
     */
    public static final short TCP_REMOVE_SESSION_FAILED = 0x28;

    /**
     * 某个用户session连接的最后handshake，表示连接完成
     */
    public static final short TCP_SESSION_HANDSHAKE = 0x29;

    /**
     * 某个logicSession丢失了
     */
    public static final short TCP_LOGIC_SESSION_LOST = 0x2A;

    /**
     * 没有空闲的logicSession
     */
    public static final short TCP_NO_FREE_LOGIC_SESSIONS = 0x2B;

    /**
     * mc的具体数据包头信息, ack包还是恢复原来的数据
     */
    public static final short TCP_MC_IDENTIFIER = 0x50;

    /**
     * 存储所有的tcp的包头
     */
    private static final Set<Short> tcpPackageIds = new HashSet<Short>();

    static {
        registerPackageIds();
    }

    /**
     * 注册所有的tcp的packageIds
     */
    private static  void registerPackageIds() {
        tcpPackageIds.add(TCP_PING);
        tcpPackageIds.add(TCP_PONE);

        tcpPackageIds.add(TCP_CONNECT);
        tcpPackageIds.add(TCP_CONNECT_EXISTED);
        tcpPackageIds.add(TCP_CONNECTION_UNEXISTED);
        tcpPackageIds.add(TCP_CONNECT_SUCCEED);
        tcpPackageIds.add(TCP_CONNECT_FAILED);
        tcpPackageIds.add(TCP_CONNECT_HANDSHAKE);

        tcpPackageIds.add(TCP_DISCONNECT);
        tcpPackageIds.add(TCP_DISCONNECTED);
        tcpPackageIds.add(TCP_DISCONNECT_FEAILED);

        tcpPackageIds.add(TCP_NEW_SESSION);
        tcpPackageIds.add(TCP_NEW_SESSION_EXISTED);
        tcpPackageIds.add(TCP_NEW_SESSION_UNEXISTED);
        tcpPackageIds.add(TCP_NEW_SESSION_SUCCEED);
        tcpPackageIds.add(TCP_NO_FREE_SESSION);
        tcpPackageIds.add(TCP_CONNECT_BAN);
        tcpPackageIds.add(TCP_SESSION_HANDSHAKE);

        tcpPackageIds.add(TCP_REMOVE_SESSION);
        tcpPackageIds.add(TCP_REMOVE_SESSION_SUCCEED);
        tcpPackageIds.add(TCP_REMOVE_SESSION_FAILED);
        tcpPackageIds.add(TCP_LOGIC_SESSION_LOST);
        tcpPackageIds.add(TCP_NO_FREE_LOGIC_SESSIONS);

        tcpPackageIds.add(TCP_MC_IDENTIFIER);
    }

    /**
     * 判断该packageId是否合法
     * @param pacakgeId
     * @return
     */
    public static boolean hasPackageId(short pacakgeId) {
        return tcpPackageIds.contains(pacakgeId);
    }
}
