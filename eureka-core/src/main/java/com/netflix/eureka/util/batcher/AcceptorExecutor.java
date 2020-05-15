package com.netflix.eureka.util.batcher;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.StatsTimer;
import com.netflix.servo.monitor.Timer;
import com.netflix.servo.stats.StatsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.netflix.eureka.Names.METRIC_REPLICATION_PREFIX;

/**
 * 任务接收执行器
 * 一个带有内部线程的活动对象，该对象接受来自客户端的任务，并以基于pull的方式将其分配给worker。
 * 工作者在有空时会明确要求一个或一批项目。
 * 这样可以保证要处理的数据始终是最新的，并且不会进行过时的数据处理。
 * <p>
 * 任务标识
 * 传递给处理的每个任务都有一个相应的任务ID。此ID用于删除重复项（将旧副本替换为新副本）。
 * <p>
 * 重新处理
 * 如果worker的数据处理失败，并且故障本质上是暂时的，则worker会将任务放回任务接收执行器。
 * 该数据将与当前工作负载合并，如果已经收到较新的版本，则可能会将其丢弃。
 * <p>
 * An active object with an internal thread accepting tasks from clients, and dispatching them to
 * workers in a pull based manner. Workers explicitly request an item or a batch of items whenever they are
 * available. This guarantees that data to be processed are always up to date, and no stale data processing is done.
 *
 * <h3>Task identification</h3>
 * Each task passed for processing has a corresponding task id. This id is used to remove duplicates (replace
 * older copies with newer ones).
 *
 * <h3>Re-processing</h3>
 * If data processing by a worker failed, and the failure is transient in nature, the worker will put back the
 * task(s) back to the {@link AcceptorExecutor}. This data will be merged with current workload, possibly discarded if
 * a newer version has been already received.
 *
 * @author Tomasz Bak
 */
class AcceptorExecutor<ID, T> {

    private static final Logger logger = LoggerFactory.getLogger(AcceptorExecutor.class);

    /**
     * 任务接收执行者编号
     */
    private final String id;

    /**
     * 待执行队列最大数量
     */
    private final int maxBufferSize;

    /**
     * 单个批量任务包含任务最大数量
     */
    private final int maxBatchingSize;

    /**
     * 批量任务等待最大延迟时长，单位：毫秒
     */
    private final long maxBatchingDelay;

    /**
     * 是否关闭
     */
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /**
     * 接收任务队列
     */
    private final BlockingQueue<TaskHolder<ID, T>> acceptorQueue = new LinkedBlockingQueue<>();
    /**
     * 重新执行任务队列
     */
    private final BlockingDeque<TaskHolder<ID, T>> reprocessQueue = new LinkedBlockingDeque<>();

    /**
     * 接收任务线程
     */
    private final Thread acceptorThread;

    /**
     * 待执行任务映射
     */
    private final Map<ID, TaskHolder<ID, T>> pendingTasks = new HashMap<>();
    /**
     * 待执行队列
     */
    private final Deque<ID> processingOrder = new LinkedList<>();

    /**
     * 单任务工作请求信号量
     */
    private final Semaphore singleItemWorkRequests = new Semaphore(0);
    /**
     * 单任务工作队列
     */
    private final BlockingQueue<TaskHolder<ID, T>> singleItemWorkQueue = new LinkedBlockingQueue<>();

    /**
     * 批量任务工作请求信号量
     */
    private final Semaphore batchWorkRequests = new Semaphore(0);
    /**
     * 批量任务工作队列
     */
    private final BlockingQueue<List<TaskHolder<ID, T>>> batchWorkQueue = new LinkedBlockingQueue<>();

    /**
     * 网络通信整形器，用于计算失败的任务还需延长多长时间
     */
    private final TrafficShaper trafficShaper;

