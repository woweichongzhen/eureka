package com.netflix.discovery;

import com.google.inject.ImplementedBy;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.LookupService;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * 简单的接口，用于服务发现client
 * 提供多种方法获取应用集合和应用实例信息集合
 * 提供方法获取本地客户端信息，例如，应用管理器 ApplicationInfoManager 和 Eureka-Client 配置 EurekaClientConfig
 * 提供方法注册本地客户端的健康检查和 Eureka 事件监听器
 * <p>
 * Define a simple interface over the current DiscoveryClient implementation.
 * <p>
 * This interface does NOT try to clean up the current client interface for eureka 1.x. Rather it tries
 * to provide an easier transition path from eureka 1.x to eureka 2.x.
 * <p>
 * EurekaClient API contracts are:
 * - provide the ability to get InstanceInfo(s) (in various different ways)
 * - provide the ability to get data about the local Client (known regions, own AZ etc)
 * - provide the ability to register and access the healthcheck handler for the client
 *
 * @author David Liu
 */
@ImplementedBy(DiscoveryClient.class)
public interface EurekaClient extends LookupService {

    // ========================
    // getters for InstanceInfo
    // ========================

    /**
     * @param region the region that the Applications reside in
     * @return an {@link com.netflix.discovery.shared.Applications} for the matching region. a Null value
     * is treated as the local region.
     */
    Applications getApplicationsForARegion(@Nullable String region);

    /**
     * Get all applications registered with a specific eureka service.
     *
     * @param serviceUrl The string representation of the service url.
     * @return The registry information containing all applications.
     */
    Applications getApplications(String serviceUrl);

    /**
     * Gets the list of instances matching the given VIP Address.
     *
     * @param vipAddress The VIP address to match the instances for.
     * @param secure     true if it is a secure vip address, false otherwise
     * @return - The list of {@link InstanceInfo} objects matching the criteria
     */
    List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure);

    /**
     * Gets the list of instances matching the given VIP Address in the passed region.
     *
     * @param vipAddress The VIP address to match the instances for.
     * @param secure     true if it is a secure vip address, false otherwise
     * @param region     region from which the instances are to be fetched. If <code>null</code> then local region is
     *                   assumed.
     * @return - The list of {@link InstanceInfo} objects matching the criteria, empty list if not instances found.
     */
    List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure, @Nullable String region);

    /**
     * Gets the list of instances matching the given VIP Address and the given
     * application name if both of them are not null. If one of them is null,
     * then that criterion is completely ignored for matching instances.
     *
     * @param vipAddress The VIP address to match the instances for.
     * @param appName    The applicationName to match the instances for.
     * @param secure     true if it is a secure vip address, false otherwise.
     * @return - The list of {@link InstanceInfo} objects matching the criteria.
     */
    List<InstanceInfo> getInstancesByVipAddressAndAppName(String vipAddress, String appName, boolean secure);

    // ==========================
    // getters for local metadata
    // ==========================

    /**
     * @return in String form all regions (local + remote) that can be accessed by this client
     */
    Set<String> getAllKnownRegions();

    /**
     * @return the current self instance status as seen on the Eureka server.
     */
    InstanceInfo.InstanceStatus getInstanceRemoteStatus();

    /**
     * @param zone the zone in which the client resides
     * @return The list of all eureka service urls for the eureka client to talk to.
     * @deprecated see {@link com.netflix.discovery.endpoint.EndpointUtils} for replacement
     * <p>
     * Get the list of all eureka service urls for the eureka client to talk to.
     */
    @Deprecated
    List<String> getDiscoveryServiceUrls(String zone);

    /**
     * @param instanceZone   The zone in which the client resides
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise
     * @return The list of all eureka service urls for the eureka client to talk to
     * @deprecated see {@link com.netflix.discovery.endpoint.EndpointUtils} for replacement
     * <p>
     * Get the list of all eureka service urls from properties file for the eureka client to talk to.
     */
    @Deprecated
    List<String> getServiceUrlsFromConfig(String instanceZone, boolean preferSameZone);

    /**
     * @param instanceZone   The zone in which the client resides.
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise.
     * @return The list of all eureka service urls for the eureka client to talk to.
     * @deprecated see {@link com.netflix.discovery.endpoint.EndpointUtils} for replacement
     * <p>
     * Get the list of all eureka service urls from DNS for the eureka client to
     * talk to. The client picks up the service url from its zone and then fails over to
     * other zones randomly. If there are multiple servers in the same zone, the client once
     * again picks one randomly. This way the traffic will be distributed in the case of failures.
     */
    @Deprecated
    List<String> getServiceUrlsFromDNS(String instanceZone, boolean preferSameZone);

    // ===========================
    // healthcheck related methods
    // ===========================

    /**
     * @param callback app specific healthcheck.
     * @deprecated Use {@link #registerHealthCheck(com.netflix.appinfo.HealthCheckHandler)} instead.
     * <p>
     * Register {@link HealthCheckCallback} with the eureka client.
     * <p>
     * Once registered, the eureka client will invoke the
     * {@link HealthCheckCallback} in intervals specified by
     * {@link EurekaClientConfig#getInstanceInfoReplicationIntervalSeconds()}.
     */
    @Deprecated
    void registerHealthCheckCallback(HealthCheckCallback callback);

    /**
     * Register {@link HealthCheckHandler} with the eureka client.
     * <p>
     * Once registered, the eureka client will first make an onDemand update of the
     * registering instanceInfo by calling the newly registered healthcheck handler,
     * and subsequently invoke the {@link HealthCheckHandler} in intervals specified
     * by {@link EurekaClientConfig#getInstanceInfoReplicationIntervalSeconds()}.
     *
     * @param healthCheckHandler app specific healthcheck handler.
     */
    void registerHealthCheck(HealthCheckHandler healthCheckHandler);

    /**
     * Register {@link EurekaEventListener} with the eureka client.
     * <p>
     * Once registered, the eureka client will invoke {@link EurekaEventListener#onEvent}
     * whenever there is a change in eureka client's internal state.  Use this instead of
     * polling the client for changes.
     * <p>
     * {@link EurekaEventListener#onEvent} is called from the context of an internal thread
     * and must therefore return as quickly as possible without blocking.
     *
     * @param eventListener
     */
    void registerEventListener(EurekaEventListener eventListener);

    /**
     * Unregister a {@link EurekaEventListener} previous registered with {@link EurekaClient#registerEventListener}
     * or injected into the constructor of {@link DiscoveryClient}
     *
     * @param eventListener
     * @return True if removed otherwise false if the listener was never registered.
     */
    boolean unregisterEventListener(EurekaEventListener eventListener);

    /**
     * 获取当前注册的监看检查处理器
     *
     * @return the current registered healthcheck handler
     */
    HealthCheckHandler getHealthCheckHandler();

    // =============
    // other methods
    // =============

    /**
     * Shuts down Eureka Client. Also sends a deregistration request to the eureka server.
     */
    void shutdown();

    /**
     * @return the configuration of this eureka client
     */
    EurekaClientConfig getEurekaClientConfig();

    /**
     * @return the application info manager of this eureka client
     */
    ApplicationInfoManager getApplicationInfoManager();
}
