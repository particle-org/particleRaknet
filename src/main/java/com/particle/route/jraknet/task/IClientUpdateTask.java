package com.particle.route.jraknet.task;

/**
 * The interface Cycle task.
 */
public interface IClientUpdateTask {
    /**
     * Update operate.
     */
    void update();

    /**
     * 判断该Task是否运行结束，若结束则停止循环
     *
     * @return Task状态
     */
    boolean isStopping();

    /**
     * 获取任务ID
     *
     * @return 任务id
     */
    long getUUID();
}
