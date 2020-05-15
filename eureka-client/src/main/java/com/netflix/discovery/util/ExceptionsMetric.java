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

package com.netflix.discovery.util;

import com.netflix.servo.DefaultMonitorRegistry;
import com.netflix.servo.monitor.BasicCounter;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.MonitorConfig;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 异常统计监控
 * <p>
 * Counters for exceptions.
 *
 * @author Tomasz Bak
 */
public class ExceptionsMetric {

    private final String name;

    /**
     * 异常统计：
     * key：类名
     * value：计数器
     */
    private final ConcurrentHashMap<String, Counter> exceptionCounters = new ConcurrentHashMap<>();

    public ExceptionsMetric(String name) {
        this.name = name;
    }

    /**
     * 对异常计数
     *
     * @param ex 异常
     */
    public void count(Throwable ex) {
        getOrCreateCounter(extractName(ex)).increment();
    }

    /**
     * 关闭异常统计
     */
    public void shutdown() {
        ServoUtil.unregister(exceptionCounters.values());
    }

    /**
     * 获取或创建异常计数器
     *
     * @param exceptionName 异常类名
     * @return 异常计数器
     */
    private Counter getOrCreateCounter(String exceptionName) {
        Counter counter = exceptionCounters.get(exceptionName);
        if (counter == null) {
            counter = new BasicCounter(
                    MonitorConfig
                            .builder(name)
                            .withTag("id", exceptionName)
                            .build());
            if (exceptionCounters.putIfAbsent(exceptionName, counter) == null) {
                DefaultMonitorRegistry.getInstance().register(counter);
            } else {
                counter = exceptionCounters.get(exceptionName);
            }
        }
        return counter;
    }

    /**
     * 获取异常根原因的类名
     *
     * @param ex 异常
     * @return 异常根原因的类名
     */
    private static String extractName(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
