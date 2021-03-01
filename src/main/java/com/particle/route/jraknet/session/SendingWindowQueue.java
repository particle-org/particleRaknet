package com.particle.route.jraknet.session;

import com.particle.route.jraknet.protocol.message.SendedEncapsulatePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SendingWindowQueue {
    private static final Logger log = LoggerFactory.getLogger(SendingWindowQueue.class);

    //发包窗口大小
    private static final int QUEUE_SIZE = 64;
    private static final int QUICK_INDEX_MASK = 63;

    //队列内容
    private final SendedEncapsulatePacket[] sendingWindow = new SendedEncapsulatePacket[QUEUE_SIZE];
    private int writeIndex = 0;
    private int waitingIndex = 0;

    public boolean isFull() {
        return sendingWindow[QUICK_INDEX_MASK & (writeIndex + 1)] != null;
    }

    public boolean offer(SendedEncapsulatePacket packet) {
        this.writeIndex = QUICK_INDEX_MASK & (this.writeIndex + 1);

        //full
        if (sendingWindow[this.writeIndex] != null) {
            this.writeIndex = QUICK_INDEX_MASK & (this.writeIndex - 1 + QUEUE_SIZE);

            return false;
        }

        this.sendingWindow[this.writeIndex] = packet;

        reIndex();

        return true;
    }

    /**
     * 移除队列中的元素
     * 情况1: 移除元素就是waitingIndex的元素，计算offset后就可以直接移除
     * 情况2: 移除元素是队列中的某个元素，且换算后下标正确，计算offset后就可以直接移除
     *
     * @param recordIndex
     * @return
     */
    public SendedEncapsulatePacket remove(int recordIndex) {
        //排除队列为空的情况
        if (this.sendingWindow[this.waitingIndex] != null) {
            int offset = (recordIndex - sendingWindow[waitingIndex].getIndex() + waitingIndex) & QUICK_INDEX_MASK;

            if (sendingWindow[offset] != null && sendingWindow[offset].getIndex() == recordIndex) {
                SendedEncapsulatePacket sendedEncapsulatePacket = sendingWindow[offset];

                sendingWindow[offset] = null;

                //滚动重发指针
                reIndex();

                return sendedEncapsulatePacket;
            }
        }

        return null;
    }

    private void reIndex() {
        //这里需要保证，队列满时不会出现this.waitingIndex != this.writeIndex
        while (sendingWindow[this.waitingIndex] == null && this.waitingIndex != this.writeIndex) {
            waitingIndex = QUICK_INDEX_MASK & (waitingIndex + 1);
        }
    }

    public SendingIterator getIterator() {
        return new SendingIterator(waitingIndex, writeIndex);
    }

    public class SendingIterator {
        private int cacheIndex;

        private int writeIndex;

        //遍历情况的判断
        private boolean looped;

        public SendingIterator(int cacheIndex, int writeIndex) {
            this.cacheIndex = cacheIndex;
            this.writeIndex = writeIndex;

            //如果起始元素为空，则代表队列为空
            this.looped = sendingWindow[cacheIndex] == null && this.cacheIndex == this.writeIndex;
        }

        public SendedEncapsulatePacket next() {
            SendedEncapsulatePacket current = null;

            while (!looped && current == null) {
                if (sendingWindow[cacheIndex] != null) {
                    current = sendingWindow[cacheIndex];
                }

                looped = cacheIndex == writeIndex;

                cacheIndex = QUICK_INDEX_MASK & (cacheIndex + 1);
            }

            return current;
        }
    }
}
