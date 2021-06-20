/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.lease;

import com.netflix.eureka.registry.AbstractInstanceRegistry;

/**
 * 租约信息
 * <p>
 * Describes a time-based availability of a {@link T}. Purpose is to avoid
 * accumulation of instances in {@link AbstractInstanceRegistry} as result of ungraceful
 * shutdowns that is not uncommon in AWS environments.
 * <p>
 * If a lease elapses without renewals, it will eventually expire consequently
 * marking the associated {@link T} for immediate eviction - this is similar to
 * an explicit cancellation except that there is no communication between the
 * {@link T} and {@link LeaseManager}.
 *
 * @author Karthik Ranganathan, Greg Kim
 */
public class Lease<T> {

    enum Action {
        /**
         * 注册
         */
        Register,

        /**
         * 取消
         */
        Cancel,

        /**
         * 续约
         */
        Renew
    }

    public static final int DEFAULT_DURATION_IN_SECS = 90;

    /**
     * 实体
     */
    private final T holder;

    /**
     * 取消注册时间戳
     */
    private long evictionTimestamp;

    /**
     * 注册时间戳
     */
    private final long registrationTimestamp;

    /**
     * 开始服务时间戳
     */
    private long serviceUpTimestamp;
    // Make it volatile so that the expiration task would see this quicker

    /**
     * 最后更新时间戳
     */
    private volatile long lastUpdateTimestamp;

    /**
     * 租约持续时长，单位：毫秒
     */
    private final long duration;

    public Lease(T r, int durationInSecs) {
        holder = r;
        registrationTimestamp = System.currentTimeMillis();
        lastUpdateTimestamp = registrationTimestamp;
        duration = (durationInSecs * 1000);
    }

    /**
     * 续订租约，此处正常不应该加上周期
     * <p>
     * Renew the lease, use renewal duration if it was specified by the
     * associated {@link T} during registration, otherwise default duration is
     * {@link #DEFAULT_DURATION_IN_SECS}.
     */
    public void renew() {
        lastUpdateTimestamp = System.currentTimeMillis() + duration;
    }

    /**
     * 取消注册
     * <p>
     * Cancels the lease by updating the eviction time.
     */
    public void cancel() {
        if (evictionTimestamp <= 0) {
            evictionTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * 服务上线
     * <p>
     * Mark the service as up. This will only take affect the first time called,
     * subsequent calls will be ignored.
     */
    public void serviceUp() {
        if (serviceUpTimestamp == 0) {
            serviceUpTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * 设置服务上线时间
     * <p>
     * Set the leases service UP timestamp.
     */
    public void setServiceUpTimestamp(long serviceUpTimestamp) {
        this.serviceUpTimestamp = serviceUpTimestamp;
    }

    /**
     * 租约是否过期
     * <p>
     * Checks if the lease of a given {@link com.netflix.appinfo.InstanceInfo} has expired or not.
     */
    public boolean isExpired() {
        return isExpired(0L);
    }

    /**
     * 判断是否过期
     * <p>
     * 当前时间 和
     * 上次更新时间 + 周期 + 补偿时间
     * 比较，大于则过期
     * <p>
     * 由于 renew 方法做错了事，并将 lastUpdateTimestamp 设置为 + duration 多于持续时间，
     * 因此有效期实际上是2 * 持续时间。
     * 这是一个小错误，仅会影响那些不正常的实例。
     * 由于可能会对现有使用产生广泛影响，因此此问题不会得到解决。
     * <p>
     * Checks if the lease of a given {@link com.netflix.appinfo.InstanceInfo} has expired or not.
     * <p>
     * Note that due to renew() doing the 'wrong" thing and setting lastUpdateTimestamp to +duration more than
     * what it should be, the expiry will actually be 2 * duration. This is a minor bug and should only affect
     * instances that ungracefully shutdown. Due to possible wide ranging impact to existing usage, this will
     * not be fixed.
     *
     * @param additionalLeaseMs any additional lease time to add to the lease evaluation in ms.
     */
    public boolean isExpired(long additionalLeaseMs) {
        return evictionTimestamp > 0
                || System.currentTimeMillis() > (lastUpdateTimestamp + duration + additionalLeaseMs);
    }

    /**
     * Gets the milliseconds since epoch when the lease was registered.
     *
     * @return the milliseconds since epoch when the lease was registered.
     */
    public long getRegistrationTimestamp() {
        return registrationTimestamp;
    }

    /**
     * Gets the milliseconds since epoch when the lease was last renewed.
     * Note that the value returned here is actually not the last lease renewal time but the renewal + duration.
     *
     * @return the milliseconds since epoch when the lease was last renewed.
     */
    public long getLastRenewalTimestamp() {
        return lastUpdateTimestamp;
    }

    /**
     * Gets the milliseconds since epoch when the lease was evicted.
     *
     * @return the milliseconds since epoch when the lease was evicted.
     */
    public long getEvictionTimestamp() {
        return evictionTimestamp;
    }

    /**
     * Gets the milliseconds since epoch when the service for the lease was marked as up.
     *
     * @return the milliseconds since epoch when the service for the lease was marked as up.
     */
    public long getServiceUpTimestamp() {
        return serviceUpTimestamp;
    }

    /**
     * Returns the holder of the lease.
     */
    public T getHolder() {
        return holder;
    }

}
