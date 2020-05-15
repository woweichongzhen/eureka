package com.netflix.eureka.util.batcher;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.StatsTimer;
import com.netflix.servo.stats.StatsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.netflix.eureka.Names.METRIC_REPLICATION_PREFIX;

/**
 * 任务执行器。其内部提供创建单任务和批量任务执行器的两种方法
 * <p>
 * {@link TaskExecutors} instance holds a number of worker threads that cooperate with {@link AcceptorExecutor}.
 * Each worker sends a job request to {@link AcceptorExecutor} whenever it is available, and processes it once
 * provided with a task(s).
 *
 * @author Tomasz Bak
 */
class TaskExecutors<ID, T> {

    private static final Logger logger = LoggerFactory.getLogger(TaskExecutors.class);

    private static final Map<String, TaskExecutorMetrics> registeredMonitors = new HashMap<>();

    /**
     * 是否关闭
     */
    private final AtomicBoolean isShutdown;

    /**
     * 工作线程池
     * 工作任务队列会被工作线程池并发拉取，并发执行
     */
    private final List<Thread> workerThreads;

    TaskExecutors(WorkerRunnableFactory<ID, T> workerRunnableFactory, int workerCount, AtomicBoolean isShutdown) {
        this.isShutdown = isShutdown;
        this.workerThreads = new ArrayList<>();

        // 任务处理器线程池组，启动任务处理线程
        ThreadGroup threadGroup = new ThreadGroup("eurekaTaskExecutors");
        for (int i = 0; i < workerCount; i++) {
            WorkerRunnable<ID, T> runnable = workerRunnableFactory.create(i);
            Thread workerThread = new Thread(threadGroup, runnable, runnable.getWorkerName());
            workerThreads.add(workerThread);
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    /**
     * 关闭处理线程池组
     */
    void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            // 如果未关闭，遍历终端线程
            for (Thread workerThread : workerThreads) {
                workerThread.interrupt();
            }
            registeredMonitors.forEach(Monitors::unregisterObject);
        }
    }

    static <ID, T> TaskExecutors<ID, T> singleItemExecutors(final String name,
                                                            int workerCount,
                                                            final TaskProcessor<T> processor,
                                                            final AcceptorExecutor<ID, T> acceptorExecutor) {
        final AtomicBoolean isShutdown = new AtomicBoolean();
        final TaskExecutorMetrics metrics = new TaskExecutorMetrics(name);
        registeredMonitors.put(name, metrics);
        // 创建单任务处理器线程池
        return new TaskExecutors<>(
                // 单任务处理线程工厂
                idx -> new SingleTaskWorkerRunnable<>("TaskNonBatchingWorker-" + name + '-' + idx,
                        isShutdown,
                        metrics,
                        processor,
                        acceptorExecutor),
                workerCount,
                isShutdown);
    }


    static <ID, T> TaskExecutors<ID, T> batchExecutors(final String name,
                                                       int workerCount,
                                                       final TaskProcessor<T> processor,
                                                       final AcceptorExecutor<ID, T> acceptorExecutor) {
        final AtomicBoolean isShutdown = new AtomicBoolean();
        final TaskExecutorMetrics metrics = new TaskExecutorMetrics(name);
        registeredMonitors.put(name, metrics);
        // 创建批量任务处理器线程池
        return new TaskExecutors<>(
                // 批量任务处理线程工厂
                idx -> new BatchWorkerRunnable<>("TaskBatchingWorker-" + name + '-' + idx,
                        isShutdown,
                        metrics,
                        processor,
                        acceptorExecutor),
                workerCount,
                isShutdown);
    }

