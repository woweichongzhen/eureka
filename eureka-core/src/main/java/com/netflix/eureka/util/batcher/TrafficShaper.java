/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.eureka.util.batcher;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;

/**
 * 网络通信整形器
 * <p>
 * 在将任务分派给工作人员之前提供准入控制策略。
 * 它对通过重新处理请求（瞬态故障，拥塞）而来的事件做出反应，并根据此反馈延迟处理
 * <p>
 * {@link TrafficShaper} provides admission control policy prior to dispatching tasks to workers.
 * It reacts to events coming via reprocess requests (transient failures, congestion), and delays the processing
 * depending on this feedback.
 *
 * @author Tomasz Bak
 */
class TrafficShaper {

    /**
     * 最大延迟
     * Upper bound on delay provided by configuration.
     */
    private static final long MAX_DELAY = 30 * 1000;

    /**
     * 拥挤重试延迟
     */
    private final long congestionRetryDelayMs;

    /**
     * 网络失败重试
     */
    private final long networkFailureRetryMs;

    /**
     * 上一次的拥挤错误的时间
     */
    private volatile long lastCongestionError;

    /**
     * 上一次网络错误的时间
     */
    private volatile long lastNetworkFailure;

    TrafficShaper(long congestionRetryDelayMs, long networkFailureRetryMs) {
        // 对两个时间进行最大限制
        this.congestionRetryDelayMs = Math.min(MAX_DELAY, congestionRetryDelayMs);
        this.networkFailureRetryMs = Math.min(MAX_DELAY, networkFailureRetryMs);
    }

    /**
     * 添加处理失败的任务
     *
     * @param processingResult 处理结果
     */
    void registerFailure(ProcessingResult processingResult) {
        // 判断处理结果，分别设置最新的处理错误的时间戳
        if (processingResult == ProcessingResult.Congestion) {
            lastCongestionError = System.currentTimeMillis();
        } else if (processingResult == ProcessingResult.TransientError) {
            lastNetworkFailure = System.currentTimeMillis();
        }
    }

    /**
     * 计算延迟时间
     */
    long transmissionDelay() {
        // 未发生过错误，无需延迟
        if (lastCongestionError == -1 && lastNetworkFailure == -1) {
            return 0;
        }

        long now = System.currentTimeMillis();

        // 存在阻塞错误
        if (lastCongestionError != -1) {
            // 阻塞的时间小于设定的阻塞延迟时间，返回仍需阻塞的时间
            long congestionDelay = now - lastCongestionError;
            if (congestionDelay >= 0 && congestionDelay < congestionRetryDelayMs) {
                return congestionRetryDelayMs - congestionDelay;
            }
            // 重试上一次的阻塞错误
            lastCongestionError = -1;
        }

        // 存在网络错误
        if (lastNetworkFailure != -1) {
            // 已失败的时间小于设定的网络延迟时间，返回仍需延迟的时间
            long failureDelay = now - lastNetworkFailure;
            if (failureDelay >= 0 && failureDelay < networkFailureRetryMs) {
                return networkFailureRetryMs - failureDelay;
            }
            // 重置上一次的网络错误
            lastNetworkFailure = -1;
        }

        // 错误都没有，无需延迟
        return 0;
    }
}
