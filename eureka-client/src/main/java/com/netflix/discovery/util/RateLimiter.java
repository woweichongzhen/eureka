/*
 * Copyright 2014 Netflix, Inc.
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

package com.netflix.discovery.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于Token Bucket Algorithm ( 令牌桶算法 )的速率限制器
 * 基于令牌桶算法的限流的特点：让流量平稳，而不是瞬间流量。
 * 1000 QPS 相对平均的分摊在这一秒内，而不是第 1 ms 999 请求，后面 999 ms 0 请求
 * <p>
 * 速率限制器实现基于令牌桶算法。有两个参数：
 * <p>
 * 突发大小
 * 允许以突发形式进入系统的最大请求数
 * <p>
 * 平均速率
 * 每秒的预期请求数（RateLimiters使用还支持MINUTES）
 * <p>
 * Rate limiter implementation is based on token bucket algorithm. There are two parameters:
 * <ul>
 * <li>
 *     burst size - maximum number of requests allowed into the system as a burst
 * </li>
 * <li>
 *     average rate - expected number of requests per second (RateLimiters using MINUTES is also supported)
 * </li>
 * </ul>
 *
 * @author Tomasz Bak
 */
public class RateLimiter {

    /**
     * 速率单位转换成毫秒
     */
    private final long rateToMsConversion;

    /**
     * 消耗令牌数
     */
    private final AtomicInteger consumedTokens = new AtomicInteger();

    /**
     * 最后填充令牌的时间
     */
    private final AtomicLong lastRefillTime = new AtomicLong(0);

    @Deprecated
    public RateLimiter() {
        this(TimeUnit.SECONDS);
    }

    /**
     * 初始化令牌桶算法
     *
     * @param averageRateUnit 级别
     */
    public RateLimiter(TimeUnit averageRateUnit) {
        switch (averageRateUnit) {
            case SECONDS:
                // 秒级别
                rateToMsConversion = 1000;
                break;
            case MINUTES:
                // 分钟级别
                rateToMsConversion = 60 * 1000;
                break;
            default:
                throw new IllegalArgumentException("TimeUnit of " + averageRateUnit + " is not supported");
        }
    }

    /**
     * 获取令牌
     *
     * @param burstSize   令牌桶上限
     *                    每毫秒可获取 10 个令牌
     *                    例如，每毫秒允许请求上限为 10 次，并且请求消耗掉的令牌，需要逐步填充。
     *                    这里要注意下，虽然每毫秒允许请求上限为 10 次，这是在没有任何令牌被消耗的情况下，实际每秒允许请求依然是 2000 次
     * @param averageRate 令牌填充平均速率
     *                    即每秒可获取 2000 个令牌，每秒允许请求 2000 次。
     *                    每毫秒可填充两个令牌
     * @return 是否获取成功
     */
    public boolean acquire(int burstSize, long averageRate) {
        return acquire(burstSize, averageRate, System.currentTimeMillis());
    }

    public boolean acquire(int burstSize, long averageRate, long currentTimeMillis) {
        // 与其抛出异常，不如让所有流量通过
        if (burstSize <= 0 || averageRate <= 0) {
            return true;
        }

        // 填充已消耗的令牌
        refillToken(burstSize, averageRate, currentTimeMillis);
        // 消费令牌
        return consumeToken(burstSize);
    }

    /**
     * 填充已消耗的令牌
     * 获取令牌时才计算，多次令牌填充可以合并成一次，减少冗余和无效的计算
     *
     * @param burstSize         令牌桶上限
     * @param averageRate       令牌再装平均速率
     * @param currentTimeMillis 当前时间戳
     */
    private void refillToken(int burstSize, long averageRate, long currentTimeMillis) {
        // 获得 最后填充令牌的时间
        long refillTime = lastRefillTime.get();
        // 获取已经过去多少毫秒
        long timeDelta = currentTimeMillis - refillTime;

        // 计算可填充最大令牌数量
        long newTokens = timeDelta * averageRate / rateToMsConversion;
        if (newTokens > 0) {
            // 计算新的填充令牌的时间
            long newRefillTime = refillTime == 0
                    ? currentTimeMillis
                    : refillTime + newTokens * rateToMsConversion / averageRate;
            // CAS 保证有且仅有一个线程进入填充
            if (lastRefillTime.compareAndSet(refillTime, newRefillTime)) {
                // while循环直到填充令牌成功
                while (true) {
                    // 计算新的填充令牌后的已消耗令牌数量
                    int currentLevel = consumedTokens.get();
                    // 万一burstSize减小，以它作为已消耗的令牌数量
                    int adjustedLevel = Math.min(currentLevel, burstSize);
                    int newLevel = (int) Math.max(0, adjustedLevel - newTokens);
                    // CAS避免和正在消费令牌的线程冲突
                    if (consumedTokens.compareAndSet(currentLevel, newLevel)) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * 消费获取到的令牌
     *
     * @param burstSize 令牌桶上限
     * @return 是否消费成功
     */
    private boolean consumeToken(int burstSize) {
        // 死循环，直到没有令牌，或者获取令牌成功
        while (true) {
            // 没有令牌
            int currentLevel = consumedTokens.get();
            if (currentLevel >= burstSize) {
                return false;
            }
            // CAS避免和正在消费令牌或者填充令牌的线程冲突
            if (consumedTokens.compareAndSet(currentLevel, currentLevel + 1)) {
                return true;
            }
        }
    }

    /**
     * 重置令牌桶
     */
    public void reset() {
        consumedTokens.set(0);
        lastRefillTime.set(0);
    }
}
