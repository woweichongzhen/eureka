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

package com.netflix.discovery;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckCallbackToHandlerBridge;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.resolver.ClosableResolver;
import com.netflix.discovery.shared.resolver.EndpointRandomizer;
import com.netflix.discovery.shared.resolver.ResolverUtils;
import com.netflix.discovery.shared.resolver.aws.ApplicationsResolver;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpClientFactory;
import com.netflix.discovery.shared.transport.EurekaHttpClients;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.EurekaTransportConfig;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.jersey.EurekaJerseyClient;
import com.netflix.discovery.shared.transport.jersey.Jersey1DiscoveryClientOptionalArgs;
import com.netflix.discovery.shared.transport.jersey.Jersey1TransportClientFactories;
import com.netflix.discovery.shared.transport.jersey.TransportClientFactories;
import com.netflix.discovery.util.ThresholdLevelsMetric;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRATION_PREFIX;
import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRY_PREFIX;

/**
 * 服务发现客户端
 * 向 Eureka-Server 注册自身服务
 * 向 Eureka-Server 续约自身服务
 * 向 Eureka-Server 取消自身服务，当关闭时
 * 从 Eureka-Server 查询应用集合和应用实例信息
 * <p>
 * The class that is instrumental for interactions with <tt>Eureka Server</tt>.
 *
 * <p>
 * <tt>Eureka Client</tt> is responsible for a) <em>Registering</em> the
 * instance with <tt>Eureka Server</tt> b) <em>Renewal</em>of the lease with
 * <tt>Eureka Server</tt> c) <em>Cancellation</em> of the lease from
 * <tt>Eureka Server</tt> during shutdown
 * <p>
 * d) <em>Querying</em> the list of services/instances registered with
 * <tt>Eureka Server</tt>
 * <p>
 *
 * <p>
 * <tt>Eureka Client</tt> needs a configured list of <tt>Eureka Server</tt>
 * {@link java.net.URL}s to talk to.These {@link java.net.URL}s are typically amazon elastic eips
 * which do not change. All of the functions defined above fail-over to other
 * {@link java.net.URL}s specified in the list in the case of failure.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 * @author Spencer Gibb
 */
