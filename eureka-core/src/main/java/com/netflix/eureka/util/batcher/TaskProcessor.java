package com.netflix.eureka.util.batcher;

import java.util.List;

/**
 * 任务处理器
 * <p>
 * An interface to be implemented by clients for task execution.
 *
 * @author Tomasz Bak
 */
public interface TaskProcessor<T> {

    /**
     * 任务处理结果
     * <p>
     * A processed task/task list ends up in one of the following states:
     * <ul>
     *     <li>{@code Success} processing finished successfully</li>
     *     <li>{@code TransientError} processing failed, but shall be retried later</li>
     *     <li>{@code PermanentError} processing failed, and is non recoverable</li>
     * </ul>
     */
    enum ProcessingResult {
        /**
         * 成功
         */
        Success,
        /**
         * 拥挤阻塞错误，会重试
         * 例如限流
         */
        Congestion,
        /**
         * 处理失败，将会重试
         * 如超时
         */
        TransientError,
        /**
         * 处理失败，不会恢复，直接丢弃
         * 执行发生异常
         */
        PermanentError
    }

    /**
     * 处理非合并任务
     * In non-batched mode a single task is processed at a time.
     */
    ProcessingResult process(T task);

    /**
     * 处理合并任务
     * For batched mode a collection of tasks is run at a time. The result is provided for the aggregated result,
     * and all tasks are handled in the same way according to what is returned (for example are rescheduled, if the
     * error is transient).
     */
    ProcessingResult process(List<T> tasks);
}
