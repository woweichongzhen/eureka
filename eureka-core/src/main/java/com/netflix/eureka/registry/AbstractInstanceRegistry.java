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

package com.netflix.eureka.registry;

import com.google.common.cache.CacheBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.Pair;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.annotations.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.netflix.eureka.util.EurekaMonitors.CANCEL;
import static com.netflix.eureka.util.EurekaMonitors.CANCEL_NOT_FOUND;
import static com.netflix.eureka.util.EurekaMonitors.EXPIRED;
import static com.netflix.eureka.util.EurekaMonitors.GET_ALL_CACHE_MISS;
import static com.netflix.eureka.util.EurekaMonitors.GET_ALL_CACHE_MISS_DELTA;
import static com.netflix.eureka.util.EurekaMonitors.GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS;
import static com.netflix.eureka.util.EurekaMonitors.GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS_DELTA;
import static com.netflix.eureka.util.EurekaMonitors.REGISTER;
import static com.netflix.eureka.util.EurekaMonitors.RENEW;
import static com.netflix.eureka.util.EurekaMonitors.RENEW_NOT_FOUND;
import static com.netflix.eureka.util.EurekaMonitors.STATUS_OVERRIDE_DELETE;
import static com.netflix.eureka.util.EurekaMonitors.STATUS_UPDATE;

/**
 * 应用对象注册表抽象实现
 * 执行的主要操作是：注册、续订、取消、到期、状态更改、存储增量
 * <p>
 * Handles all registry requests from eureka clients.
 *
 * <p>
 * Primary operations that are performed are the
 * <em>Registers</em>, <em>Renewals</em>, <em>Cancels</em>, <em>Expirations</em>, and <em>Status Changes</em>. The
 * registry also stores only the delta operations
 * </p>
 *
 * @author Karthik Ranganathan
 */