@Singleton
public class DiscoveryClient implements EurekaClient {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryClient.class);

    // Constants
    public static final String HTTP_X_DISCOVERY_ALLOW_REDIRECT = "X-Discovery-AllowRedirect";

    private static final String VALUE_DELIMITER = ",";
    private static final String COMMA_STRING    = VALUE_DELIMITER;

    /**
     * @deprecated here for legacy support as the client config has moved to be an instance variable
     */
    @Deprecated
    private static EurekaClientConfig staticClientConfig;

    // Timers
    private static final String                          PREFIX                        = "DiscoveryClient_";
    private final        Counter                         RECONCILE_HASH_CODES_MISMATCH = Monitors.newCounter(
            PREFIX + "ReconcileHashCodeMismatch");
    private final        com.netflix.servo.monitor.Timer FETCH_REGISTRY_TIMER          = Monitors
            .newTimer(PREFIX + "FetchRegistry");
    private final        Counter                         REREGISTER_COUNTER            = Monitors.newCounter(PREFIX
            + "Reregister");

    // instance variables
    /**
     * A scheduler to be used for the following 3 tasks:
     * - updating service urls
     * - scheduling a TimedSuperVisorTask
     * 定时任务线程池：为后面两个线程池执行失败延时的定时任务，内部再包装线程池
     */
    private final ScheduledExecutorService scheduler;
    // additional executors for supervised subtasks
    /**
     * 心跳任务线程池
     */
    private final ThreadPoolExecutor       heartbeatExecutor;
    /**
     * 缓存刷新线程池
     */
    private final ThreadPoolExecutor       cacheRefreshExecutor;

    private TimedSupervisorTask cacheRefreshTask;
    private TimedSupervisorTask heartbeatTask;

    private final Provider<HealthCheckHandler>  healthCheckHandlerProvider;
    private final Provider<HealthCheckCallback> healthCheckCallbackProvider;
    /**
     * 注册前置执行器
     */
    private final PreRegistrationHandler        preRegistrationHandler;

    /**
     * Applications 在本地的缓存
     */
    private final AtomicReference<Applications> localRegionApps = new AtomicReference<>();

    /**
     * 拉取后的注册表更新锁
     */
    private final Lock                   fetchRegistryUpdateLock = new ReentrantLock();
    // monotonically increasing generation counter to ensure stale threads do not reset registry to an older version
    /**
     * 拉取注册信息次数
     * 单调增加的生成计数器，以确保旧线程不会将注册表重置为旧版本
     */
    private final AtomicLong             fetchRegistryGeneration;
    private final ApplicationInfoManager applicationInfoManager;
    private final InstanceInfo           instanceInfo;

    /**
     * 区域( Region )集合的注册信息
     */
    private final AtomicReference<String>   remoteRegionsToFetch;
    /**
     * 区域( Region )集合的注册信息
     */
    private final AtomicReference<String[]> remoteRegionsRef;
    private final InstanceRegionChecker     instanceRegionChecker;

    private final EndpointUtils.ServiceUrlRandomizer urlRandomizer;
    private final EndpointRandomizer                 endpointRandomizer;
    private final Provider<BackupRegistry>           backupRegistryProvider;
    /**
     * eureka http传输
     */
    private final EurekaTransport                    eurekaTransport;

    /**
     * 健康检查处理器引用
     */
    private final    AtomicReference<HealthCheckHandler> healthCheckHandlerRef = new AtomicReference<>();
    private volatile Map<String, Applications>           remoteRegionVsApps    = new ConcurrentHashMap<>();

    /**
     * 本地缓存的远端对应实例的状态
     */
    private volatile InstanceInfo.InstanceStatus lastRemoteInstanceStatus = InstanceInfo.InstanceStatus.UNKNOWN;

    /**
     * eureka事件监听器，自行实现
     */
    private final CopyOnWriteArraySet<EurekaEventListener> eventListeners = new CopyOnWriteArraySet<>();

    private String appPathIdentifier;

    /**
     * 状态改变监听器
     */
    private ApplicationInfoManager.StatusChangeListener statusChangeListener;

    private InstanceInfoReplicator instanceInfoReplicator;

    /**
     * 注册信息的应用实例数
     */
    private volatile int registrySize = 0;

    /**
     * 最后成功从 Eureka-Server 拉取注册信息时间戳
     */
    private volatile long lastSuccessfulRegistryFetchTimestamp = -1;

    /**
     * 最后成功向 Eureka-Server 心跳时间戳
     */
    private volatile long lastSuccessfulHeartbeatTimestamp = -1;

    /**
     * 心跳监控
     */
    private final ThresholdLevelsMetric heartbeatStalenessMonitor;

    /**
     * 拉取注册信息的监控
     */
    private final ThresholdLevelsMetric registryStalenessMonitor;

    /**
     * 是否关闭，false未关闭，true已关闭
     */
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    protected final EurekaClientConfig    clientConfig;
    protected final EurekaTransportConfig transportConfig;

    /**
     * 初始化完成的时间戳
     */
    private final long initTimestampMs;

    /**
     * 初始化注册的数量
     */
    private final int initRegistrySize;

    private final Stats stats = new Stats();

    private static final class EurekaTransport {
        private ClosableResolver       bootstrapResolver;
        private TransportClientFactory transportClientFactory;

        private EurekaHttpClient        registrationClient;
        private EurekaHttpClientFactory registrationClientFactory;

        private EurekaHttpClient        queryClient;
        private EurekaHttpClientFactory queryClientFactory;

        void shutdown() {
            if (registrationClientFactory != null) {
                registrationClientFactory.shutdown();
            }

            if (queryClientFactory != null) {
                queryClientFactory.shutdown();
            }

            if (registrationClient != null) {
                registrationClient.shutdown();
            }

            if (queryClient != null) {
                queryClient.shutdown();
            }

            if (transportClientFactory != null) {
                transportClientFactory.shutdown();
            }

            if (bootstrapResolver != null) {
                bootstrapResolver.shutdown();
            }
        }
    }

    public static class DiscoveryClientOptionalArgs extends Jersey1DiscoveryClientOptionalArgs {

    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config) {
        this(myInfo, config, null);
    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config, DiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    /**
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config) {
        this(applicationInfoManager, config, null);
    }

    /**
     * @deprecated use the version that take {@link com.netflix.discovery.AbstractDiscoveryClientOptionalArgs} instead
     */
    @Deprecated
    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config,
                           DiscoveryClientOptionalArgs args) {
        this(applicationInfoManager, config, (AbstractDiscoveryClientOptionalArgs) args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config,
                           AbstractDiscoveryClientOptionalArgs args) {
        this(applicationInfoManager, config, args, ResolverUtils::randomize);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config,
                           AbstractDiscoveryClientOptionalArgs args, EndpointRandomizer randomizer) {
        this(applicationInfoManager, config, args, new Provider<BackupRegistry>() {
            private volatile BackupRegistry backupRegistryInstance;

            @Override
            public synchronized BackupRegistry get() {
                if (backupRegistryInstance == null) {
                    String backupRegistryClassName = config.getBackupRegistryImpl();
                    if (null != backupRegistryClassName) {
                        try {
                            // 创建备选实例，获取备选注册实例
                            backupRegistryInstance =
                                    (BackupRegistry) Class.forName(backupRegistryClassName).newInstance();
                            logger.info("Enabled backup registry of type {}", backupRegistryInstance.getClass());
                        } catch (InstantiationException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (IllegalAccessException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (ClassNotFoundException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        }
                    }

                    if (backupRegistryInstance == null) {
                        logger.warn("Using default backup registry implementation which does not do anything.");
                        backupRegistryInstance = new NotImplementedRegistryImpl();
                    }
                }

                return backupRegistryInstance;
            }
        }, randomizer);
    }

    /**
     * @deprecated Use {@link #DiscoveryClient(ApplicationInfoManager, EurekaClientConfig, AbstractDiscoveryClientOptionalArgs,
     * Provider<BackupRegistry>, EndpointRandomizer)}
     */
    @Deprecated
    DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config,
                    AbstractDiscoveryClientOptionalArgs args,
                    Provider<BackupRegistry> backupRegistryProvider) {
        this(applicationInfoManager, config, args, backupRegistryProvider, ResolverUtils::randomize);
    }

    /**
     * 最终的构造函数
     *
     * @param applicationInfoManager 应用信息管理
     * @param config                 client信息配置
     * @param args                   客户端可选参数抽象基类
     * @param backupRegistryProvider 备份的注册中心接口，无合适的实现
     * @param endpointRandomizer     随机server端点
     */
    @Inject
    DiscoveryClient(ApplicationInfoManager applicationInfoManager,
                    EurekaClientConfig config,
                    AbstractDiscoveryClientOptionalArgs args,
                    Provider<BackupRegistry> backupRegistryProvider,
                    EndpointRandomizer endpointRandomizer) {
        // args 可选参数赋值
        if (args != null) {
            this.healthCheckHandlerProvider = args.healthCheckHandlerProvider;
            this.healthCheckCallbackProvider = args.healthCheckCallbackProvider;
            this.eventListeners.addAll(args.getEventListeners());
            this.preRegistrationHandler = args.preRegistrationHandler;
        } else {
            this.healthCheckCallbackProvider = null;
            this.healthCheckHandlerProvider = null;
            this.preRegistrationHandler = null;
        }

        // app信息赋值
        this.applicationInfoManager = applicationInfoManager;
        InstanceInfo myInfo = applicationInfoManager.getInfo();

        // 客户端配置赋值
        clientConfig = config;
        staticClientConfig = clientConfig;
        transportConfig = config.getTransportConfig();
        instanceInfo = myInfo;
        if (myInfo != null) {
            appPathIdentifier = instanceInfo.getAppName() + "/" + instanceInfo.getId();
        } else {
            logger.warn("Setting instanceInfo to a passed in null value");
        }

        // 后备注册中心，端点随机，url随机赋值
        this.backupRegistryProvider = backupRegistryProvider;
        this.endpointRandomizer = endpointRandomizer;
        this.urlRandomizer = new EndpointUtils.InstanceInfoBasedUrlRandomizer(instanceInfo);

        // 初始化app本地缓存，定时任务会定时拉取
        localRegionApps.set(new Applications());
        fetchRegistryGeneration = new AtomicLong(0);

        // 获取大区信息和引用
        remoteRegionsToFetch = new AtomicReference<String>(clientConfig.fetchRegistryForRemoteRegions());
        remoteRegionsRef = new AtomicReference<>(remoteRegionsToFetch.get() == null ? null :
                remoteRegionsToFetch.get().split(","));

        // 拉取注册信息的监控
        if (config.shouldFetchRegistry()) {
            this.registryStalenessMonitor = new ThresholdLevelsMetric(this, METRIC_REGISTRY_PREFIX + "lastUpdateSec_"
                    , new long[] {15L, 30L, 60L, 120L, 240L, 480L});
        } else {
            this.registryStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }

        // 心跳的监控
        if (config.shouldRegisterWithEureka()) {
            this.heartbeatStalenessMonitor = new ThresholdLevelsMetric(this, METRIC_REGISTRATION_PREFIX +
                    "lastHeartbeatSec_", new long[] {15L, 30L, 60L, 120L, 240L, 480L});
        } else {
            this.heartbeatStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }

        logger.info("Initializing Eureka in region {}", clientConfig.getRegion());

        // 既不注册，也不拉取，即不和 eureka server 交互，释放一些资源，直接返回
        if (!config.shouldRegisterWithEureka() && !config.shouldFetchRegistry()) {
            logger.info("Client configured to neither register nor query for data.");
            scheduler = null;
            heartbeatExecutor = null;
            cacheRefreshExecutor = null;
            eurekaTransport = null;
            instanceRegionChecker = new InstanceRegionChecker(new PropertyBasedAzToRegionMapper(config),
                    clientConfig.getRegion());

            // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
            // to work with DI'd DiscoveryClient
            DiscoveryManager.getInstance().setDiscoveryClient(this);
            DiscoveryManager.getInstance().setEurekaClientConfig(config);

            initTimestampMs = System.currentTimeMillis();
            initRegistrySize = this.getApplications().size();
            registrySize = initRegistrySize;
            logger.info("Discovery Client initialized at timestamp {} with initial instances count: {}",
                    initTimestampMs, initRegistrySize);

            return;  // no need to setup up an network tasks and we are done
        }

        try {
            // default size of 2 - 1 each for heartbeat and cacheRefresh
            // 心跳、缓存刷新线程池初始化，heartbeatExecutor和cacheRefreshExecutor再提交给scheduler具体的任务
            scheduler = Executors.newScheduledThreadPool(2,
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-%d")
                            .setDaemon(true)
                            .build());
            heartbeatExecutor = new ThreadPoolExecutor(
                    1, clientConfig.getHeartbeatExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-HeartbeatExecutor-%d")
                            .setDaemon(true)
                            .build()
            );  // use direct handoff
            cacheRefreshExecutor = new ThreadPoolExecutor(
                    1, clientConfig.getCacheRefreshExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-CacheRefreshExecutor-%d")
                            .setDaemon(true)
                            .build()
            );  // use direct handoff

            // 网络通信任务
            eurekaTransport = new EurekaTransport();

            // 初始化服务器端点定时解析任务
            scheduleServerEndpointTask(eurekaTransport, args);

            // AWS
            AzToRegionMapper azToRegionMapper;
            if (clientConfig.shouldUseDnsForFetchingServiceUrls()) {
                azToRegionMapper = new DNSBasedAzToRegionMapper(clientConfig);
            } else {
                azToRegionMapper = new PropertyBasedAzToRegionMapper(clientConfig);
            }
            if (null != remoteRegionsToFetch.get()) {
                azToRegionMapper.setRegionsToFetch(remoteRegionsToFetch.get().split(","));
            }

            // 应用实例信息区域(region)校验
            instanceRegionChecker = new InstanceRegionChecker(azToRegionMapper, clientConfig.getRegion());
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize DiscoveryClient!", e);
        }

        // 拉取注册信息
        if (clientConfig.shouldFetchRegistry() && !fetchRegistry(false)) {
            // 拉取失败则从备份注册中心拉取
            fetchRegistryFromBackup();
        }

        // call and execute the pre registration handler before all background tasks (inc registration) is started
        // 执行本地注册的前置执行器
        if (this.preRegistrationHandler != null) {
            this.preRegistrationHandler.beforeRegistration();
        }

        // 配置了启动时立即注册，则立即发起注册，注册不成功抛出异常
        if (clientConfig.shouldRegisterWithEureka() && clientConfig.shouldEnforceRegistrationAtInit()) {
            try {
                // 发送注册请求
                if (!register()) {
                    throw new IllegalStateException("Registration error at startup. Invalid server response.");
                }
            } catch (Throwable th) {
                logger.error("Registration error at startup: {}", th.getMessage());
                throw new IllegalStateException(th);
            }
        }

        // finally, init the schedule tasks (e.g. cluster resolvers, heartbeat, instanceInfo replicator, fetch
        // 初始化心跳，拉取的定时任务，并启动延迟注册任务
        initScheduledTasks();

        // 向 servo 注册监控
        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register timers", e);
        }

        // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
        // to work with DI'd DiscoveryClient
        DiscoveryManager.getInstance().setDiscoveryClient(this);
        DiscoveryManager.getInstance().setEurekaClientConfig(config);

        // 初始化完成，设置初始化时间戳，本地应用信息数量
        initTimestampMs = System.currentTimeMillis();
        initRegistrySize = this.getApplications().size();
        registrySize = initRegistrySize;
        logger.info("Discovery Client initialized at timestamp {} with initial instances count: {}",
                initTimestampMs, initRegistrySize);
    }

    /**
     * 定时解析服务端点
     */
    private void scheduleServerEndpointTask(EurekaTransport eurekaTransport,
                                            AbstractDiscoveryClientOptionalArgs args) {
        Collection<?> additionalFilters = args == null
                ? Collections.emptyList()
                : args.additionalFilters;

        EurekaJerseyClient providedJerseyClient = args == null
                ? null
                : args.eurekaJerseyClient;

        TransportClientFactories argsTransportClientFactories = null;
        if (args != null && args.getTransportClientFactories() != null) {
            argsTransportClientFactories = args.getTransportClientFactories();
        }

        // Ignore the raw types warnings since the client filter interface changed between jersey 1/2
        @SuppressWarnings("rawtypes")
        TransportClientFactories transportClientFactories = argsTransportClientFactories == null
                ? new Jersey1TransportClientFactories()
                : argsTransportClientFactories;

        Optional<SSLContext> sslContext = args == null
                ? Optional.empty()
                : args.getSSLContext();
        Optional<HostnameVerifier> hostnameVerifier = args == null
                ? Optional.empty()
                : args.getHostnameVerifier();

        // If the transport factory was not supplied with args, assume they are using jersey 1 for passivity
        // 初始化 应用解析器的应用实例数据源
        eurekaTransport.transportClientFactory = providedJerseyClient == null
                ? transportClientFactories.newTransportClientFactory(clientConfig, additionalFilters,
                applicationInfoManager.getInfo(), sslContext, hostnameVerifier)
                : transportClientFactories.newTransportClientFactory(additionalFilters, providedJerseyClient);

        ApplicationsResolver.ApplicationsSource applicationsSource = (stalenessThreshold, timeUnit) -> {
            long thresholdInMs = TimeUnit.MILLISECONDS.convert(stalenessThreshold, timeUnit);
            long delay = getLastSuccessfulRegistryFetchTimePeriod();
            if (delay > thresholdInMs) {
                logger.info("Local registry is too stale for local lookup. Threshold:{}, actual:{}",
                        thresholdInMs, delay);
                return null;
            } else {
                return localRegionApps.get();
            }
        };

        // 创建 EndPoint 解析器
        eurekaTransport.bootstrapResolver = EurekaHttpClients.newBootstrapResolver(
                clientConfig,
                transportConfig,
                eurekaTransport.transportClientFactory,
                applicationInfoManager.getInfo(),
                applicationsSource,
                endpointRandomizer
        );

        if (clientConfig.shouldRegisterWithEureka()) {
            EurekaHttpClientFactory newRegistrationClientFactory = null;
            EurekaHttpClient newRegistrationClient = null;
            try {
                newRegistrationClientFactory = EurekaHttpClients.registrationClientFactory(
                        eurekaTransport.bootstrapResolver,
                        eurekaTransport.transportClientFactory,
                        transportConfig
                );
                newRegistrationClient = newRegistrationClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.registrationClientFactory = newRegistrationClientFactory;
            eurekaTransport.registrationClient = newRegistrationClient;
        }

        // new method (resolve from primary servers for read)
        // Configure new transport layer (candidate for injecting in the future)

        // 创建client，SessionedEurekaHttpClient
        if (clientConfig.shouldFetchRegistry()) {
            EurekaHttpClientFactory newQueryClientFactory = null;
            EurekaHttpClient newQueryClient = null;
            try {
                newQueryClientFactory = EurekaHttpClients.queryClientFactory(
                        eurekaTransport.bootstrapResolver,
                        eurekaTransport.transportClientFactory,
                        clientConfig,
                        transportConfig,
                        applicationInfoManager.getInfo(),
                        applicationsSource,
                        endpointRandomizer
                );
                newQueryClient = newQueryClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.queryClientFactory = newQueryClientFactory;
            eurekaTransport.queryClient = newQueryClient;
        }
    }

    @Override
    public EurekaClientConfig getEurekaClientConfig() {
        return clientConfig;
    }

    @Override
    public ApplicationInfoManager getApplicationInfoManager() {
        return applicationInfoManager;
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        return getApplications().getRegisteredApplications(appName);
    }

    /**
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    @Override
    public Applications getApplications() {
        return localRegionApps.get();
    }

    @Override
    public Applications getApplicationsForARegion(@Nullable String region) {
        if (instanceRegionChecker.isLocalRegion(region)) {
            return localRegionApps.get();
        } else {
            return remoteRegionVsApps.get(region);
        }
    }

    public Set<String> getAllKnownRegions() {
        String localRegion = instanceRegionChecker.getLocalRegion();
        if (!remoteRegionVsApps.isEmpty()) {
            Set<String> regions = remoteRegionVsApps.keySet();
            Set<String> toReturn = new HashSet<String>(regions);
            toReturn.add(localRegion);
            return toReturn;
        } else {
            return Collections.singleton(localRegion);
        }
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(java.lang.String)
     */
    @Override
    public List<InstanceInfo> getInstancesById(String id) {
        List<InstanceInfo> instancesList = new ArrayList<>();
        for (Application app : this.getApplications()
                .getRegisteredApplications()) {
            InstanceInfo instanceInfo = app.getByInstanceId(id);
            if (instanceInfo != null) {
                instancesList.add(instanceInfo);
            }
        }
        return instancesList;
    }

    /**
     * Register {@link HealthCheckCallback} with the eureka client.
     * <p>
     * Once registered, the eureka client will invoke the
     * {@link HealthCheckCallback} in intervals specified by
     * {@link EurekaClientConfig#getInstanceInfoReplicationIntervalSeconds()}.
     *
     * @param callback app specific healthcheck.
     * @deprecated Use
     */
    @Deprecated
    @Override
    public void registerHealthCheckCallback(HealthCheckCallback callback) {
        if (instanceInfo == null) {
            logger.error("Cannot register a listener for instance info since it is null!");
        }
        if (callback != null) {
            healthCheckHandlerRef.set(new HealthCheckCallbackToHandlerBridge(callback));
        }
    }

    @Override
    public void registerHealthCheck(HealthCheckHandler healthCheckHandler) {
        if (instanceInfo == null) {
            logger.error("Cannot register a healthcheck handler when instance info is null!");
        }
        if (healthCheckHandler != null) {
            this.healthCheckHandlerRef.set(healthCheckHandler);
            // schedule an onDemand update of the instanceInfo when a new healthcheck handler is registered
            if (instanceInfoReplicator != null) {
                instanceInfoReplicator.onDemandUpdate();
            }
        }
    }

    @Override
    public void registerEventListener(EurekaEventListener eventListener) {
        this.eventListeners.add(eventListener);
    }

    @Override
    public boolean unregisterEventListener(EurekaEventListener eventListener) {
        return this.eventListeners.remove(eventListener);
    }

    /**
     * Gets the list of instances matching the given VIP Address.
     *
     * @param vipAddress - The VIP address to match the instances for.
     * @param secure     - true if it is a secure vip address, false otherwise
     * @return - The list of {@link InstanceInfo} objects matching the criteria
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure) {
        return getInstancesByVipAddress(vipAddress, secure, instanceRegionChecker.getLocalRegion());
    }

    /**
     * Gets the list of instances matching the given VIP Address in the passed region.
     *
     * @param vipAddress - The VIP address to match the instances for.
     * @param secure     - true if it is a secure vip address, false otherwise
     * @param region     - region from which the instances are to be fetched. If <code>null</code> then local region is
     *                   assumed.
     * @return - The list of {@link InstanceInfo} objects matching the criteria, empty list if not instances found.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure,
                                                       @Nullable String region) {
        if (vipAddress == null) {
            throw new IllegalArgumentException(
                    "Supplied VIP Address cannot be null");
        }
        Applications applications;
        if (instanceRegionChecker.isLocalRegion(region)) {
            applications = this.localRegionApps.get();
        } else {
            applications = remoteRegionVsApps.get(region);
            if (null == applications) {
                logger.debug("No applications are defined for region {}, so returning an empty instance list for vip "
                        + "address {}.", region, vipAddress);
                return Collections.emptyList();
            }
        }

        if (!secure) {
            return applications.getInstancesByVirtualHostName(vipAddress);
        } else {
            return applications.getInstancesBySecureVirtualHostName(vipAddress);

        }

    }

    /**
     * Gets the list of instances matching the given VIP Address and the given
     * application name if both of them are not null. If one of them is null,
     * then that criterion is completely ignored for matching instances.
     *
     * @param vipAddress - The VIP address to match the instances for.
     * @param appName    - The applicationName to match the instances for.
     * @param secure     - true if it is a secure vip address, false otherwise.
     * @return - The list of {@link InstanceInfo} objects matching the criteria.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddressAndAppName(
            String vipAddress, String appName, boolean secure) {

        List<InstanceInfo> result = new ArrayList<InstanceInfo>();
        if (vipAddress == null && appName == null) {
            throw new IllegalArgumentException(
                    "Supplied VIP Address and application name cannot both be null");
        } else if (vipAddress != null && appName == null) {
            return getInstancesByVipAddress(vipAddress, secure);
        } else if (vipAddress == null && appName != null) {
            Application application = getApplication(appName);
            if (application != null) {
                result = application.getInstances();
            }
            return result;
        }

        String instanceVipAddress;
        for (Application app : getApplications().getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                if (secure) {
                    instanceVipAddress = instance.getSecureVipAddress();
                } else {
                    instanceVipAddress = instance.getVIPAddress();
                }
                if (instanceVipAddress == null) {
                    continue;
                }
                String[] instanceVipAddresses = instanceVipAddress
                        .split(COMMA_STRING);

                // If the VIP Address is delimited by a comma, then consider to
                // be a list of VIP Addresses.
                // Try to match at least one in the list, if it matches then
                // return the instance info for the same
                for (String vipAddressFromList : instanceVipAddresses) {
                    if (vipAddress.equalsIgnoreCase(vipAddressFromList.trim())
                            && appName.equalsIgnoreCase(instance.getAppName())) {
                        result.add(instance);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.netflix.discovery.shared.LookupService#getNextServerFromEureka(java
     * .lang.String, boolean)
     */
    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        List<InstanceInfo> instanceInfoList = this.getInstancesByVipAddress(
                virtualHostname, secure);
        if (instanceInfoList == null || instanceInfoList.isEmpty()) {
            throw new RuntimeException("No matches for the virtual host name :"
                    + virtualHostname);
        }
        Applications apps = this.localRegionApps.get();
        int index = (int) (apps.getNextIndex(virtualHostname,
                secure).incrementAndGet() % instanceInfoList.size());
        return instanceInfoList.get(index);
    }

    /**
     * Get all applications registered with a specific eureka service.
     *
     * @param serviceUrl - The string representation of the service url.
     * @return - The registry information containing all applications.
     */
    @Override
    public Applications getApplications(String serviceUrl) {
        try {
            EurekaHttpResponse<Applications> response = clientConfig.getRegistryRefreshSingleVipAddress() == null
                    ? eurekaTransport.queryClient.getApplications()
                    : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress());
            if (response.getStatusCode() == Status.OK.getStatusCode()) {
                logger.debug(PREFIX + "{} -  refresh status: {}", appPathIdentifier, response.getStatusCode());
                return response.getEntity();
            }
            logger.error(PREFIX + "{} - was unable to refresh its cache! status = {}", appPathIdentifier,
                    response.getStatusCode());
        } catch (Throwable th) {
            logger.error(PREFIX + "{} - was unable to refresh its cache! status = {}", appPathIdentifier,
                    th.getMessage(), th);
        }
        return null;
    }

    /**
     * rest注册请求发起
     * Register with the eureka service by making the appropriate REST call.
     */
    boolean register() throws Throwable {
        logger.info(PREFIX + "{}: registering service...", appPathIdentifier);
        EurekaHttpResponse<Void> httpResponse;
        try {
            httpResponse = eurekaTransport.registrationClient.register(instanceInfo);
        } catch (Exception e) {
            logger.warn(PREFIX + "{} - registration failed {}", appPathIdentifier, e.getMessage(), e);
            throw e;
        }
        if (logger.isInfoEnabled()) {
            logger.info(PREFIX + "{} - registration status: {}", appPathIdentifier, httpResponse.getStatusCode());
        }
        return httpResponse.getStatusCode() == Status.NO_CONTENT.getStatusCode();
    }

    /**
     * 向server发起续约请求
     *
     * @return 是否续约成功
     */
    boolean renew() {
        EurekaHttpResponse<InstanceInfo> httpResponse;
        try {
            httpResponse = eurekaTransport.registrationClient.sendHeartBeat(
                    instanceInfo.getAppName(),
                    instanceInfo.getId(),
                    instanceInfo,
                    null);
            logger.debug(PREFIX + "{} - Heartbeat status: {}", appPathIdentifier, httpResponse.getStatusCode());
            // 如果server返回未发现，重新执行注册
            if (httpResponse.getStatusCode() == Status.NOT_FOUND.getStatusCode()) {
                REREGISTER_COUNTER.increment();
                logger.info(PREFIX + "{} - Re-registering apps/{}", appPathIdentifier, instanceInfo.getAppName());
                long timestamp = instanceInfo.setIsDirtyWithTime();
                boolean success = register();
                if (success) {
                    instanceInfo.unsetIsDirty(timestamp);
                }
                return success;
            }
            return httpResponse.getStatusCode() == Status.OK.getStatusCode();
        } catch (Throwable e) {
            logger.error(PREFIX + "{} - was unable to send heartbeat!", appPathIdentifier, e);
            return false;
        }
    }

    /**
     * @param instanceZone   The zone in which the client resides
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise
     * @return The list of all eureka service urls for the eureka client to talk to
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     * <p>
     * Get the list of all eureka service urls from properties file for the eureka client to talk to.
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(clientConfig, instanceZone, preferSameZone);
    }

    /**
     * 关闭eureka的客户端，发送cancel请求给server
     * <p>
     * Shuts down Eureka Client. Also sends a deregistration request to the
     * eureka server.
     */
    @PreDestroy
    @Override
    public synchronized void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            return;
        }

        logger.info("Shutting down DiscoveryClient ...");

        // 移除状态改变监听器
        if (statusChangeListener != null && applicationInfoManager != null) {
            applicationInfoManager.unregisterStatusChangeListener(statusChangeListener.getId());
        }

        // 取消实例复制、心跳线程池、缓存刷新线程池、延迟任务控制、心跳任务、缓存任务
        this.cancelScheduledTasks();

        // If APPINFO was registered
        // 如果注册过，在关闭时设置DOWN状态，取消注册
        if (applicationInfoManager != null
                && clientConfig.shouldRegisterWithEureka()
                && clientConfig.shouldUnregisterOnShutdown()) {
            applicationInfoManager.setInstanceStatus(InstanceStatus.DOWN);
            this.unregister();
        }

        // 关闭网络传输
        if (eurekaTransport != null) {
            eurekaTransport.shutdown();
        }

        // 关闭心跳状态监控，拉取注册信息的监控
        heartbeatStalenessMonitor.shutdown();
        registryStalenessMonitor.shutdown();

        Monitors.unregisterObject(this);

        logger.info("Completed shut down of DiscoveryClient");
    }

    /**
     * 从server取消注册
     */
    void unregister() {
        if (eurekaTransport == null || eurekaTransport.registrationClient == null) {
            return;
        }

        try {
            logger.info("Unregistering ...");
            EurekaHttpResponse<Void> httpResponse =
                    eurekaTransport.registrationClient.cancel(instanceInfo.getAppName(), instanceInfo.getId());
            logger.info(PREFIX + "{} - deregister  status: {}", appPathIdentifier, httpResponse.getStatusCode());
        } catch (Exception e) {
            logger.error(PREFIX + "{} - de-registration failed{}", appPathIdentifier, e.getMessage(), e);
        }
    }

    /**
     * 拉取注册信息，可能是常量，也可能是增量
     * <p>
     * Fetches the registry information.
     *
     * <p>
     * This method tries to get only deltas after the first fetch unless there
     * is an issue in reconciling eureka server and client registry information.
     * </p>
     *
     * @param forceFullRegistryFetch Forces a full registry fetch. 是否强制全量拉取
     * @return true if the registry was fetched
     */
    private boolean fetchRegistry(boolean forceFullRegistryFetch) {
        Stopwatch tracer = FETCH_REGISTRY_TIMER.start();

        try {
            // If the delta is disabled or if it is the first time, get all
            // applications
            // 获取 本地缓存的注册的应用实例集合
            Applications applications = getApplications();

            /*
             * 全量增量获取条件判断
             */
            // 是否禁用了增量
            if (clientConfig.shouldDisableDelta()
                    // 只获得一个 vipAddress 对应的应用实例的注册信息
                    || (!Strings.isNullOrEmpty(clientConfig.getRegistryRefreshSingleVipAddress()))
                    // 是否强制全量获取
                    || forceFullRegistryFetch
                    // 无应用信息
                    || (applications == null)
                    // 无已注册的实例信息
                    || (applications.getRegisteredApplications().size() == 0)
                    // 客户端应用非最新库
                    || (applications.getVersion() == -1)) {
                logger.info("Disable delta property : {}", clientConfig.shouldDisableDelta());
                logger.info("Single vip registry refresh property : {}", clientConfig.getRegistryRefreshSingleVipAddress());
                logger.info("Force full registry fetch : {}", forceFullRegistryFetch);
                logger.info("Application is null : {}", (applications == null));
                logger.info("Registered Applications size is zero : {}", (applications.getRegisteredApplications().size() == 0));
                logger.info("Application version is -1: {}", (applications.getVersion() == -1));

                // 全量获取注册信息，并设置到本地缓存
                this.getAndStoreFullRegistry();
            } else {
                // 增量获取注册信息，并刷新本地缓存
                this.getAndUpdateDelta(applications);
            }
            // 设置全部应用信息的hashcode
            // 用于校验增量获取的注册信息和 Eureka-Server 全量的注册信息是否一致( 完整 )
            applications.setAppsHashCode(applications.getReconcileHashCode());

            // 打印 本地缓存的注册的应用实例数量
            logTotalInstances();
        } catch (Throwable e) {
            logger.error(PREFIX + "{} - was unable to refresh its cache! status = {}", appPathIdentifier,
                    e.getMessage(), e);
            return false;
        } finally {
            if (tracer != null) {
                tracer.stop();
            }
        }

        // Notify about cache refresh before updating the instance remote status
        // 在更新实例远程状态之前通知有关缓存刷新的信息
        // 触发 CacheRefreshedEvent 事件，事件监听器执行。目前 Eureka 未提供默认的该事件监听器
        onCacheRefreshed();

        // Update remote status based on refreshed data held in the cache
        // 更新本地缓存的当前应用实例在 Eureka-Server 的状态
        updateInstanceRemoteStatus();

        // registry was fetched successfully, so return true
        return true;
    }

    /**
     * 更新本地缓存的当前应用实例在 Eureka-Server 的状态
     */
    private synchronized void updateInstanceRemoteStatus() {
        // Determine this instance's status for this app and set to UNKNOWN if not found

        // 从注册信息中获取当前应用在 Eureka-Server 的状态
        InstanceInfo.InstanceStatus currentRemoteInstanceStatus = null;
        if (instanceInfo.getAppName() != null) {
            Application app = getApplication(instanceInfo.getAppName());
            if (app != null) {
                InstanceInfo remoteInstanceInfo = app.getByInstanceId(instanceInfo.getId());
                if (remoteInstanceInfo != null) {
                    currentRemoteInstanceStatus = remoteInstanceInfo.getStatus();
                }
            }
        }

        // 不存在设置为未知
        if (currentRemoteInstanceStatus == null) {
            currentRemoteInstanceStatus = InstanceInfo.InstanceStatus.UNKNOWN;
        }

        // Notify if status changed
        // 对比本地缓存 和 最新的的当前应用实例在 Eureka-Server 的状态
        // 若不同，更新本地缓存( 注意，只更新该缓存变量，不更新本地当前应用实例的状态( instanceInfo.status ) )
        // 触发 StatusChangeEvent 事件，事件监听器执行。
        // 目前 Eureka 未提供默认的该事件监听器。
        // eureka-Client 本地应用实例与 Eureka-Server 的该应用实例状态不同的原因，因为应用实例的覆盖状态
        if (lastRemoteInstanceStatus != currentRemoteInstanceStatus) {
            onRemoteStatusChanged(lastRemoteInstanceStatus, currentRemoteInstanceStatus);
            lastRemoteInstanceStatus = currentRemoteInstanceStatus;
        }
    }

    /**
     * @return Return he current instance status as seen on the Eureka server.
     */
    @Override
    public InstanceInfo.InstanceStatus getInstanceRemoteStatus() {
        return lastRemoteInstanceStatus;
    }

    /**
     * 获取hashcode
     *
     * @param applications 应用集合
     * @return hashcode
     */
    private String getReconcileHashCode(Applications applications) {
        TreeMap<String, AtomicInteger> instanceCountMap = new TreeMap<String, AtomicInteger>();
        if (isFetchingRemoteRegionRegistries()) {
            for (Applications remoteApp : remoteRegionVsApps.values()) {
                remoteApp.populateInstanceCountMap(instanceCountMap);
            }
        }
        applications.populateInstanceCountMap(instanceCountMap);
        return Applications.getReconcileHashCode(instanceCountMap);
    }

    /**
     * 全量获取并存储应用信息
     * <p>
     * Gets the full registry information from the eureka server and stores it locally.
     * When applying the full registry, the following flow is observed:
     * <p>
     * if (update generation have not advanced (due to another thread))
     * atomically set the registry to the new registry
     * fi
     *
     * @throws Throwable on error.
     */
    private void getAndStoreFullRegistry() throws Throwable {
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        logger.info("Getting all instance registry info from the eureka server");

        // 全量获取注册信息
        Applications apps = null;
        EurekaHttpResponse<Applications> httpResponse =
                clientConfig.getRegistryRefreshSingleVipAddress() == null
                        ? eurekaTransport.queryClient.getApplications(remoteRegionsRef.get())
                        : eurekaTransport.queryClient.getVip(
                                clientConfig.getRegistryRefreshSingleVipAddress(),
                                remoteRegionsRef.get());
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            apps = httpResponse.getEntity();
        }
        logger.info("The response status is {}", httpResponse.getStatusCode());

        // 设置到本地缓存
        if (apps == null) {
            logger.error("The application is null for some reason. Not storing this information");
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            // 拉取的次数加一，也就是这期间没有再触发过其他拉取，如果触发过，则本次拉取无效
            localRegionApps.set(this.filterAndShuffle(apps));
            logger.debug("Got full registry with apps hashcode {}", apps.getAppsHashCode());
        } else {
            logger.warn("Not updating applications as another thread is updating it already");
        }
    }

    /**
     * 增量获取注册信息，并刷新本地缓存
     * <p>
     * Get the delta registry information from the eureka server and update it locally.
     * When applying the delta, the following flow is observed:
     * <p>
     * if (update generation have not advanced (due to another thread))
     * atomically try to: update application with the delta and get reconcileHashCode
     * abort entire processing otherwise
     * do reconciliation if reconcileHashCode clash
     * fi
     *
     * @throws Throwable on error
     */
    private void getAndUpdateDelta(Applications applications) throws Throwable {
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        // 增量获取注册信息
        Applications delta = null;
        EurekaHttpResponse<Applications> httpResponse = eurekaTransport.queryClient.getDelta(remoteRegionsRef.get());
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            delta = httpResponse.getEntity();
        }

        if (delta == null) {
            logger.warn("The server does not allow the delta revision to be applied because it is not safe. "
                    + "Hence got the full registry.");
            // 增量获取为空，全量获取
            getAndStoreFullRegistry();
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            // 比较是否是最近的拉取，不是不进入此if
            logger.debug("Got delta update with apps hashcode {}", delta.getAppsHashCode());

            String reconcileHashCode = "";
            if (fetchRegistryUpdateLock.tryLock()) {
                try {
                    // 更新增量信息，将变化的和本地缓存的进行合并
                    this.updateDelta(delta);
                    // 获取hashcode
                    reconcileHashCode = this.getReconcileHashCode(applications);
                } finally {
                    fetchRegistryUpdateLock.unlock();
                }
            } else {
                logger.warn("Cannot acquire update lock, aborting getAndUpdateDelta");
            }
            // There is a diff in number of instances for some reason
            // 比较hashcode，如果不同，记录日志，全量拉取
            if (!reconcileHashCode.equals(delta.getAppsHashCode()) || clientConfig.shouldLogDeltaDiff()) {
                this.reconcileAndLogDifference(delta, reconcileHashCode);
            }
        } else {
            logger.warn("Not updating application delta as another thread is updating it already");
            logger.debug("Ignoring delta update with apps hashcode {}, as another thread is updating it already",
                    delta.getAppsHashCode());
        }
    }

    /**
     * Logs the total number of non-filtered instances stored locally.
     */
    private void logTotalInstances() {
        if (logger.isDebugEnabled()) {
            int totInstances = 0;
            for (Application application : getApplications().getRegisteredApplications()) {
                totInstances += application.getInstancesAsIsFromEureka().size();
            }
            logger.debug("The total number of all instances in the client now is {}", totInstances);
        }
    }

    /**
     * 协调server和client注册信息，log不同； 并发起一个远端请求重新全量拉取
     * Reconcile the eureka server and client registry information and logs the differences if any.
     * When reconciling, the following flow is observed:
     * <p>
     * make a remote call to the server for the full registry
     * calculate and log differences
     * if (update generation have not advanced (due to another thread))
     * atomically set the registry to the new registry
     * fi
     *
     * @param delta             the last delta registry information received from the eureka
     *                          server.
     * @param reconcileHashCode the hashcode generated by the server for reconciliation.
     * @throws Throwable on any error.
     */
    private void reconcileAndLogDifference(Applications delta, String reconcileHashCode) throws Throwable {
        logger.debug("The Reconcile hashcodes do not match, client : {}, server : {}. Getting the full registry",
                reconcileHashCode, delta.getAppsHashCode());

        RECONCILE_HASH_CODES_MISMATCH.increment();

        long currentUpdateGeneration = fetchRegistryGeneration.get();

        EurekaHttpResponse<Applications> httpResponse = clientConfig.getRegistryRefreshSingleVipAddress() == null
                ? eurekaTransport.queryClient.getApplications(remoteRegionsRef.get())
                : eurekaTransport.queryClient.getVip(
                        clientConfig.getRegistryRefreshSingleVipAddress(),
                        remoteRegionsRef.get());
        Applications serverApps = httpResponse.getEntity();
        if (serverApps == null) {
            logger.warn("Cannot fetch full registry from the server; reconciliation failure");
            return;
        }

        if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            localRegionApps.set(this.filterAndShuffle(serverApps));
            getApplications().setVersion(delta.getVersion());
            logger.debug(
                    "The Reconcile hashcodes after complete sync up, client : {}, server : {}.",
                    getApplications().getReconcileHashCode(),
                    delta.getAppsHashCode());
        } else {
            logger.warn("Not setting the applications map as another thread has advanced the update generation");
        }
    }

    /**
     * 更新增量信息，将变化的和本地缓存的进行合并
     * <p>
     * Updates the delta information fetches from the eureka server into the
     * local cache.
     *
     * @param delta the delta information received from eureka server in the last
     *              poll cycle.
     */
    private void updateDelta(Applications delta) {
        int deltaCount = 0;
        // 循环变化的集合 和 实例信息
        for (Application app : delta.getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                Applications applications = getApplications();
                String instanceRegion = instanceRegionChecker.getInstanceRegion(instance);
                if (!instanceRegionChecker.isLocalRegion(instanceRegion)) {
                    Applications remoteApps = remoteRegionVsApps.get(instanceRegion);
                    if (null == remoteApps) {
                        remoteApps = new Applications();
                        remoteRegionVsApps.put(instanceRegion, remoteApps);
                    }
                    applications = remoteApps;
                }

                ++deltaCount;
                if (ActionType.ADDED.equals(instance.getActionType())) {
                    // 添加
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Added instance {} to the existing apps in region {}", instance.getId(),
                            instanceRegion);
                    // 添加实例
                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);
                } else if (ActionType.MODIFIED.equals(instance.getActionType())) {
                    // 修改
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Modified instance {} to the existing apps ", instance.getId());
                    // 添加实例
                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);
                } else if (ActionType.DELETED.equals(instance.getActionType())) {
                    // 删除实例
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp != null) {
                        logger.debug("Deleted instance {} to the existing apps ", instance.getId());
                        existingApp.removeInstance(instance);
                        /*
                         * We find all instance list from application(The status of instance status is not only the
                         * status is UP but also other status)
                         * if instance list is empty, we remove the application.
                         *
                         * 我们从应用程序中找到所有实例列表（实例状态的状态不仅是状态为UP，而且还有其他状态）
                         * 如果实例列表为空，则删除该应用程序
                         */
                        if (existingApp.getInstancesAsIsFromEureka().isEmpty()) {
                            applications.removeApplication(existingApp);
                        }
                    }
                }
            }
        }
        logger.debug("The total number of instances fetched by the delta processor : {}", deltaCount);

        // 打乱应用集合
        getApplications().setVersion(delta.getVersion());
        getApplications().shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());

        for (Applications applications : remoteRegionVsApps.values()) {
            applications.setVersion(delta.getVersion());
            applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
        }
    }

    /**
     * 初始化心跳，拉取的定时任务
     * Initializes all scheduled tasks.
     */
    private void initScheduledTasks() {
        if (clientConfig.shouldFetchRegistry()) {
            // registry cache refresh timer
            // 拉取周期
            int registryFetchIntervalSeconds = clientConfig.getRegistryFetchIntervalSeconds();
            // 拉取超时后的延迟重试的时间
            int expBackOffBound = clientConfig.getCacheRefreshExecutorExponentialBackOffBound();
            cacheRefreshTask = new TimedSupervisorTask(
                    "cacheRefresh",
                    scheduler,
                    cacheRefreshExecutor,
                    registryFetchIntervalSeconds,
                    TimeUnit.SECONDS,
                    expBackOffBound,
                    this::refreshRegistry
            );
            // 执行延迟拉取任务
            scheduler.schedule(
                    cacheRefreshTask,
                    registryFetchIntervalSeconds, TimeUnit.SECONDS);
        }

        if (clientConfig.shouldRegisterWithEureka()) {
            // 心跳周期
            int renewalIntervalInSecs = instanceInfo.getLeaseInfo().getRenewalIntervalInSecs();
            // 拉取超时后的延迟重试的时间 * 次数
            int expBackOffBound = clientConfig.getHeartbeatExecutorExponentialBackOffBound();
            logger.info("Starting heartbeat executor: " + "renew interval is: {}", renewalIntervalInSecs);

            // Heartbeat timer
            heartbeatTask = new TimedSupervisorTask(
                    "heartbeat",
                    scheduler,
                    heartbeatExecutor,
                    renewalIntervalInSecs,
                    TimeUnit.SECONDS,
                    expBackOffBound,
                    () -> {
                        // 续约成功，更新 成功心跳的时间戳
                        if (this.renew()) {
                            lastSuccessfulHeartbeatTimestamp = System.currentTimeMillis();
                        }
                    }
            );
            // 执行延迟心跳任务
            scheduler.schedule(
                    heartbeatTask,
                    renewalIntervalInSecs, TimeUnit.SECONDS);

            // InstanceInfo replicator

            // 复制和更新实例信息到eureka，设置定时任务
            instanceInfoReplicator = new InstanceInfoReplicator(
                    this,
                    instanceInfo,
                    clientConfig.getInstanceInfoReplicationIntervalSeconds(),
                    2); // burstSize

            // 状态改变监听器
            statusChangeListener = new ApplicationInfoManager.StatusChangeListener() {
                @Override
                public String getId() {
                    return "statusChangeListener";
                }

                @Override
                public void notify(StatusChangeEvent statusChangeEvent) {
                    if (InstanceStatus.DOWN == statusChangeEvent.getStatus() ||
                            InstanceStatus.DOWN == statusChangeEvent.getPreviousStatus()) {
                        // log at warn level if DOWN was involved
                        logger.warn("Saw local status change event {}", statusChangeEvent);
                    } else {
                        logger.info("Saw local status change event {}", statusChangeEvent);
                    }
                    // 收到改变通知，将状态汇报给 server
                    instanceInfoReplicator.onDemandUpdate();
                }
            };

            // 注册状态改变监听器
            if (clientConfig.shouldOnDemandUpdateStatusChange()) {
                applicationInfoManager.registerStatusChangeListener(statusChangeListener);
            }

            // 开始第一次的同步复制
            instanceInfoReplicator.start(clientConfig.getInitialInstanceInfoReplicationIntervalSeconds());
        } else {
            logger.info("Not registering with Eureka server per configuration");
        }
    }

    /**
     * 取消实例复制、心跳线程池、缓存刷新线程池、延迟任务控制、心跳任务、缓存任务
     */
    private void cancelScheduledTasks() {
        if (instanceInfoReplicator != null) {
            instanceInfoReplicator.stop();
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (cacheRefreshExecutor != null) {
            cacheRefreshExecutor.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (cacheRefreshTask != null) {
            cacheRefreshTask.cancel();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
    }

    /**
     * @param instanceZone   The zone in which the client resides.
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise.
     * @return The list of all eureka service urls for the eureka client to talk to.
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     * <p>
     * Get the list of all eureka service urls from DNS for the eureka client to
     * talk to. The client picks up the service url from its zone and then fails over to
     * other zones randomly. If there are multiple servers in the same zone, the client once
     * again picks one randomly. This way the traffic will be distributed in the case of failures.
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromDNS(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromDNS(clientConfig, instanceZone, preferSameZone, urlRandomizer);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     */
    @Deprecated
    @Override
    public List<String> getDiscoveryServiceUrls(String zone) {
        return EndpointUtils.getDiscoveryServiceUrls(clientConfig, zone, urlRandomizer);
    }

    /**
     * @param dnsName The dns name of the zone-specific CNAME
     * @param type    CNAME or EIP that needs to be retrieved
     * @return The list of EC2 URLs associated with the dns name
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     * <p>
     * Get the list of EC2 URLs given the zone name.
     */
    @Deprecated
    public static Set<String> getEC2DiscoveryUrlsFromZone(String dnsName,
                                                          EndpointUtils.DiscoveryUrlType type) {
        return EndpointUtils.getEC2DiscoveryUrlsFromZone(dnsName, type);
    }

    /**
     * 刷新当前的实例信息，如果状态改变了，设置新的状态，并设置isDirty为true，并通知监听器
     * Refresh the current local instanceInfo. Note that after a valid refresh where changes are observed, the
     * isDirty flag on the instanceInfo is set to true
     */
    void refreshInstanceInfo() {
        applicationInfoManager.refreshDataCenterInfoIfRequired();
        applicationInfoManager.refreshLeaseInfoIfRequired();

        // 检查健康状态，发生异常则下线
        InstanceStatus status;
        try {
            status = getHealthCheckHandler().getStatus(instanceInfo.getStatus());
        } catch (Exception e) {
            logger.warn("Exception from healthcheckHandler.getStatus, setting status to DOWN", e);
            status = InstanceStatus.DOWN;
        }

        if (null != status) {
            applicationInfoManager.setInstanceStatus(status);
        }
    }

    @VisibleForTesting
    InstanceInfoReplicator getInstanceInfoReplicator() {
        return instanceInfoReplicator;
    }

    @VisibleForTesting
    InstanceInfo getInstanceInfo() {
        return instanceInfo;
    }

    @Override
    public HealthCheckHandler getHealthCheckHandler() {
        HealthCheckHandler healthCheckHandler = this.healthCheckHandlerRef.get();
        if (healthCheckHandler == null) {
            // 获取健康检查处理器
            if (null != healthCheckHandlerProvider) {
                healthCheckHandler = healthCheckHandlerProvider.get();
            } else if (null != healthCheckCallbackProvider) {
                healthCheckHandler = new HealthCheckCallbackToHandlerBridge(healthCheckCallbackProvider.get());
            }

            // 为空创建新的
            if (null == healthCheckHandler) {
                healthCheckHandler = new HealthCheckCallbackToHandlerBridge(null);
            }
            // 没有则设置
            this.healthCheckHandlerRef.compareAndSet(null, healthCheckHandler);
        }

        return this.healthCheckHandlerRef.get();
    }

    /**
     * 刷新注册信息，全量或增量
     */
    @VisibleForTesting
    void refreshRegistry() {
        try {
            boolean isFetchingRemoteRegionRegistries = isFetchingRemoteRegionRegistries();

            boolean remoteRegionsModified = false;
            // This makes sure that a dynamic change to remote regions to fetch is honored.
            // 可以确保对远程区域进行动态更改以获取数据
            String latestRemoteRegions = clientConfig.fetchRegistryForRemoteRegions();
            if (null != latestRemoteRegions) {
                String currentRemoteRegions = remoteRegionsToFetch.get();
                if (!latestRemoteRegions.equals(currentRemoteRegions)) {
                    // Both remoteRegionsToFetch and AzToRegionMapper.regionsToFetch need to be in sync
                    // remoteRegionsToFetch和AzToRegionMapper.regionsToFetch都需要同步
                    synchronized (instanceRegionChecker.getAzToRegionMapper()) {
                        if (remoteRegionsToFetch.compareAndSet(currentRemoteRegions, latestRemoteRegions)) {
                            String[] remoteRegions = latestRemoteRegions.split(",");
                            remoteRegionsRef.set(remoteRegions);
                            instanceRegionChecker.getAzToRegionMapper().setRegionsToFetch(remoteRegions);
                            remoteRegionsModified = true;
                        } else {
                            logger.info("Remote regions to fetch modified concurrently," +
                                    " ignoring change from {} to {}", currentRemoteRegions, latestRemoteRegions);
                        }
                    }
                } else {
                    // Just refresh mapping to reflect any DNS/Property change
                    instanceRegionChecker.getAzToRegionMapper().refreshMapping();
                }
            }

            // 拉取注册信息
            boolean success = this.fetchRegistry(remoteRegionsModified);
            if (success) {
                // 设置 注册信息的应用实例数
                registrySize = localRegionApps.get().size();
                // 设置 最后获取注册信息时间
                lastSuccessfulRegistryFetchTimestamp = System.currentTimeMillis();
            }

            // 打印日志
            if (logger.isDebugEnabled()) {
                StringBuilder allAppsHashCodes = new StringBuilder();
                allAppsHashCodes.append("Local region apps hashcode: ");
                allAppsHashCodes.append(localRegionApps.get().getAppsHashCode());
                allAppsHashCodes.append(", is fetching remote regions? ");
                allAppsHashCodes.append(isFetchingRemoteRegionRegistries);
                for (Map.Entry<String, Applications> entry : remoteRegionVsApps.entrySet()) {
                    allAppsHashCodes.append(", Remote region: ");
                    allAppsHashCodes.append(entry.getKey());
                    allAppsHashCodes.append(" , apps hashcode: ");
                    allAppsHashCodes.append(entry.getValue().getAppsHashCode());
                }
                logger.debug("Completed cache refresh task for discovery. All Apps hash code is {} ",
                        allAppsHashCodes);
            }
        } catch (Throwable e) {
            logger.error("Cannot fetch registry from server", e);
        }
    }

    /**
     * 从备份注册中心拉取注册信息
     * Fetch the registry information from back up registry if all eureka server
     * urls are unreachable.
     */
    private void fetchRegistryFromBackup() {
        try {
            @SuppressWarnings("deprecation")
            BackupRegistry backupRegistryInstance = newBackupRegistryInstance();
            if (null == backupRegistryInstance) { // backward compatibility with the old protected method, in case it
                // is being used.
                backupRegistryInstance = backupRegistryProvider.get();
            }

            if (null != backupRegistryInstance) {
                Applications apps = null;
                if (isFetchingRemoteRegionRegistries()) {
                    // 获取区域注册中心地址，并拉取
                    String remoteRegionsStr = remoteRegionsToFetch.get();
                    if (null != remoteRegionsStr) {
                        apps = backupRegistryInstance.fetchRegistry(remoteRegionsStr.split(","));
                    }
                } else {
                    apps = backupRegistryInstance.fetchRegistry();
                }
                if (apps != null) {
                    //拉取成功后本地缓存
                    final Applications applications = this.filterAndShuffle(apps);
                    applications.setAppsHashCode(applications.getReconcileHashCode());
                    localRegionApps.set(applications);
                    logTotalInstances();
                    logger.info("Fetched registry successfully from the backup");
                }
            } else {
                logger.warn("No backup registry instance defined & unable to find any discovery servers.");
            }
        } catch (Throwable e) {
            logger.warn("Cannot fetch applications from apps although backup registry was specified", e);
        }
    }

    /**
     * @deprecated Use injection to provide {@link BackupRegistry} implementation.
     */
    @Deprecated
    @Nullable
    protected BackupRegistry newBackupRegistryInstance()
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return null;
    }

    /**
     * 过滤只保留状态为开启( UP )的应用实例，并随机打乱应用实例顺序
     * <p>
     * Gets the <em>applications</em> after filtering the applications for
     * instances with only UP states and shuffling them.
     *
     * <p>
     * The filtering depends on the option specified by the configuration
     * {@link EurekaClientConfig#shouldFilterOnlyUpInstances()}. Shuffling helps
     * in randomizing the applications list there by avoiding the same instances
     * receiving traffic during start ups.
     * </p>
     *
     * @param apps The applications that needs to be filtered and shuffled.
     * @return The applications after the filter and the shuffle.
     */
    private Applications filterAndShuffle(Applications apps) {
        if (apps != null) {
            if (isFetchingRemoteRegionRegistries()) {
                // 如果本地存在远端注册信息，则进行过滤，只保留UP，并打乱应用实例顺序
                Map<String, Applications> remoteRegionVsApps = new ConcurrentHashMap<String, Applications>();
                apps.shuffleAndIndexInstances(remoteRegionVsApps, clientConfig, instanceRegionChecker);
                for (Applications applications : remoteRegionVsApps.values()) {
                    applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
                }
                this.remoteRegionVsApps = remoteRegionVsApps;
            } else {
                // 是否过滤实例，只保留UP，并打乱顺序
                apps.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
            }
        }
        return apps;
    }

    private boolean isFetchingRemoteRegionRegistries() {
        return null != remoteRegionsToFetch.get();
    }

    /**
     * 远端状态改变事件
     * Invoked when the remote status of this client has changed.
     * Subclasses may override this method to implement custom behavior if needed.
     *
     * @param oldStatus the previous remote {@link InstanceStatus}
     * @param newStatus the new remote {@link InstanceStatus}
     */
    protected void onRemoteStatusChanged(InstanceInfo.InstanceStatus oldStatus, InstanceInfo.InstanceStatus newStatus) {
        fireEvent(new StatusChangeEvent(oldStatus, newStatus));
    }

    /**
     * Invoked every time the local registry cache is refreshed (whether changes have
     * been detected or not).
     * <p>
     * Subclasses may override this method to implement custom behavior if needed.
     */
    protected void onCacheRefreshed() {
        fireEvent(new CacheRefreshedEvent());
    }

    /**
     * 发送给定的事件到所有事件监听器中
     * Send the given event on the EventBus if one is available
     *
     * @param event the event to send on the eventBus
     */
    protected void fireEvent(final EurekaEvent event) {
        for (EurekaEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.info("Event {} throw an exception for listener {}", event, listener, e.getMessage());
            }
        }
    }

    /**
     * @param myInfo - The InstanceInfo object of the instance.
     * @return - The zone in which the particular instance belongs to.
     * @deprecated see {@link com.netflix.appinfo.InstanceInfo#getZone(String[], com.netflix.appinfo.InstanceInfo)}
     * <p>
     * Get the zone that a particular instance is in.
     */
    @Deprecated
    public static String getZone(InstanceInfo myInfo) {
        String[] availZones = staticClientConfig.getAvailabilityZones(staticClientConfig.getRegion());
        return InstanceInfo.getZone(availZones, myInfo);
    }

    /**
     * @return - The region in which the particular instance belongs to.
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     * <p>
     * Get the region that this particular instance is in.
     */
    @Deprecated
    public static String getRegion() {
        String region = staticClientConfig.getRegion();
        if (region == null) {
            region = "default";
        }
        region = region.trim().toLowerCase();
        return region;
    }

    /**
     * @deprecated use {@link #getServiceUrlsFromConfig(String, boolean)} instead.
     */
    @Deprecated
    public static List<String> getEurekaServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(staticClientConfig, instanceZone, preferSameZone);
    }

    public long getLastSuccessfulHeartbeatTimePeriod() {
        return lastSuccessfulHeartbeatTimestamp < 0
                ? lastSuccessfulHeartbeatTimestamp
                : System.currentTimeMillis() - lastSuccessfulHeartbeatTimestamp;
    }

    public long getLastSuccessfulRegistryFetchTimePeriod() {
        return lastSuccessfulRegistryFetchTimestamp < 0
                ? lastSuccessfulRegistryFetchTimestamp
                : System.currentTimeMillis() - lastSuccessfulRegistryFetchTimestamp;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRATION_PREFIX + "lastSuccessfulHeartbeatTimePeriod",
            description = "How much time has passed from last successful heartbeat", type = DataSourceType.GAUGE)
    private long getLastSuccessfulHeartbeatTimePeriodInternal() {
        final long delay = (!clientConfig.shouldRegisterWithEureka() || isShutdown.get())
                ? 0
                : getLastSuccessfulHeartbeatTimePeriod();

        heartbeatStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    // for metrics only
    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "lastSuccessfulRegistryFetchTimePeriod",
            description = "How much time has passed from last successful local registry update", type =
            DataSourceType.GAUGE)
    private long getLastSuccessfulRegistryFetchTimePeriodInternal() {
        final long delay = (!clientConfig.shouldFetchRegistry() || isShutdown.get())
                ? 0
                : getLastSuccessfulRegistryFetchTimePeriod();

        registryStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "localRegistrySize",
            description = "Count of instances in the local registry", type = DataSourceType.GAUGE)
    public int localRegistrySize() {
        return registrySize;
    }

    private long computeStalenessMonitorDelay(long delay) {
        if (delay < 0) {
            return System.currentTimeMillis() - initTimestampMs;
        } else {
            return delay;
        }
    }

    /**
     * Gets stats for the DiscoveryClient.
     *
     * @return The DiscoveryClientStats instance.
     */
    public Stats getStats() {
        return stats;
    }

    /**
     * Stats is used to track useful attributes of the DiscoveryClient. It includes helpers that can aid
     * debugging and log analysis.
     */
    public class Stats {

        private Stats() {
        }

        public int initLocalRegistrySize() {
            return initRegistrySize;
        }

        public long initTimestampMs() {
            return initTimestampMs;
        }

        public int localRegistrySize() {
            return registrySize;
        }

        public long lastSuccessfulRegistryFetchTimestampMs() {
            return lastSuccessfulRegistryFetchTimestamp;
        }

        public long lastSuccessfulHeartbeatTimestampMs() {
            return lastSuccessfulHeartbeatTimestamp;
        }

        /**
         * Used to determine if the Discovery client's first attempt to fetch from the service registry succeeded with
         * non-empty results.
         *
         * @return true if succeeded, failed otherwise
         */
        public boolean initSucceeded() {
            return initLocalRegistrySize() > 0 && initTimestampMs() > 0;
        }

    }

}
