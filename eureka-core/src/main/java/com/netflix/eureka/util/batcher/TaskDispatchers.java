package com.netflix.eureka.util.batcher;

/**
 * 任务分发器工厂，构建任务分发器
 * See {@link TaskDispatcher} for an overview.
 *
 * @author Tomasz Bak
 */
public class TaskDispatchers {

    /**
     * 创建单任务的任务分发器
     * 用于 Eureka-Server 向亚马逊 AWS 的 ASG ( Autoscaling Group ) 同步状态
     */
    public static <ID, T> TaskDispatcher<ID, T> createNonBatchingTaskDispatcher(String id,
                                                                                int maxBufferSize,
                                                                                int workerCount,
                                                                                long maxBatchingDelay,
                                                                                long congestionRetryDelayMs,
                                                                                long networkFailureRetryMs,
                                                                                TaskProcessor<T> taskProcessor) {
        // 创建接收者执行线程池，用于将队列任务分给不同的worker
        final AcceptorExecutor<ID, T> acceptorExecutor = new AcceptorExecutor<>(
                // 任务执行器编号
                id,
                // 最大的阻塞数量
                maxBufferSize,
                // 最大的批量数量
                1,
                // 最大批量延迟
                maxBatchingDelay,
                // 阻塞比如限流后延迟重试时间
                congestionRetryDelayMs,
                // 网络失败重试时间
                networkFailureRetryMs
        );
        // 创建单个任务处理器线程池
        final TaskExecutors<ID, T> taskExecutor = TaskExecutors.singleItemExecutors(
                // 任务处理器编号
                id,
                // 工作线程数量
                workerCount,
                // 任务处理器实现
                taskProcessor,
                // 接受者线程池
                acceptorExecutor);
        // 创建任务分发器
        return new TaskDispatcher<ID, T>() {
            @Override
            public void process(ID id, T task, long expiryTime) {
                // 调用接受者接收分派任务
                acceptorExecutor.process(id, task, expiryTime);
            }

            @Override
            public void shutdown() {
                // 接受者关闭
                acceptorExecutor.shutdown();
                // 任务处理器关闭
                taskExecutor.shutdown();
            }
        };
    }

    /**
     * 创建批量执行的任务分发器
     * 用于 Eureka-Server 集群注册信息的同步任务
     */
    public static <ID, T> TaskDispatcher<ID, T> createBatchingTaskDispatcher(String id,
                                                                             int maxBufferSize,
                                                                             int workloadSize,
                                                                             int workerCount,
                                                                             long maxBatchingDelay,
                                                                             long congestionRetryDelayMs,
                                                                             long networkFailureRetryMs,
                                                                             TaskProcessor<T> taskProcessor) {
        // 创建接收者执行线程池，用于将队列任务分给不同的worker
        final AcceptorExecutor<ID, T> acceptorExecutor = new AcceptorExecutor<>(
                // 任务执行器编号
                id,
                // 最大的阻塞数量
                maxBufferSize,
                // 最大的批量数量
                workloadSize,
                // 最大批量延迟
                maxBatchingDelay,
                // 阻塞比如限流后延迟重试时间
                congestionRetryDelayMs,
                // 网络失败重试时间
                networkFailureRetryMs
        );
        // 创建批量任务处理器线程池
        final TaskExecutors<ID, T> taskExecutor = TaskExecutors.batchExecutors(
                // 任务处理器编号
                id,
                // 工作线程数量
                workerCount,
                // 任务处理器实现
                taskProcessor,
                // 接受者线程池
                acceptorExecutor);
        return new TaskDispatcher<ID, T>() {
            @Override
            public void process(ID id, T task, long expiryTime) {
                // 调用接受者接收分派任务
                acceptorExecutor.process(id, task, expiryTime);
            }

            @Override
            public void shutdown() {
                // 接受者关闭
                acceptorExecutor.shutdown();
                // 任务处理器关闭
                taskExecutor.shutdown();
            }
        };
    }
}