public abstract class AbstractInstanceRegistry implements InstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AbstractInstanceRegistry.class);

    private static final String[] EMPTY_STR_ARRAY = new String[0];

    /**
     * 注册表
     * 第一层key：appName
     * 第二层key：实例id
     * 第二层value：实例信息，租约信息
     */
    private final ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry = new ConcurrentHashMap<>();

    /**
     * 远端注册表
     */
    protected Map<String, RemoteRegionRegistry> regionNameVSRemoteRegistry = new HashMap<>();

    /**
     * 应用实例覆盖状态映射
     * key：实例id
     * value：实例状态
     */
    protected final ConcurrentMap<String, InstanceStatus> overriddenInstanceStatusMap = CacheBuilder
            .newBuilder().initialCapacity(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .<String, InstanceStatus>build().asMap();

    // CircularQueues here for debugging/statistics purposes only
    /**
     * 最近注册队列，仅用于调试和统计，用于 Eureka-Server 运维界面的显示，无实际业务逻辑使用
     */
    private final CircularQueue<Pair<Long, String>>          recentRegisteredQueue;
    /**
     * 最近取消队列，仅用于调试和统计，用于 Eureka-Server 运维界面的显示，无实际业务逻辑使用
     */
    private final CircularQueue<Pair<Long, String>>          recentCanceledQueue;
    /**
     * 最近改变队列，用于注册信息的增量获取
     */
    private final ConcurrentLinkedQueue<RecentlyChangedItem> recentlyChangedQueue = new ConcurrentLinkedQueue<>();

    /**
     * 读写锁，是针对于 ResponseCache 的读写锁
     * 需要保证增量，全量，二级缓存数据和注册表一致
     * 所以需要在同步这些信息时加上写锁
     */
    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    /**
     * 读锁
     */
    private final Lock read = readWriteLock.readLock();

    /**
     * 写锁
     */
    private final Lock write = readWriteLock.writeLock();

    /**
     * 自我保护锁
     */
    protected final Object lock = new Object();

    /**
     * 后台任务定时顺序扫描队列，当 lastUpdateTime 超过一定时长后进行移除增量变更
     */
    private final Timer deltaRetentionTimer = new Timer("Eureka-DeltaRetentionTimer", true);
    private final Timer evictionTimer       = new Timer("Eureka-EvictionTimer", true);

    /**
     * 续约速度测量
     * 配合 Netflix Servo 实现监控信息采集续租每分钟次数
     * Eureka-Server 运维界面的显示续租每分钟次数
     */
    private final MeasuredRate renewsLastMin;

    /**
     * 剔除过期任务引用
     */
    private final AtomicReference<EvictionTask> evictionTaskRef = new AtomicReference<EvictionTask>();

    protected String[] allKnownRemoteRegions = EMPTY_STR_ARRAY;

    /**
     * 自我保护阈值：期待每分钟能收到的续约数量的最小值
     * 当每分钟心跳次数小于该值时，触发eureka的自我保护
     */
    protected volatile int numberOfRenewsPerMinThreshold;

    /**
     * 自我保护机制：期待能收到消息的客户端数目
     */
    protected volatile int expectedNumberOfClientsSendingRenews;

    protected final    EurekaServerConfig serverConfig;
    protected final    EurekaClientConfig clientConfig;
    protected final    ServerCodecs       serverCodecs;
    protected volatile ResponseCache      responseCache;

    /**
     * Create a new, empty instance registry.
     */
    protected AbstractInstanceRegistry(EurekaServerConfig serverConfig, EurekaClientConfig clientConfig,
                                       ServerCodecs serverCodecs) {
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.recentCanceledQueue = new CircularQueue<>(1000);
        this.recentRegisteredQueue = new CircularQueue<>(1000);

        // 计算周期为1分钟
        this.renewsLastMin = new MeasuredRate(1000 * 60);

        // 后台任务定时顺序扫描队列，当 lastUpdateTime 超过一定时长后进行移除 增量信息
        this.deltaRetentionTimer.schedule(
                // 获取增量任务
                getDeltaRetentionTask(),
                // 移除增量执行频率
                serverConfig.getDeltaRetentionTimerIntervalInMs(),
                serverConfig.getDeltaRetentionTimerIntervalInMs());
    }

    @Override
    public synchronized void initializedResponseCache() {
        if (responseCache == null) {
            responseCache = new ResponseCacheImpl(serverConfig, serverCodecs, this);
        }
    }

    protected void initRemoteRegionRegistry() throws MalformedURLException {
        Map<String, String> remoteRegionUrlsWithName = serverConfig.getRemoteRegionUrlsWithName();
        if (!remoteRegionUrlsWithName.isEmpty()) {
            allKnownRemoteRegions = new String[remoteRegionUrlsWithName.size()];
            int remoteRegionArrayIndex = 0;
            for (Map.Entry<String, String> remoteRegionUrlWithName : remoteRegionUrlsWithName.entrySet()) {
                RemoteRegionRegistry remoteRegionRegistry = new RemoteRegionRegistry(
                        serverConfig,
                        clientConfig,
                        serverCodecs,
                        remoteRegionUrlWithName.getKey(),
                        new URL(remoteRegionUrlWithName.getValue()));
                regionNameVSRemoteRegistry.put(remoteRegionUrlWithName.getKey(), remoteRegionRegistry);
                allKnownRemoteRegions[remoteRegionArrayIndex++] = remoteRegionUrlWithName.getKey();
            }
        }
        logger.info("Finished initializing remote region registries. All known remote regions: {}",
                (Object) allKnownRemoteRegions);
    }

    @Override
    public ResponseCache getResponseCache() {
        return responseCache;
    }

    public long getLocalRegistrySize() {
        long total = 0;
        for (Map<String, Lease<InstanceInfo>> entry : registry.values()) {
            total += entry.size();
        }
        return total;
    }

    /**
     * Completely clear the registry.
     */
    @Override
    public void clearRegistry() {
        overriddenInstanceStatusMap.clear();
        recentCanceledQueue.clear();
        recentRegisteredQueue.clear();
        recentlyChangedQueue.clear();
        registry.clear();
    }

    // for server info use
    @Override
    public Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot() {
        return new HashMap<>(overriddenInstanceStatusMap);
    }

    /**
     * 注册新实例并带有续约周期
     * <p>
     * Registers a new instance with a given duration.
     *
     * @see com.netflix.eureka.lease.LeaseManager#register(java.lang.Object, int, boolean)
     */
    @Override
    public void register(InstanceInfo registrant, int leaseDuration, boolean isReplication) {
        try {
            // 读锁上锁
            read.lock();
            Map<String, Lease<InstanceInfo>> gMap = registry.get(registrant.getAppName());
            REGISTER.increment(isReplication);
            // 双重判断获取内层map，保证map不为空
            if (gMap == null) {
                final ConcurrentHashMap<String, Lease<InstanceInfo>> gNewMap = new ConcurrentHashMap<>();
                gMap = registry.putIfAbsent(registrant.getAppName(), gNewMap);
                if (gMap == null) {
                    gMap = gNewMap;
                }
            }
            // 获取可能存在的注册信息
            Lease<InstanceInfo> existingLease = gMap.get(registrant.getId());
            // Retain the last dirty timestamp without overwriting it, if there is already a lease
            if (existingLease != null && (existingLease.getHolder() != null)) {
                // 已存在则获取上次修改时间，与当前注册的修改时间进行比较，如果大于则采用自己保存的注册信息
                Long existingLastDirtyTimestamp = existingLease.getHolder().getLastDirtyTimestamp();
                Long registrationLastDirtyTimestamp = registrant.getLastDirtyTimestamp();
                logger.debug("Existing lease found (existing={}, provided={}", existingLastDirtyTimestamp,
                        registrationLastDirtyTimestamp);

                // this is a > instead of a >= because if the timestamps are equal, we still take the remote transmitted
                // InstanceInfo instead of the server local copy.
                if (existingLastDirtyTimestamp > registrationLastDirtyTimestamp) {
                    logger.warn("There is an existing lease and the existing lease's dirty timestamp {} is greater" +
                                    " than the one that is being registered {}", existingLastDirtyTimestamp,
                            registrationLastDirtyTimestamp);
                    logger.warn("Using the existing instanceInfo instead of the new instanceInfo as the registrant");
                    registrant = existingLease.getHolder();
                }
            } else {
                // The lease does not exist and hence it is a new registration
                // 注册信息不存在，更新自我保护机制的次数和阈值
                synchronized (lock) {
                    if (this.expectedNumberOfClientsSendingRenews > 0) {
                        // Since the client wants to register it, increase the number of clients sending renews
                        this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews + 1;
                        updateRenewsPerMinThreshold();
                    }
                }
                logger.debug("No previous lease information found; it is new registration");
            }
            // 添加租约，设置服务上线时间，如果已注册过采用已注册的服务上线时间
            Lease<InstanceInfo> lease = new Lease<>(registrant, leaseDuration);
            if (existingLease != null) {
                lease.setServiceUpTimestamp(existingLease.getServiceUpTimestamp());
            }
            // 保存
            gMap.put(registrant.getId(), lease);

            // 添加当前时间和 appName(instantceId) 的键值对到最近注册队列（调试用）中
            recentRegisteredQueue.add(new Pair<>(
                    System.currentTimeMillis(),
                    registrant.getAppName() + "(" + registrant.getId() + ")"));

            // This is where the initial state transfer of overridden status happens
            // 添加到 应用实例覆盖状态映射（Eureka-Server 初始化使用）
            if (!InstanceStatus.UNKNOWN.equals(registrant.getOverriddenStatus())) {
                logger.debug("Found overridden status {} for instance {}. Checking to see if needs to be add to the "
                        + "overrides", registrant.getOverriddenStatus(), registrant.getId());
                if (!overriddenInstanceStatusMap.containsKey(registrant.getId())) {
                    logger.info("Not found overridden id {} and hence adding it", registrant.getId());
                    overriddenInstanceStatusMap.put(registrant.getId(), registrant.getOverriddenStatus());
                }
            }
            // 根据缓存状态，设置实例的重写状态
            InstanceStatus overriddenStatusFromMap = overriddenInstanceStatusMap.get(registrant.getId());
            if (overriddenStatusFromMap != null) {
                logger.info("Storing overridden status {} from map", overriddenStatusFromMap);
                registrant.setOverriddenStatus(overriddenStatusFromMap);
            }

            // Set the status based on the overridden status rules
            // 获得应用实例最终状态，并设置应用实例的状态
            InstanceStatus overriddenInstanceStatus = getOverriddenInstanceStatus(registrant, existingLease,
                    isReplication);
            registrant.setStatusWithoutDirty(overriddenInstanceStatus);

            // If the lease is registered with UP status, set lease service up timestamp
            // 如果实例已经是UP，重新设置服务上线时间（只有第一次注册有效）
            if (InstanceStatus.UP.equals(registrant.getStatus())) {
                lease.serviceUp();
            }
            // 设置注册动作为添加
            registrant.setActionType(ActionType.ADDED);
            recentlyChangedQueue.add(new RecentlyChangedItem(lease));
            // 设置注册信息的最后更新时间
            registrant.setLastUpdatedTimestamp();

            // 清理缓存，设置缓存过期
            invalidateCache(registrant.getAppName(), registrant.getVIPAddress(), registrant.getSecureVipAddress());
            logger.info("Registered instance {}/{} with status {} (replication={})",
                    registrant.getAppName(), registrant.getId(), registrant.getStatus(), isReplication);
        } finally {
            // 释放读锁
            read.unlock();
        }
    }

    /**
     * Cancels the registration of an instance.
     *
     * <p>
     * This is normally invoked by a client when it shuts down informing the
     * server to remove the instance from traffic.
     * </p>
     *
     * @param appName       the application name of the application.
     * @param id            the unique identifier of the instance.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the instance was removed from the {@link AbstractInstanceRegistry} successfully, false otherwise.
     */
    @Override
    public boolean cancel(String appName, String id, boolean isReplication) {
        return this.internalCancel(appName, id, isReplication);
    }

    /**
     * 内部下线处理
     * <p>
     * {@link #cancel(String, String, boolean)} method is overridden by {@link PeerAwareInstanceRegistry}, so each
     * cancel request is replicated to the peers. This is however not desired for expires which would be counted
     * in the remote peers as valid cancellations, so self preservation mode would not kick-in.
     */
    protected boolean internalCancel(String appName, String id, boolean isReplication) {
        try {
            // 获得读锁
            read.lock();
            // 增加 取消注册次数 到 监控
            CANCEL.increment(isReplication);

            // 从注册表中移除
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> leaseToCancel = null;
            if (gMap != null) {
                leaseToCancel = gMap.remove(id);
            }
            // 添加移除信息到最近取消队列中
            recentCanceledQueue.add(new Pair<>(System.currentTimeMillis(), appName + "(" + id + ")"));

            // 移除 应用实例覆盖状态映射
            InstanceStatus instanceStatus = overriddenInstanceStatusMap.remove(id);
            if (instanceStatus != null) {
                logger.debug("Removed instance id {} from the overridden map which has value {}", id, instanceStatus.name());
            }

            if (leaseToCancel == null) {
                // 未发现注册信息，返回false
                CANCEL_NOT_FOUND.increment(isReplication);
                logger.warn("DS: Registry: cancel failed because Lease is not registered for: {}/{}", appName, id);
                return false;
            }

            // 设置 租约的取消注册时间戳
            leaseToCancel.cancel();

            InstanceInfo instanceInfo = leaseToCancel.getHolder();
            String vip = null;
            String svip = null;
            if (instanceInfo != null) {
                // 设置动作为删除，添加改变项到最近改变队列
                instanceInfo.setActionType(ActionType.DELETED);
                recentlyChangedQueue.add(new RecentlyChangedItem(leaseToCancel));
                instanceInfo.setLastUpdatedTimestamp();
                vip = instanceInfo.getVIPAddress();
                svip = instanceInfo.getSecureVipAddress();
            }

            // 清除缓存，设置缓存过期
            this.invalidateCache(appName, vip, svip);
            logger.info("Cancelled instance {}/{} (replication={})", appName, id, isReplication);
        } finally {
            // 解锁
            read.unlock();
        }

        // 减少expectedNumberOfClientsSendingRenews，自我保护数据更新
        synchronized (lock) {
            if (this.expectedNumberOfClientsSendingRenews > 0) {
                // Since the client wants to cancel it, reduce the number of clients to send renews.
                this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews - 1;
                updateRenewsPerMinThreshold();
            }
        }

        return true;
    }

    /**
     * 标记指定的实例续约
     * <p>
     * Marks the given instance of the given app name as renewed, and also marks whether it originated from
     * replication.
     *
     * @see com.netflix.eureka.lease.LeaseManager#renew(java.lang.String, java.lang.String, boolean)
     */
    @Override
    public boolean renew(String appName, String id, boolean isReplication) {
        // 增加 续租次数 到 监控
        RENEW.increment(isReplication);

        // 获取注册表中的注册信息，获取不到返回false
        Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
        Lease<InstanceInfo> leaseToRenew = null;
        if (gMap != null) {
            leaseToRenew = gMap.get(id);
        }
        if (leaseToRenew == null) {
            RENEW_NOT_FOUND.increment(isReplication);
            logger.warn("DS: Registry: lease doesn't exist, registering resource: {} - {}", appName, id);
            return false;
        }

        InstanceInfo instanceInfo = leaseToRenew.getHolder();
        // 存在注册信息
        if (instanceInfo != null) {
            // touchASGCache(instanceInfo.getASGName());
            // 获取实例最终状态
            InstanceStatus overriddenInstanceStatus = this.getOverriddenInstanceStatus(
                    instanceInfo, leaseToRenew, isReplication);
            // 未知返回false
            if (overriddenInstanceStatus == InstanceStatus.UNKNOWN) {
                logger.info("Instance status UNKNOWN possibly due to deleted override for instance {}"
                        + "; re-register required", instanceInfo.getId());
                RENEW_NOT_FOUND.increment(isReplication);
                return false;
            }
            // 原状态 和 client最终状态 不一致，更新状态
            if (!instanceInfo.getStatus().equals(overriddenInstanceStatus)) {
                logger.info(
                        "The instance status {} is different from overridden instance status {} for instance {}. "
                                + "Hence setting the status to overridden status", instanceInfo.getStatus().name(),
                        instanceInfo.getOverriddenStatus().name(),
                        instanceInfo.getId());
                instanceInfo.setStatusWithoutDirty(overriddenInstanceStatus);
            }
        }

        // 新增 续租每分钟次数
        renewsLastMin.increment();
        // 设置 租约最后更新时间（续租）
        leaseToRenew.renew();
        return true;
    }

    /**
     * @param id               the unique identifier of the instance.
     * @param overriddenStatus Overridden status if any.
     * @deprecated this is expensive, try not to use. See if you can use
     * {@link #storeOverriddenStatusIfRequired(String, String, InstanceStatus)} instead.
     * <p>
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     */
    @Deprecated
    @Override
    public void storeOverriddenStatusIfRequired(String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null)
                || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got restarted -this will help us maintain
            // the overridden state from the replica
            logger.info(
                    "Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            List<InstanceInfo> instanceInfo = this.getInstancesById(id, false);
            if ((instanceInfo != null) && (!instanceInfo.isEmpty())) {
                instanceInfo.iterator().next().setOverriddenStatus(overriddenStatus);
                logger.info(
                        "Setting the overridden status for instance id {} and the value is {} ",
                        id, overriddenStatus.name());

            }
        }
    }

    /**
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     *
     * @param appName          the application name of the instance.
     * @param id               the unique identifier of the instance.
     * @param overriddenStatus overridden status if any.
     */
    @Override
    public void storeOverriddenStatusIfRequired(String appName, String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null) || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got
            // restarted -this will help us maintain the overridden state
            // from the replica
            logger.info("Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            InstanceInfo instanceInfo = this.getInstanceByAppAndId(appName, id, false);
            instanceInfo.setOverriddenStatus(overriddenStatus);
            logger.info("Set the overridden status for instance (appname:{}, id:{}} and the value is {} ",
                    appName, id, overriddenStatus.name());
        }
    }

    /**
     * Updates the status of an instance. Normally happens to put an instance
     * between {@link InstanceStatus#OUT_OF_SERVICE} and
     * {@link InstanceStatus#UP} to put the instance in and out of traffic.
     *
     * @param appName            the application name of the instance.
     * @param id                 the unique identifier of the instance.
     * @param newStatus          the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication      true if this is a replication event from other nodes, false
     *                           otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean statusUpdate(String appName, String id,
                                InstanceStatus newStatus, String lastDirtyTimestamp,
                                boolean isReplication) {
        try {
            read.lock();
            STATUS_UPDATE.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();
                InstanceInfo info = lease.getHolder();
                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }
                if ((info != null) && !(info.getStatus().equals(newStatus))) {
                    // Mark service as UP if needed
                    if (InstanceStatus.UP.equals(newStatus)) {
                        lease.serviceUp();
                    }
                    // This is NAC overriden status
                    overriddenInstanceStatusMap.put(id, newStatus);
                    // Set it for transfer of overridden status to replica on
                    // replica start up
                    info.setOverriddenStatus(newStatus);
                    long replicaDirtyTimestamp = 0;
                    info.setStatusWithoutDirty(newStatus);
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.valueOf(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Removes status override for a give instance.
     *
     * @param appName            the application name of the instance.
     * @param id                 the unique identifier of the instance.
     * @param newStatus          the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication      true if this is a replication event from other nodes, false
     *                           otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {
        try {
            read.lock();
            STATUS_OVERRIDE_DELETE.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();
                InstanceInfo info = lease.getHolder();

                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }

                InstanceStatus currentOverride = overriddenInstanceStatusMap.remove(id);
                if (currentOverride != null && info != null) {
                    info.setOverriddenStatus(InstanceStatus.UNKNOWN);
                    info.setStatusWithoutDirty(newStatus);
                    long replicaDirtyTimestamp = 0;
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.valueOf(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * 触发注册表中的剔除
     * Evicts everything in the instance registry that has expired, if expiry is enabled.
     *
     * @see com.netflix.eureka.lease.LeaseManager#evict()
     */
    @Override
    public void evict() {
        evict(0L);
    }

    /**
     * 剔除任务
     *
     * @param additionalLeaseMs 补偿时间
     */
    public void evict(long additionalLeaseMs) {
        logger.debug("Running the evict task");

        // 未开启剔除任务，或未达到剔除要求
        if (!isLeaseExpirationEnabled()) {
            logger.debug("DS: lease expiration is currently disabled.");
            return;
        }

        // We collect first all expired items, to evict them in random order. For large eviction sets,
        // if we do not that, we might wipe out whole apps before self preservation kicks in. By randomizing it,
        // the impact should be evenly distributed across all applications.
        // 获得所有过期的租约，随机剔除
        List<Lease<InstanceInfo>> expiredLeases = new ArrayList<>();
        for (Entry<String, Map<String, Lease<InstanceInfo>>> groupEntry : registry.entrySet()) {
            Map<String, Lease<InstanceInfo>> leaseMap = groupEntry.getValue();
            if (leaseMap == null) {
                continue;
            }

            for (Entry<String, Lease<InstanceInfo>> leaseEntry : leaseMap.entrySet()) {
                Lease<InstanceInfo> lease = leaseEntry.getValue();
                // 判断是否过期
                if (lease.isExpired(additionalLeaseMs) && lease.getHolder() != null) {
                    expiredLeases.add(lease);
                }
            }
        }

        // To compensate for GC pauses or drifting local time, we need to use current registry size as a base for
        // triggering self-preservation. Without that we would wipe out full registry.
        // 为了补偿GC暂停或本地时间漂移，需要使用当前注册表大小作为触发自我保存的基础；否则将清除完整的注册表

        // 计算 最大允许清理租约数量
        int registrySize = (int) this.getLocalRegistrySize();
        int registrySizeThreshold = (int) (registrySize * serverConfig.getRenewalPercentThreshold());
        int evictionLimit = registrySize - registrySizeThreshold;

        // 计算 清理租约数量，会分批过期
        int toEvict = Math.min(expiredLeases.size(), evictionLimit);
        if (toEvict <= 0) {
            return;
        }

        logger.info("Evicting {} items (expired={}, evictionLimit={})", toEvict, expiredLeases.size(),
                evictionLimit);

        // 传入当前时间为种子生成随机，避免 Java 的伪随机情况
        Random random = new Random(System.currentTimeMillis());
        for (int i = 0; i < toEvict; i++) {
            // 逐个过期，算法： Knuth shuffle algorithm
            // 随机调换后面的元素到当前位置
            int next = i + random.nextInt(expiredLeases.size() - i);
            Collections.swap(expiredLeases, i, next);
            Lease<InstanceInfo> lease = expiredLeases.get(i);

            String appName = lease.getHolder().getAppName();
            String id = lease.getHolder().getId();
            EXPIRED.increment();
            logger.warn("DS: Registry: expired lease for {}/{}", appName, id);

            this.internalCancel(appName, id, false);
        }
    }

    /**
     * Returns the given app that is in this instance only, falling back to other regions transparently only
     * if specified in this client configuration.
     *
     * @param appName the application name of the application
     * @return the application
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        return this.getApplication(appName, !disableTransparentFallback);
    }

    /**
     * Get application information.
     *
     * @param appName             The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     */
    @Override
    public Application getApplication(String appName, boolean includeRemoteRegion) {
        Application app = null;

        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);

        if (leaseMap != null && leaseMap.size() > 0) {
            for (Entry<String, Lease<InstanceInfo>> entry : leaseMap.entrySet()) {
                if (app == null) {
                    app = new Application(appName);
                }
                app.addInstance(decorateInstanceInfo(entry.getValue()));
            }
        } else if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application;
                }
            }
        }
        return app;
    }

    /**
     * 获得注册的应用集合
     * <p>
     * Get all applications in this instance registry, falling back to other regions if allowed in the Eureka config.
     *
     * @return the list of all known applications
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    @Override
    public Applications getApplications() {
        // 是否禁用本地读取不到注册信息，从远程 Eureka-Server 读取
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        if (disableTransparentFallback) {
            // 禁用了从远端回退获取
            return getApplicationsFromLocalRegionOnly();
        } else {
            // 否则从远端获取
            return getApplicationsFromAllRemoteRegions();  // Behavior of falling back to remote region can be disabled.
        }
    }

    /**
     * Returns applications including instances from all remote regions. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with a <code>null</code> argument.
     */
    public Applications getApplicationsFromAllRemoteRegions() {
        return getApplicationsFromMultipleRegions(allKnownRemoteRegions);
    }

    /**
     * 从本地获取注册表的应用程序包含的实例
     * Returns applications including instances from local region only. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with an empty array.
     */
    @Override
    public Applications getApplicationsFromLocalRegionOnly() {
        // 不从其他区域获取
        return getApplicationsFromMultipleRegions(EMPTY_STR_ARRAY);
    }

    /**
     * 获得注册的应用集合
     * <p>
     * 此方法将返回具有来自所有传递的远程区域以及当前区域的实例的应用程序。
     * 因此，这给出了来自多个区域的实例的并集视图。
     * <p>
     * This method will return applications with instances from all passed remote regions as well as the current region.
     * Thus, this gives a union view of instances from multiple regions. <br/>
     * The application instances for which this union will be done can be restricted to the names returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for every region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     * If you are not selectively requesting for a remote region, use {@link #getApplicationsFromAllRemoteRegions()}
     * or {@link #getApplicationsFromLocalRegionOnly()}
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> or empty no remote regions are
     *                      included.
     * @return The applications with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be only for certain whitelisted apps as explained above.
     */
    public Applications getApplicationsFromMultipleRegions(String[] remoteRegions) {
        // 是否包含远端区域
        boolean includeRemoteRegion = null != remoteRegions && remoteRegions.length != 0;
        logger.debug("Fetching applications registry with remote regions: {}, Regions argument {}",
                includeRemoteRegion, remoteRegions);
        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS.increment();
        } else {
            GET_ALL_CACHE_MISS.increment();
        }

        // 获取注册的应用集合
        Applications apps = new Applications();
        apps.setVersion(1L);
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {
                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();
                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }
                    // 装饰实例信息，添加最新的过期信息
                    // 添加到app中
                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }

        // 远端app信息处理
        if (includeRemoteRegion) {
            for (String remoteRegion : remoteRegions) {
                RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                if (null != remoteRegistry) {
                    Applications remoteApps = remoteRegistry.getApplications();
                    for (Application application : remoteApps.getRegisteredApplications()) {
                        if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                            logger.info("Application {}  fetched from the remote region {}",
                                    application.getName(), remoteRegion);
                            // 现在的远端信息
                            Application appInstanceTillNow = apps.getRegisteredApplications(application.getName());
                            if (appInstanceTillNow == null) {
                                appInstanceTillNow = new Application(application.getName());
                                apps.addApplication(appInstanceTillNow);
                            }
                            for (InstanceInfo instanceInfo : application.getInstances()) {
                                appInstanceTillNow.addInstance(instanceInfo);
                            }
                        } else {
                            logger.debug("Application {} not fetched from the remote region {} as there exists a "
                                            + "whitelist and this app is not in the whitelist.",
                                    application.getName(), remoteRegion);
                        }
                    }
                } else {
                    logger.warn("No remote registry available for the remote region {}", remoteRegion);
                }
            }
        }
        // 重新设置hashcode
        // 用于校验增量获取的注册信息和 Eureka-Server 全量的注册信息是否一致( 完整 )
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    private boolean shouldFetchFromRemoteRegistry(String appName, String remoteRegion) {
        Set<String> whiteList = serverConfig.getRemoteRegionAppWhitelist(remoteRegion);
        if (null == whiteList) {
            whiteList = serverConfig.getRemoteRegionAppWhitelist(null); // see global whitelist.
        }
        return null == whiteList || whiteList.contains(appName);
    }

    /**
     * 获取全量应用集合
     * Get the registry information about all {@link Applications}.
     *
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return applications
     * @deprecated Use {@link #getApplicationsFromMultipleRegions(String[])} instead. This method has a flawed behavior
     * of transparently falling back to a remote region if no instances for an app is available locally. The new
     * behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplications(boolean includeRemoteRegion) {
        GET_ALL_CACHE_MISS.increment();
        Applications apps = new Applications();
        apps.setVersion(1L);
        // 组装应用信息
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {

                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();

                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }

                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }
        // 包括远端的信息
        if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Applications applications = remoteRegistry.getApplications();
                for (Application application : applications
                        .getRegisteredApplications()) {
                    Application appInLocalRegistry = apps
                            .getRegisteredApplications(application.getName());
                    if (appInLocalRegistry == null) {
                        apps.addApplication(application);
                    }
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    /**
     * 获取应用增量应用集合
     * <p>
     * Get the registry information about the delta changes. The deltas are
     * cached for a window specified by
     * {@link EurekaServerConfig#getRetentionTimeInMSInDeltaQueue()}. Subsequent
     * requests for delta information may return the same information and client
     * must make sure this does not adversely affect them.
     *
     * @return all application deltas.
     * @deprecated use {@link #getApplicationDeltasFromMultipleRegions(String[])} instead. This method has a
     * flawed behavior of transparently falling back to a remote region if no instances for an app is available locally.
     * The new behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplicationDeltas() {
        // 添加 增量获取次数 到 监控
        GET_ALL_CACHE_MISS_DELTA.increment();

        // 初始化 变化的应用集合
        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDelta().get());
        Map<String, Application> applicationInstancesMap = new HashMap<>();

        try {
            // 获取写锁
            write.lock();

            // 获取 最近租约变更记录队列
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is : {}",
                    this.recentlyChangedQueue.size());

            // 拼装 变化的应用集合
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug(
                        "The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo
                        .getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();

            if (!disableTransparentFallback) {
                Applications allAppsInLocalRegion = getApplications(false);

                for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                    Applications applications = remoteRegistry.getApplicationDeltas();
                    for (Application application : applications.getRegisteredApplications()) {
                        Application appInLocalRegistry =
                                allAppsInLocalRegion.getRegisteredApplications(application.getName());
                        if (appInLocalRegistry == null) {
                            apps.addApplication(application);
                        }
                    }
                }
            }

            // 获取全量应用集合，通过它计算一致性哈希值
            Applications allApps = getApplications(!disableTransparentFallback);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the application delta also including instances from the passed remote regions, with the instances from the
     * local region. <br/>
     * <p>
     * The remote regions from where the instances will be chosen can further be restricted if this application does not
     * appear in the whitelist specified for the region as returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for a region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> all remote regions are included.
     *                      If empty list then no remote region is included.
     * @return The delta with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be further be restricted as explained above. <code>null</code> if the application does
     * not exist locally or in remote regions.
     */
    public Applications getApplicationDeltasFromMultipleRegions(String[] remoteRegions) {
        if (null == remoteRegions) {
            remoteRegions = allKnownRemoteRegions; // null means all remote regions.
        }

        boolean includeRemoteRegion = remoteRegions.length != 0;

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS_DELTA.increment();
        } else {
            GET_ALL_CACHE_MISS_DELTA.increment();
        }

        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDeltaWithRegions().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        try {
            write.lock();
            // 最近改变队列，注册信息增量获取
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is :{}", this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug("The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo.getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            if (includeRemoteRegion) {
                for (String remoteRegion : remoteRegions) {
                    RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                    if (null != remoteRegistry) {
                        Applications remoteAppsDelta = remoteRegistry.getApplicationDeltas();
                        if (null != remoteAppsDelta) {
                            for (Application application : remoteAppsDelta.getRegisteredApplications()) {
                                if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                                    Application appInstanceTillNow =
                                            apps.getRegisteredApplications(application.getName());
                                    if (appInstanceTillNow == null) {
                                        appInstanceTillNow = new Application(application.getName());
                                        apps.addApplication(appInstanceTillNow);
                                    }
                                    for (InstanceInfo instanceInfo : application.getInstances()) {
                                        appInstanceTillNow.addInstance(new InstanceInfo(instanceInfo));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Applications allApps = getApplicationsFromMultipleRegions(remoteRegions);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id      the unique identifier of the instance.
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id) {
        return this.getInstanceByAppAndId(appName, id, true);
    }

    /**
     * 通过appName和实例id获取实例信息
     * <p>
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName              the application name for which the information is requested.
     * @param id                   the unique identifier of the instance.
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     *                             如果需要包含来自远程区域的应用程序
     *                             如该属性的区域{@link URL}所示
     *                             {@link EurekaServerConfig＃getRemoteRegionUrls（）}，则为true，否则为false
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id, boolean includeRemoteRegions) {
        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);
        Lease<InstanceInfo> lease = null;
        if (leaseMap != null) {
            lease = leaseMap.get(id);
        }
        if (lease != null
                && (!isLeaseExpirationEnabled() || !lease.isExpired())) {
            return decorateInstanceInfo(lease);
        } else if (includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application.getByInstanceId(id);
                }
            }
        }
        return null;
    }

    /**
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(String)
     * @deprecated Try {@link #getInstanceByAppAndId(String, String)} instead.
     * <p>
     * Get all instances by ID, including automatically asking other regions if the ID is unknown.
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id) {
        return this.getInstancesById(id, true);
    }

    /**
     * @param id                   the unique id of the instance
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return list of InstanceInfo objects.
     * @deprecated Try {@link #getInstanceByAppAndId(String, String, boolean)} instead.
     * <p>
     * Get the list of instances by its unique id.
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id, boolean includeRemoteRegions) {
        List<InstanceInfo> list = new ArrayList<InstanceInfo>();

        for (Iterator<Entry<String, Map<String, Lease<InstanceInfo>>>> iter =
             registry.entrySet().iterator(); iter.hasNext(); ) {

            Map<String, Lease<InstanceInfo>> leaseMap = iter.next().getValue();
            if (leaseMap != null) {
                Lease<InstanceInfo> lease = leaseMap.get(id);

                if (lease == null || (isLeaseExpirationEnabled() && lease.isExpired())) {
                    continue;
                }

                if (list == Collections.EMPTY_LIST) {
                    list = new ArrayList<InstanceInfo>();
                }
                list.add(decorateInstanceInfo(lease));
            }
        }
        if (list.isEmpty() && includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                for (Application application : remoteRegistry.getApplications()
                        .getRegisteredApplications()) {
                    InstanceInfo instanceInfo = application.getByInstanceId(id);
                    if (instanceInfo != null) {
                        list.add(instanceInfo);
                        return list;
                    }
                }
            }
        }
        return list;
    }

    /**
     * 装饰实例信息
     *
     * @param lease 续约信息
     * @return 装饰后的实例信息
     */
    private InstanceInfo decorateInstanceInfo(Lease<InstanceInfo> lease) {
        InstanceInfo info = lease.getHolder();

        // client app settings
        int renewalInterval = LeaseInfo.DEFAULT_LEASE_RENEWAL_INTERVAL;
        int leaseDuration = LeaseInfo.DEFAULT_LEASE_DURATION;

        // TODO: clean this up
        // 让续约周期和过期时间不为空，以缓存为主，不存在使用默认
        if (info.getLeaseInfo() != null) {
            renewalInterval = info.getLeaseInfo().getRenewalIntervalInSecs();
            leaseDuration = info.getLeaseInfo().getDurationInSecs();
        }

        // 设置续约信息
        info.setLeaseInfo(LeaseInfo.Builder.newBuilder()
                .setRegistrationTimestamp(lease.getRegistrationTimestamp())
                .setRenewalTimestamp(lease.getLastRenewalTimestamp())
                .setServiceUpTimestamp(lease.getServiceUpTimestamp())
                .setRenewalIntervalInSecs(renewalInterval)
                .setDurationInSecs(leaseDuration)
                .setEvictionTimestamp(lease.getEvictionTimestamp()).build());

        info.setIsCoordinatingDiscoveryServer();
        return info;
    }

    /**
     * Servo route; do not call.
     *
     * @return servo data
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsInLastMin",
            description = "Number of total heartbeats received in the last minute", type = DataSourceType.GAUGE)
    @Override
    public long getNumOfRenewsInLastMin() {
        return renewsLastMin.getCount();
    }

    /**
     * Gets the threshold for the renewals per minute.
     *
     * @return the integer representing the threshold for the renewals per
     * minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsPerMinThreshold", type = DataSourceType.GAUGE)
    @Override
    public int getNumOfRenewsPerMinThreshold() {
        return numberOfRenewsPerMinThreshold;
    }

    /**
     * Get the N instances that are most recently registered.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNRegisteredInstances() {
        List<Pair<Long, String>> list = new ArrayList<Pair<Long, String>>(recentRegisteredQueue);
        Collections.reverse(list);
        return list;
    }

    /**
     * Get the N instances that have most recently canceled.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNCanceledInstances() {
        List<Pair<Long, String>> list = new ArrayList<Pair<Long, String>>(recentCanceledQueue);
        Collections.reverse(list);
        return list;
    }

    /**
     * 过期缓存
     *
     * @param appName          应用名称
     * @param vipAddress       vip地址
     * @param secureVipAddress 安全vip地址
     */
    private void invalidateCache(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress) {
        responseCache.invalidate(appName, vipAddress, secureVipAddress);
    }

    /**
     * 更新自我保护机制的阈值
     */
    protected void updateRenewsPerMinThreshold() {
        this.numberOfRenewsPerMinThreshold =
                // 期待能收到消息的客户端数目
                (int) (this.expectedNumberOfClientsSendingRenews
                        // 期待每分钟能收到的数目
                        * (60.0 / serverConfig.getExpectedClientRenewalIntervalSeconds())
                        // 阈值
                        * serverConfig.getRenewalPercentThreshold());
    }

    /**
     * 最近改变的一项
     */
    private static final class RecentlyChangedItem {
        /**
         * 创建时间
         */
        private final long                lastUpdateTime;
        /**
         * 实例租约信息
         */
        private final Lease<InstanceInfo> leaseInfo;

        public RecentlyChangedItem(Lease<InstanceInfo> lease) {
            this.leaseInfo = lease;
            lastUpdateTime = System.currentTimeMillis();
        }

        public long getLastUpdateTime() {
            return this.lastUpdateTime;
        }

        public Lease<InstanceInfo> getLeaseInfo() {
            return this.leaseInfo;
        }
    }

    protected void postInit() {
        // 启动每分钟次数测量
        renewsLastMin.start();
        // 清理过期任务
        if (evictionTaskRef.get() != null) {
            evictionTaskRef.get().cancel();
        }
        // 设置新的任务并启动
        evictionTaskRef.set(new EvictionTask());
        evictionTimer.schedule(evictionTaskRef.get(),
                serverConfig.getEvictionIntervalTimerInMs(),
                serverConfig.getEvictionIntervalTimerInMs());
    }

    /**
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        deltaRetentionTimer.cancel();
        evictionTimer.cancel();
        renewsLastMin.stop();
        responseCache.stop();
    }

    @com.netflix.servo.annotations.Monitor(name = "numOfElementsinInstanceCache", description = "Number of overrides " +
            "in the instance Cache", type = DataSourceType.GAUGE)
    public long getNumberofElementsininstanceCache() {
        return overriddenInstanceStatusMap.size();
    }

    /**
     * 剔除过期的实例 定时任务
     */
    class EvictionTask extends TimerTask {

        private final AtomicLong lastExecutionNanosRef = new AtomicLong(0L);

        @Override
        public void run() {
            try {
                // 获取补偿时间
                long compensationTimeMs = this.getCompensationTimeMs();
                logger.info("Running the evict task with compensationTime {}ms", compensationTimeMs);
                // 执行剔除任务
                evict(compensationTimeMs);
            } catch (Throwable e) {
                logger.error("Could not run the evict task", e);
            }
        }

        /**
         * 补偿时间毫秒
         * 计算公式 = 当前时间 - 最后任务执行时间 - 任务执行频率
         * 由于 JVM GC ，又或是时间偏移( clock skew ) 等原因，定时器执行实际比预期会略有延迟。
         * 笔者在本机低负载运行，大概 10 ms 内。
         * <p>
         * compute a compensation time defined as the actual time this task was executed since the prev iteration,
         * vs the configured amount of time for execution. This is useful for cases where changes in time (due to
         * clock skew or gc for example) causes the actual eviction task to execute later than the desired time
         * according to the configured cycle.
         */
        long getCompensationTimeMs() {
            long currNanos = this.getCurrentTimeNano();
            long lastNanos = lastExecutionNanosRef.getAndSet(currNanos);
            if (lastNanos == 0L) {
                return 0L;
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(currNanos - lastNanos);
            long compensationTime = elapsedMs - serverConfig.getEvictionIntervalTimerInMs();
            return Math.max(compensationTime, 0L);
        }

        long getCurrentTimeNano() {
            return System.nanoTime();
        }

    }

    /**
     * 循环队列
     */
    /* visible for testing */ static class CircularQueue<E> extends AbstractQueue<E> {

        /**
         * 实际存储
         */
        private final ArrayBlockingQueue<E> delegate;

        /**
         * 队列容量
         */
        private final int capacity;

        public CircularQueue(int capacity) {
            this.capacity = capacity;
            this.delegate = new ArrayBlockingQueue<>(capacity);
        }

        @Override
        public Iterator<E> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean offer(E e) {
            // 存储空间不足时，移除队首元素
            while (!delegate.offer(e)) {
                delegate.poll();
            }
            return true;
        }

        @Override
        public E poll() {
            return delegate.poll();
        }

        @Override
        public E peek() {
            return delegate.peek();
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Object[] toArray() {
            return delegate.toArray();
        }
    }

    /**
     * 实例状态重写的规则
     *
     * @return The rule that will process the instance status override.
     */
    protected abstract InstanceStatusOverrideRule getInstanceInfoOverrideRule();

    /**
     * 获取重写的实例状态
     *
     * @param r             实例信息
     * @param existingLease 已存在的租约信息
     * @param isReplication 是否其他节点复制
     * @return 重写后的实例状态
     */
    protected InstanceInfo.InstanceStatus getOverriddenInstanceStatus(InstanceInfo r,
                                                                      Lease<InstanceInfo> existingLease,
                                                                      boolean isReplication) {
        InstanceStatusOverrideRule rule = this.getInstanceInfoOverrideRule();
        logger.debug("Processing override status using rule: {}", rule);
        // 按实例状态重写规则顺序匹配
        return rule.apply(r, existingLease, isReplication).status();
    }

    /**
     * 获取最近改变队列，将过期的项删除
     */
    private TimerTask getDeltaRetentionTask() {
        return new TimerTask() {
            @Override
            public void run() {
                Iterator<RecentlyChangedItem> it = recentlyChangedQueue.iterator();
                while (it.hasNext()) {
                    // 过期，移除
                    if (it.next().getLastUpdateTime() <
                            System.currentTimeMillis() - serverConfig.getRetentionTimeInMSInDeltaQueue()) {
                        it.remove();
                    } else {
                        break;
                    }
                }
            }
        };
    }
}
