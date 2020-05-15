package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用于将本地instanceinfo更新和复制到远程服务器的任务。
 * 此任务的属性是：
 * - 配置有单个更新线程以确保对远程服务器进行顺序更新
 * - 可以通过onDemandUpdate（）按需计划更新任务
 * - 任务处理的速率受burstSize的限制
 * - 新更新总是在较早的更新任务之后自动计划任务。但是，如果启动了按需任务，则计划的自动更新任务将被丢弃（并且将在新的*按需更新之后安排新的任务）。
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 * is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 * on-demand update).
 *
 * @author dliu
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    /**
     * 客户端
     */
    private final DiscoveryClient discoveryClient;

    /**
     * 实力信息
     */
    private final InstanceInfo instanceInfo;

    /**
     * 复制的周期
     */
    private final int replicationIntervalSeconds;

    /**
     * 同步线程池
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 定时任务的引用
     */
    private final AtomicReference<Future> scheduledPeriodicRef;

    /**
     * 是否开始了复制
     */
    private final AtomicBoolean started;

    /**
     * 分钟级别的速率限流
     */
    private final RateLimiter rateLimiter;

    /**
     * 令牌桶大小
     */
    private final int burstSize;

    /**
     * 允许的每分钟次数
     */
    private final int allowedRatePerMinute;

    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds
            , int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;

        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {
            // 为了第一次注册，设置为dirty，可以同步数据
            instanceInfo.setIsDirty();  // for initial register
            // 启动定时任务
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        // 等待最多3s就杀死线程池
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }

    /**
     * 同步实例信息
     */
    public boolean onDemandUpdate() {
        // 未限流
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            // 未关闭
            if (!scheduler.isShutdown()) {
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");

                        // 获取定时任务，如果有未完成的直接取消
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of" +
                                    " on demand update");
                            latestPeriodic.cancel(false);
                        }

                        // 再重新执行
                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    public void run() {
        try {
            // 刷新当前的实例信息，如果状态改变了，设置新的状态，并设置isDirty为true，并通知监听器
            discoveryClient.refreshInstanceInfo();

            // 获取改变时间，如果不为空，重新注册，并将改变时间置空
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                discoveryClient.register();
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            // 再次启动定时任务
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

}
