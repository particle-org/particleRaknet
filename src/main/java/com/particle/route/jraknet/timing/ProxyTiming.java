package com.particle.route.jraknet.timing;

public class ProxyTiming {

    private static ProxyTiming instance = new ProxyTiming();

    //这里采用累加的方式，获取所有session的数据
    private long serverPacketSendCounting = 0;
    private long serverPacketReceivedCounting = 0;
    private long serverPacketResendCounting = 0;
    private long serverPacketDropCounting = 0;
    private long serverSessionUpdateCounting = 0;

    private long serverPacketSendCache = 0;
    private long serverPacketReceivedCache = 0;
    private long serverPacketResendCache = 0;
    private long serverPacketDropCache = 0;
    private long serverSessionUpdateCache = 0;

    private boolean isRunning = true;

    private ProxyTiming(){
        //主动刷新数据
        Thread updatingThread = new Thread(() -> {
            while (isRunning) {
                this.serverPacketSendCounting = this.serverPacketSendCache / 10;
                this.serverPacketSendCache = 0;
                this.serverPacketReceivedCounting = this.serverPacketReceivedCache / 10;
                this.serverPacketReceivedCache = 0;
                this.serverPacketResendCounting = this.serverPacketResendCache / 10;
                this.serverPacketResendCache = 0;
                this.serverPacketDropCounting = this.serverPacketDropCache / 10;
                this.serverPacketDropCache = 0;
                this.serverSessionUpdateCounting = this.serverSessionUpdateCache / 10;
                this.serverSessionUpdateCache = 0;

                //每10秒求一次均值
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        updatingThread.setName("Timing update thread");
        updatingThread.start();
    }

    public static ProxyTiming getInstance() {
        return ProxyTiming.instance;
    }

    public void recorderServerPacketSendCounting(long value) {
        this.serverPacketSendCache += value;
    }

    public void recorderServerPacketReceivedCounting(long value) {
        this.serverPacketReceivedCache += value;
    }

    public void recorderServerPacketResendCounting(long value) {
        this.serverPacketResendCache += value;
    }

    public void recorderServerPacketDropCounting(long value) {
        this.serverPacketDropCache += value;
    }

    public void recorderServerSessionUpdateCounting() {
        this.serverSessionUpdateCache++;
    }

    public long getServerPacketSendCounting() {
        return serverPacketSendCounting;
    }

    public long getServerPacketReceivedCounting() {
        return serverPacketReceivedCounting;
    }

    public long getServerPacketResendCounting() {
        return serverPacketResendCounting;
    }

    public long getServerPacketDropCounting() {
        return serverPacketDropCounting;
    }

    public long getServerSessionUpdateCounting() {
        return serverSessionUpdateCounting;
    }

    public void shutdown() {
        this.isRunning = false;
    }
}
