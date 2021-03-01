package com.particle.route.jraknet.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

public class UpdaterManager {
    private static final Logger log = LoggerFactory.getLogger(UpdaterManager.class);

    private ExecutorService service;

    private Set<Long> tasks = new HashSet<>();

    private int count = 0;
    private int lastSpeed = 0;

    public UpdaterManager(String name) {
        this.service =  Executors.newFixedThreadPool(2);

        new Thread(() -> {
            while (true) {
                lastSpeed = count / 5;

                if (lastSpeed < 1000 && lastSpeed != 0) {
                    log.warn("{} update speed too slow! {}/s", name, lastSpeed);
                }

                count = 0;

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void addTask(IClientUpdateTask task) {
        if(!this.tasks.contains(task.getUUID())) {
            this.tasks.add(task.getUUID());
            this.service.execute(new CycleTask(task));
        }
    }

    private class CycleTask implements Runnable {
        private IClientUpdateTask task;

        CycleTask(IClientUpdateTask task) {
            this.task = task;
        }

        @Override
        public void run() {
            if (this.task.isStopping()) {
                tasks.remove(this.task.getUUID());
            } else {
                try {
                    this.task.update();
                } catch (Exception e) {
                    log.error("RakNet Client {} Update Exception", task.getUUID());
                    log.error("Exceptions", e);
                }

                count++;

                service.execute(this);
            }
        }
    }
}
