package com.netflix.eureka.cluster;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.cluster.protocol.ReplicationInstance;
import com.netflix.eureka.cluster.protocol.ReplicationInstance.ReplicationInstanceBuilder;
import com.netflix.eureka.cluster.protocol.ReplicationInstanceResponse;
import com.netflix.eureka.cluster.protocol.ReplicationList;
import com.netflix.eureka.cluster.protocol.ReplicationListResponse;
import com.netflix.eureka.util.batcher.TaskProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.netflix.eureka.cluster.protocol.ReplicationInstance.ReplicationInstanceBuilder.aReplicationInstance;

/**
 * 同步任务处理器
 *
 * @author Tomasz Bak
 */
class ReplicationTaskProcessor implements TaskProcessor<ReplicationTask> {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationTaskProcessor.class);

    private final HttpReplicationClient replicationClient;

    private final String peerId;

    private volatile long lastNetworkErrorTime;

    private static final Pattern READ_TIME_OUT_PATTERN = Pattern.compile(".*read.*time.*out.*");

    ReplicationTaskProcessor(String peerId, HttpReplicationClient replicationClient) {
        this.replicationClient = replicationClient;
        this.peerId = peerId;
    }

    /**
     * 用于 Eureka-Server 向亚马逊 AWS 的 ASG ( Autoscaling Group ) 同步状态
     */
    @Override
    public ProcessingResult process(ReplicationTask task) {
        try {
            EurekaHttpResponse<?> httpResponse = task.execute();
            int statusCode = httpResponse.getStatusCode();
            Object entity = httpResponse.getEntity();
            if (logger.isDebugEnabled()) {
                logger.debug("Replication task {} completed with status {}, (includes entity {})", task.getTaskName()
                        , statusCode, entity != null);
            }
            if (isSuccess(statusCode)) {
                task.handleSuccess();
            } else if (statusCode == 503) {
                logger.debug("Server busy (503) reply for task {}", task.getTaskName());
                return ProcessingResult.Congestion;
            } else {
                task.handleFailure(statusCode, entity);
                return ProcessingResult.PermanentError;
            }
        } catch (Throwable e) {
            if (maybeReadTimeOut(e)) {
                logger.error("It seems to be a socket read timeout exception, it will retry later. if it continues to" +
                        " happen and some eureka node occupied all the cpu time, you should set property 'eureka" +
                        ".server.peer-node-read-timeout-ms' to a bigger value", e);
                //read timeout exception is more Congestion then TransientError, return Congestion for longer delay
                return ProcessingResult.Congestion;
            } else if (isNetworkConnectException(e)) {
                logNetworkErrorSample(task, e);
                return ProcessingResult.TransientError;
            } else {
                logger.error("{}: {} Not re-trying this exception because it does not seem to be a network exception",
                        peerId, task.getTaskName(), e);
                return ProcessingResult.PermanentError;
            }
        }
        return ProcessingResult.Success;
    }

    /**
     * 用于 Eureka-Server 集群注册信息的同步操作任务
     * 通过调用被同步的 Eureka-Server 的 peerreplication/batch/ 接口
     * 一次性将批量( 多个 )的同步操作任务发起请求
     */
    @Override
    public ProcessingResult process(List<ReplicationTask> tasks) {
        // 创建 批量提交同步操作任务的请求对象
        ReplicationList list = createReplicationListOf(tasks);
        try {
            // 发起 批量提交同步操作任务的请求
            EurekaHttpResponse<ReplicationListResponse> response = replicationClient.submitBatchUpdates(list);
            // 判断返回码决定是否要重试
            int statusCode = response.getStatusCode();
            if (!isSuccess(statusCode)) {
                if (statusCode == 503) {
                    // 限流
                    logger.warn("Server busy (503) HTTP status code received from the peer {}; rescheduling tasks " +
                            "after delay", peerId);
                    return ProcessingResult.Congestion;
                } else {
                    // Unexpected error returned from the server. This should ideally never happen.
                    logger.error("Batch update failure with HTTP status code {}; discarding {} replication tasks",
                            statusCode, tasks.size());
                    // 非预期，永久错误
                    return ProcessingResult.PermanentError;
                }
            } else {
                // 发送成功的
                handleBatchResponse(tasks, response.getEntity().getResponseList());
            }
        } catch (Throwable e) {
            if (maybeReadTimeOut(e)) {
                // 如果是超时，返回拥挤
                logger.error("It seems to be a socket read timeout exception, it will retry later. if it continues to" +
                        " happen and some eureka node occupied all the cpu time, you should set property 'eureka" +
                        ".server.peer-node-read-timeout-ms' to a bigger value", e);
                //read timeout exception is more Congestion then TransientError, return Congestion for longer delay
                return ProcessingResult.Congestion;
            } else if (isNetworkConnectException(e)) {
                // 连接异常，返回网络原因处理失败
                logNetworkErrorSample(null, e);
                return ProcessingResult.TransientError;
            } else {
                logger.error("Not re-trying this exception because it does not seem to be a network exception", e);
                return ProcessingResult.PermanentError;
            }
        }
        return ProcessingResult.Success;
    }

    /**
     * 重试网络超时任务，打印关键信息
     * <p>
     * We want to retry eagerly, but without flooding log file with tons of error entries.
     * As tasks are executed by a pool of threads the error logging multiplies. For example:
     * 20 threads * 100ms delay == 200 error entries / sec worst case
     * Still we would like to see the exception samples, so we print samples at regular intervals.
     */
    private void logNetworkErrorSample(ReplicationTask task, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - lastNetworkErrorTime > 10000) {
            lastNetworkErrorTime = now;
            StringBuilder sb = new StringBuilder();
            sb.append("Network level connection to peer ").append(peerId);
            if (task != null) {
                sb.append(" for task ").append(task.getTaskName());
            }
            sb.append("; retrying after delay");
            logger.error(sb.toString(), e);
        }
    }

    /**
     * 200-300的返回码处理
     *
     * @param tasks        同步任务
     * @param responseList 返回集合
     */
    private void handleBatchResponse(List<ReplicationTask> tasks, List<ReplicationInstanceResponse> responseList) {
        if (tasks.size() != responseList.size()) {
            // This should ideally never happen unless there is a bug in the software.
            logger.error("Batch response size different from submitted task list ({} != {}); skipping response " +
                    "analysis", responseList.size(), tasks.size());
            return;
        }
        for (int i = 0; i < tasks.size(); i++) {
            handleBatchResponse(tasks.get(i), responseList.get(i));
        }
    }

    /**
     * 处理批量同步返回
     *
     * @param task     同步任务
     * @param response 同步返回
     */
    private void handleBatchResponse(ReplicationTask task, ReplicationInstanceResponse response) {
        // 成功空处理
        int statusCode = response.getStatusCode();
        if (isSuccess(statusCode)) {
            task.handleSuccess();
            return;
        }

        try {
            // 失败打印日志
            task.handleFailure(response.getStatusCode(), response.getResponseEntity());
        } catch (Throwable e) {
            logger.error("Replication task {} error handler failure", task.getTaskName(), e);
        }
    }

    /**
     * 创建 批量提交同步操作任务的请求对象
     *
     * @param tasks 批量同步任务
     * @return 请求对象
     */
    private ReplicationList createReplicationListOf(List<ReplicationTask> tasks) {
        ReplicationList list = new ReplicationList();
        for (ReplicationTask task : tasks) {
            // Only InstanceReplicationTask are batched.
            list.addReplicationInstance(createReplicationInstanceOf((InstanceReplicationTask) task));
        }
        return list;
    }

    /**
     * 判断http返回码是否成功
     *
     * @param statusCode http返回码
     * @return true成功，false不成功
     */
    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * 判断是否是连接异常
     * Check if the exception is some sort of network timeout exception (ie)
     * read,connect.
     *
     * @param e The exception for which the information needs to be found.
     * @return true, if it is a network timeout, false otherwise.
     */
    private static boolean isNetworkConnectException(Throwable e) {
        do {
            if (IOException.class.isInstance(e)) {
                return true;
            }
            e = e.getCause();
        } while (e != null);
        return false;
    }

    /**
     * 判断是否是网络超时原因
     * <p>
     * Check if the exception is socket read time out exception
     *
     * @param e The exception for which the information needs to be found.
     * @return true, if it may be a socket read time out exception.
     */
    private static boolean maybeReadTimeOut(Throwable e) {
        do {
            if (IOException.class.isInstance(e)) {
                String message = e.getMessage().toLowerCase();
                Matcher matcher = READ_TIME_OUT_PATTERN.matcher(message);
                if (matcher.find()) {
                    return true;
                }
            }
            e = e.getCause();
        } while (e != null);
        return false;
    }

    /**
     * 根据实例同步任务，创建要同步的实例对象
     *
     * @param task 实例同步任务
     * @return 要同步的实例对象
     */
    private static ReplicationInstance createReplicationInstanceOf(InstanceReplicationTask task) {
        ReplicationInstanceBuilder instanceBuilder = aReplicationInstance();

        instanceBuilder.withAppName(task.getAppName());
        instanceBuilder.withId(task.getId());

        InstanceInfo instanceInfo = task.getInstanceInfo();
        if (instanceInfo != null) {
            String overriddenStatus = task.getOverriddenStatus() == null ? null : task.getOverriddenStatus().name();

            instanceBuilder.withOverriddenStatus(overriddenStatus);
            instanceBuilder.withLastDirtyTimestamp(instanceInfo.getLastDirtyTimestamp());
            if (task.shouldReplicateInstanceInfo()) {
                instanceBuilder.withInstanceInfo(instanceInfo);
            }
            String instanceStatus = instanceInfo.getStatus() == null ? null : instanceInfo.getStatus().name();
            instanceBuilder.withStatus(instanceStatus);
        }
        instanceBuilder.withAction(task.getAction());
        return instanceBuilder.build();
    }
}