    /*
     * Metrics
     */
    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptedTasks", description = "Number of accepted tasks", type =
            DataSourceType.COUNTER)
    volatile long acceptedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "replayedTasks", description = "Number of replayedTasks tasks", type
            = DataSourceType.COUNTER)
    volatile long replayedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "expiredTasks", description = "Number of expired tasks", type =
            DataSourceType.COUNTER)
    volatile long expiredTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "overriddenTasks", description = "Number of overridden tasks", type =
            DataSourceType.COUNTER)
    volatile long overriddenTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueOverflows", description = "Number of queue overflows", type =
            DataSourceType.COUNTER)
    volatile long queueOverflows;

    private final Timer batchSizeMetric;

    AcceptorExecutor(String id,
                     int maxBufferSize,
                     int maxBatchingSize,
                     long maxBatchingDelay,
                     long congestionRetryDelayMs,
                     long networkFailureRetryMs) {
        this.id = id;
        this.maxBufferSize = maxBufferSize;
        this.maxBatchingSize = maxBatchingSize;
        this.maxBatchingDelay = maxBatchingDelay;

        // 创建网络通信整形器
        this.trafficShaper = new TrafficShaper(congestionRetryDelayMs, networkFailureRetryMs);

        // 创建任务接收处理器线程组，单线程任务组
        ThreadGroup threadGroup = new ThreadGroup("eurekaTaskExecutors");
        // 创建接受者线程，启动进行分发
        this.acceptorThread = new Thread(threadGroup, new AcceptorRunner(), "TaskAcceptor-" + id);
        this.acceptorThread.setDaemon(true);
        this.acceptorThread.start();

        final double[] percentiles = {50.0, 95.0, 99.0, 99.5};
        final StatsConfig statsConfig = new StatsConfig.Builder()
                .withSampleSize(1000)
                .withPercentiles(percentiles)
                .withPublishStdDev(true)
                .build();
        final MonitorConfig config = MonitorConfig.builder(METRIC_REPLICATION_PREFIX + "batchSize").build();
        this.batchSizeMetric = new StatsTimer(config, statsConfig);
        try {
            Monitors.registerObject(id, this);
        } catch (Throwable e) {
            logger.warn("Cannot register servo monitor for this object", e);
        }
    }

    /**
     * 处理任务，添加到接受者队列，接收过的任务+1
     */
    void process(ID id, T task, long expiryTime) {
        acceptorQueue.add(new TaskHolder<ID, T>(id, task, expiryTime));
        acceptedTasks++;
    }

    /**
     * 重新处理批量的任务，添加进重新处理队列，重试任务次数加次数，网络整形器添加处理结果
     */
    void reprocess(List<TaskHolder<ID, T>> holders, ProcessingResult processingResult) {
        reprocessQueue.addAll(holders);
        replayedTasks += holders.size();
        trafficShaper.registerFailure(processingResult);
    }

    /**
     * 重新处理单个任务，添加进重新处理队列，重试任务次数+1，网络整形器添加处理结果
     */
    void reprocess(TaskHolder<ID, T> taskHolder, ProcessingResult processingResult) {
        reprocessQueue.add(taskHolder);
        replayedTasks++;
        trafficShaper.registerFailure(processingResult);
    }

    /**
     * 单任务处理器请求工作，单个工作信号量-1
     */
    BlockingQueue<TaskHolder<ID, T>> requestWorkItem() {
        singleItemWorkRequests.release();
        return singleItemWorkQueue;
    }

    /**
     * 批量任务处理器请求工作，批量工作信号量-1
     */
    BlockingQueue<List<TaskHolder<ID, T>>> requestWorkItems() {
        batchWorkRequests.release();
        return batchWorkQueue;
    }

    /**
     * 关闭任务执行接受分发者
     */
    void shutdown() {
        // 未关闭则关闭，并中断分发线程
        if (isShutdown.compareAndSet(false, true)) {
            Monitors.unregisterObject(id, this);
            acceptorThread.interrupt();
        }
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptorQueueSize", description = "Number of tasks waiting in the " +
            "acceptor queue", type = DataSourceType.GAUGE)
    public long getAcceptorQueueSize() {
        return acceptorQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "reprocessQueueSize", description = "Number of tasks waiting in the " +
            "reprocess queue", type = DataSourceType.GAUGE)
    public long getReprocessQueueSize() {
        return reprocessQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueSize", description = "Task queue size", type =
            DataSourceType.GAUGE)
    public long getQueueSize() {
        return pendingTasks.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "pendingJobRequests", description = "Number of worker threads " +
            "awaiting job assignment", type = DataSourceType.GAUGE)
    public long getPendingJobRequests() {
        return singleItemWorkRequests.availablePermits() + batchWorkRequests.availablePermits();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "availableJobs", description = "Number of jobs ready to be taken by " +
            "the workers", type = DataSourceType.GAUGE)
    public long workerTaskQueueSize() {
        return singleItemWorkQueue.size() + batchWorkQueue.size();
    }

    /**
     * 接收处理线程
     */
    class AcceptorRunner implements Runnable {
        @Override
        public void run() {
            long scheduleTime = 0;
            while (!isShutdown.get()) {
                try {
                    // 处理完输入队列( 接收队列 + 重新执行队列 )
                    drainInputQueues();

                    // 待执行任务数量
                    int totalItems = processingOrder.size();

                    // 网络整形器计算需要延迟的时间
                    long now = System.currentTimeMillis();
                    // 小于当前时间，重新计算需要延迟的时间
                    // 大于等于当前时间，不需要进行整型，延迟等待调度
                    if (scheduleTime < now) {
                        scheduleTime = now + trafficShaper.transmissionDelay();
                    }
                    // 计算后的延迟时间仍小于等于当前时间，说明需要进行任务调度了
                    if (scheduleTime <= now) {
                        // 调度批量任务
                        assignBatchWork();
                        // 调度单任务
                        assignSingleItemWork();
                    }

                    // If no worker is requesting data or there is a delay injected by the traffic shaper,
                    // sleep for some time to avoid tight loop.
                    // 任务执行器无新增任务请求，正在忙碌处理之前的任务
                    // 或者任务延迟调度。睡眠 10 秒，避免资源浪费
                    if (totalItems == processingOrder.size()) {
                        Thread.sleep(10);
                    }
                } catch (InterruptedException ex) {
                    // Ignore
                } catch (Throwable e) {
                    // Safe-guard, so we never exit this loop in an uncontrolled way.
                    logger.warn("Discovery AcceptorThread error", e);
                }
            }
        }

        /**
         * 待执行任务映射大于缓冲区
         * 返回true，否则返回false
         */
        private boolean isFull() {
            return pendingTasks.size() >= maxBufferSize;
        }

        /**
         * 处理输入队列
         */
        private void drainInputQueues() throws InterruptedException {
            do {
                // 处理需要重新执行的任务队列
                drainReprocessQueue();
                // 处理接收者任务队列
                drainAcceptorQueue();

                if (!isShutdown.get()) {
                    // If all queues are empty, block for a while on the acceptor queue
                    // 所有队列为空，等待 10 ms，看接收队列是否有新任务
                    if (reprocessQueue.isEmpty()
                            && acceptorQueue.isEmpty()
                            && pendingTasks.isEmpty()) {
                        TaskHolder<ID, T> taskHolder = acceptorQueue.poll(10, TimeUnit.MILLISECONDS);
                        if (taskHolder != null) {
                            // 接受队列有新任务，映射为待执行任务
                            appendTaskHolder(taskHolder);
                        }
                    }
                }
                // 只要一个队列不为空 或者 待执行任务映射不为空，就继续执行
            } while (!reprocessQueue.isEmpty() || !acceptorQueue.isEmpty() || pendingTasks.isEmpty());
        }

        /**
         * 处理接收任务队列，主要队列存在，就处理映射
         */
        private void drainAcceptorQueue() {
            while (!acceptorQueue.isEmpty()) {
                appendTaskHolder(acceptorQueue.poll());
            }
        }

        /**
         * 处理重新执行队列
         */
        private void drainReprocessQueue() {
            long now = System.currentTimeMillis();
            // 队列存在内容，映射不满就继续执行
            while (!reprocessQueue.isEmpty() && !isFull()) {
                // 把队列中的取出，不过期不重复的，添加进映射，同时把任务id添加进待执行队列队头
                TaskHolder<ID, T> taskHolder = reprocessQueue.pollLast();
                ID id = taskHolder.getId();
                if (taskHolder.getExpiryTime() <= now) {
                    // 过期
                    expiredTasks++;
                } else if (pendingTasks.containsKey(id)) {
                    // 重复
                    overriddenTasks++;
                } else {
                    pendingTasks.put(id, taskHolder);
                    // 添加到队头，先执行
                    processingOrder.addFirst(id);
                }
            }
            // 如果缓冲区满了，丢弃所有需要重新执行的任务，并计数
            if (isFull()) {
                queueOverflows += reprocessQueue.size();
                reprocessQueue.clear();
            }
        }

        /**
         * 把接收队列的都添加到映射中
         */
        private void appendTaskHolder(TaskHolder<ID, T> taskHolder) {
            // 如果缓冲区满了，移除映射中较老的任务
            if (isFull()) {
                pendingTasks.remove(processingOrder.poll());
                queueOverflows++;
            }
            // 把任务放到映射和待执行队列
            // 如果之前已有相同的id在里面，重写次数+1
            TaskHolder<ID, T> previousTask = pendingTasks.put(taskHolder.getId(), taskHolder);
            if (previousTask == null) {
                processingOrder.add(taskHolder.getId());
            } else {
                overriddenTasks++;
            }
        }

        /**
         * 调度单个任务，从待执行队列和映射中拿去单个任务，添加到单个任务工作队列中
         */
        void assignSingleItemWork() {
            if (!processingOrder.isEmpty()) {
                // 获取 单任务工作请求信号量
                if (singleItemWorkRequests.tryAcquire(1)) {
                    long now = System.currentTimeMillis();
                    while (!processingOrder.isEmpty()) {
                        ID id = processingOrder.poll();
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) {
                            singleItemWorkQueue.add(holder);
                            return;
                        }
                        expiredTasks++;
                    }
                    // 获取不到单任务，释放请求信号量
                    singleItemWorkRequests.release();
                }
            }
        }

        /**
         * 调度批量任务
         */
        void assignBatchWork() {
            // 判断是否有足够的任务要批量处理
            if (hasEnoughTasksForNextBatch()) {
                // 批量任务工作信号量请求+1
                if (batchWorkRequests.tryAcquire(1)) {
                    long now = System.currentTimeMillis();
                    // 获取需要处理的批量任务数量，并拿去批量任务
                    int len = Math.min(maxBatchingSize, processingOrder.size());
                    List<TaskHolder<ID, T>> holders = new ArrayList<>(len);
                    while (holders.size() < len && !processingOrder.isEmpty()) {
                        ID id = processingOrder.poll();
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) {
                            holders.add(holder);
                        } else {
                            // 过期的直接可以不执行了
                            expiredTasks++;
                        }
                    }
                    // 如果没有任务，释放信号量
                    if (holders.isEmpty()) {
                        batchWorkRequests.release();
                    } else {
                        batchSizeMetric.record(holders.size(), TimeUnit.MILLISECONDS);
                        // 否则把这个批量任务添加到批量处理工作队列中
                        batchWorkQueue.add(holders);
                    }
                }
            }
        }

        /**
         * 是否有足够的任务用于下一次批量处理
         */
        private boolean hasEnoughTasksForNextBatch() {
            // 待执行队列为空，不够
            if (processingOrder.isEmpty()) {
                return false;
            }
            // 映射已满，足够了
            if (pendingTasks.size() >= maxBufferSize) {
                return true;
            }

            // 下一个任务已经到达到达批量任务处理最大等待延迟，就要进行批量处理了
            TaskHolder<ID, T> nextHolder = pendingTasks.get(processingOrder.peek());
            long delay = System.currentTimeMillis() - nextHolder.getSubmitTimestamp();
            return delay >= maxBatchingDelay;
        }
    }
}