    static class TaskExecutorMetrics {

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfSuccessfulExecutions", description = "Number of " +
                "successful task executions", type = DataSourceType.COUNTER)
        volatile long numberOfSuccessfulExecutions;

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfTransientErrors", description = "Number of transient " +
                "task execution errors", type = DataSourceType.COUNTER)
        volatile long numberOfTransientError;

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfPermanentErrors", description = "Number of permanent " +
                "task execution errors", type = DataSourceType.COUNTER)
        volatile long numberOfPermanentError;

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfCongestionIssues", description = "Number of congestion " +
                "issues during task execution", type = DataSourceType.COUNTER)
        volatile long numberOfCongestionIssues;

        final StatsTimer taskWaitingTimeForProcessing;

        TaskExecutorMetrics(String id) {
            final double[] percentiles = {50.0, 95.0, 99.0, 99.5};
            final StatsConfig statsConfig = new StatsConfig.Builder()
                    .withSampleSize(1000)
                    .withPercentiles(percentiles)
                    .withPublishStdDev(true)
                    .build();
            final MonitorConfig config = MonitorConfig.builder(METRIC_REPLICATION_PREFIX + "executionTime").build();
            taskWaitingTimeForProcessing = new StatsTimer(config, statsConfig);

            try {
                Monitors.registerObject(id, this);
            } catch (Throwable e) {
                logger.warn("Cannot register servo monitor for this object", e);
            }
        }

        void registerTaskResult(ProcessingResult result, int count) {
            switch (result) {
                case Success:
                    numberOfSuccessfulExecutions += count;
                    break;
                case TransientError:
                    numberOfTransientError += count;
                    break;
                case PermanentError:
                    numberOfPermanentError += count;
                    break;
                case Congestion:
                    numberOfCongestionIssues += count;
                    break;
            }
        }

        <ID, T> void registerExpiryTime(TaskHolder<ID, T> holder) {
            taskWaitingTimeForProcessing.record(System.currentTimeMillis() - holder.getSubmitTimestamp(),
                    TimeUnit.MILLISECONDS);
        }

        <ID, T> void registerExpiryTimes(List<TaskHolder<ID, T>> holders) {
            long now = System.currentTimeMillis();
            for (TaskHolder<ID, T> holder : holders) {
                taskWaitingTimeForProcessing.record(now - holder.getSubmitTimestamp(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 工作线程工厂，创建工作线程
     *
     * @param <ID>
     * @param <T>
     */
    interface WorkerRunnableFactory<ID, T> {
        WorkerRunnable<ID, T> create(int idx);
    }

    /**
     * 工作线程
     */
    abstract static class WorkerRunnable<ID, T> implements Runnable {
        /**
         * 线程名
         */
        final String workerName;
        /**
         * 是否关闭
         */
        final AtomicBoolean isShutdown;
        final TaskExecutorMetrics metrics;
        /**
         * 任务处理器
         */
        final TaskProcessor<T> processor;
        /**
         * 任务接收分发执行器
         */
        final AcceptorExecutor<ID, T> taskDispatcher;

        WorkerRunnable(String workerName,
                       AtomicBoolean isShutdown,
                       TaskExecutorMetrics metrics,
                       TaskProcessor<T> processor,
                       AcceptorExecutor<ID, T> taskDispatcher) {
            this.workerName = workerName;
            this.isShutdown = isShutdown;
            this.metrics = metrics;
            this.processor = processor;
            this.taskDispatcher = taskDispatcher;
        }

        String getWorkerName() {
            return workerName;
        }
    }

    /**
     * 批量任务处理线程
     *
     * @param <ID> 任务id
     * @param <T>  任务
     */
    static class BatchWorkerRunnable<ID, T> extends WorkerRunnable<ID, T> {

        BatchWorkerRunnable(String workerName,
                            AtomicBoolean isShutdown,
                            TaskExecutorMetrics metrics,
                            TaskProcessor<T> processor,
                            AcceptorExecutor<ID, T> acceptorExecutor) {
            super(workerName, isShutdown, metrics, processor, acceptorExecutor);
        }

        @Override
        public void run() {
            try {
                // 无限while处理
                while (!isShutdown.get()) {
                    // 获取任务
                    List<TaskHolder<ID, T>> holders = getWork();
                    metrics.registerExpiryTimes(holders);

                    // 获取实际的任务
                    List<T> tasks = getTasksOf(holders);
                    // 调用任务处理器处理任务
                    ProcessingResult result = processor.process(tasks);
                    switch (result) {
                        case Success:
                            break;
                        case Congestion:
                        case TransientError:
                            // 阻塞和网络失败的重新处理
                            taskDispatcher.reprocess(holders, result);
                            break;
                        case PermanentError:
                            // 异常错误，直接丢弃
                            logger.warn("Discarding {} tasks of {} due to permanent error", holders.size(), workerName);
                            break;
                        default:
                            break;
                    }
                    metrics.registerTaskResult(result, tasks.size());
                }
            } catch (InterruptedException e) {
                // Ignore
            } catch (Throwable e) {
                // Safe-guard, so we never exit this loop in an uncontrolled way.
                logger.warn("Discovery WorkerThread error", e);
            }
        }

        /**
         * 获取批量任务
         */
        private List<TaskHolder<ID, T>> getWork() throws InterruptedException {
            // 请求工作，获取批量任务
            BlockingQueue<List<TaskHolder<ID, T>>> workQueue = taskDispatcher.requestWorkItems();
            // 如果结果为空，每1S获取一次
            List<TaskHolder<ID, T>> result;
            do {
                result = workQueue.poll(1, TimeUnit.SECONDS);
            } while (!isShutdown.get() && result == null);
            return (result == null) ? new ArrayList<>() : result;
        }

        /**
         * 获取实际的任务
         */
        private List<T> getTasksOf(List<TaskHolder<ID, T>> holders) {
            List<T> tasks = new ArrayList<>(holders.size());
            for (TaskHolder<ID, T> holder : holders) {
                tasks.add(holder.getTask());
            }
            return tasks;
        }
    }

    /**
     * 单任务处理线程
     *
     * @param <ID> 任务id
     * @param <T>  任务
     */
    static class SingleTaskWorkerRunnable<ID, T> extends WorkerRunnable<ID, T> {

        SingleTaskWorkerRunnable(String workerName,
                                 AtomicBoolean isShutdown,
                                 TaskExecutorMetrics metrics,
                                 TaskProcessor<T> processor,
                                 AcceptorExecutor<ID, T> acceptorExecutor) {
            super(workerName, isShutdown, metrics, processor, acceptorExecutor);
        }

        @Override
        public void run() {
            try {
                // 不关闭一直处理
                while (!isShutdown.get()) {
                    // 请求获取单个任务
                    BlockingQueue<TaskHolder<ID, T>> workQueue = taskDispatcher.requestWorkItem();
                    // 从队列中拿去一个不为空的任务
                    TaskHolder<ID, T> taskHolder;
                    while ((taskHolder = workQueue.poll(1, TimeUnit.SECONDS)) == null) {
                        if (isShutdown.get()) {
                            return;
                        }
                    }
                    metrics.registerExpiryTime(taskHolder);
                    // 处理任务
                    ProcessingResult result = processor.process(taskHolder.getTask());
                    switch (result) {
                        case Success:
                            break;
                        case Congestion:
                        case TransientError:
                            // 重试
                            taskDispatcher.reprocess(taskHolder, result);
                            break;
                        case PermanentError:
                            // 丢弃
                            logger.warn("Discarding a task of {} due to permanent error", workerName);
                            break;
                        default:
                            break;
                    }
                    metrics.registerTaskResult(result, 1);
                }
            } catch (InterruptedException e) {
                // Ignore
            } catch (Throwable e) {
                // Safe-guard, so we never exit this loop in an uncontrolled way.
                logger.warn("Discovery WorkerThread error", e);
            }
        }
    }
}
