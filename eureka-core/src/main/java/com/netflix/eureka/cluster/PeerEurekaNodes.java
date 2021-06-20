package com.netflix.eureka.cluster;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.transport.JerseyReplicationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 管理eureka节点生命周期
 * <p>
 * Helper class to manage lifecycle of a collection of {@link PeerEurekaNode}s.
 *
 * @author Tomasz Bak
 */
@Singleton
public class PeerEurekaNodes {

    private static final Logger logger = LoggerFactory.getLogger(PeerEurekaNodes.class);

    /**
     * 注册表
     */
    protected final PeerAwareInstanceRegistry registry;

    /**
     * Server 配置
     */
    protected final EurekaServerConfig serverConfig;

    /**
     * client配置
     */
    protected final EurekaClientConfig clientConfig;

    /**
     * 服务编解码
     */
    protected final ServerCodecs serverCodecs;

    /**
     * 应用信息管理
     */
    private final ApplicationInfoManager applicationInfoManager;

    /**
     * Eureka-Server 集群节点数组
     */
    private volatile List<PeerEurekaNode> peerEurekaNodes = Collections.emptyList();

    /**
     * Eureka-Server 服务地址数组
     */
    private volatile Set<String> peerEurekaNodeUrls = Collections.emptySet();

    /**
     * 定时同步线程池
     */
    private ScheduledExecutorService taskExecutor;

    @Inject
    public PeerEurekaNodes(
            PeerAwareInstanceRegistry registry,
            EurekaServerConfig serverConfig,
            EurekaClientConfig clientConfig,
            ServerCodecs serverCodecs,
            ApplicationInfoManager applicationInfoManager) {
        this.registry = registry;
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.applicationInfoManager = applicationInfoManager;
    }

    public List<PeerEurekaNode> getPeerNodesView() {
        return Collections.unmodifiableList(peerEurekaNodes);
    }

    public List<PeerEurekaNode> getPeerEurekaNodes() {
        return peerEurekaNodes;
    }

    public int getMinNumberOfAvailablePeers() {
        return serverConfig.getHealthStatusMinNumberOfAvailablePeers();
    }

    /**
     * 开启定时任务同步复制
     */
    public void start() {
        // 创建 定时任务服务
        taskExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread thread = new Thread(r, "Eureka-PeerNodesUpdater");
                    thread.setDaemon(true);
                    return thread;
                }
        );

        try {
            // 初始化 集群节点信息
            this.updatePeerEurekaNodes(resolvePeerUrls());

            // 初始化固定周期更新集群节点信息的任务，10分钟更新一次
            Runnable peersUpdateTask = () -> {
                try {
                    updatePeerEurekaNodes(resolvePeerUrls());
                } catch (Throwable e) {
                    logger.error("Cannot update the replica Nodes", e);
                }

            };
            taskExecutor.scheduleWithFixedDelay(
                    peersUpdateTask,
                    serverConfig.getPeerEurekaNodesUpdateIntervalMs(),
                    serverConfig.getPeerEurekaNodesUpdateIntervalMs(),
                    TimeUnit.MILLISECONDS
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (PeerEurekaNode node : peerEurekaNodes) {
            logger.info("Replica node URL:  {}", node.getServiceUrl());
        }
    }

    public void shutdown() {
        taskExecutor.shutdown();
        List<PeerEurekaNode> toRemove = this.peerEurekaNodes;

        this.peerEurekaNodes = Collections.emptyList();
        this.peerEurekaNodeUrls = Collections.emptySet();

        for (PeerEurekaNode node : toRemove) {
            node.shutDown();
        }
    }

    /**
     * 获取集群同步url，并解析
     *
     * @return peer URLs with node's own URL filtered out
     */
    protected List<String> resolvePeerUrls() {
        InstanceInfo myInfo = applicationInfoManager.getInfo();
        // 获取可用区域的配置
        String zone = InstanceInfo.getZone(clientConfig.getAvailabilityZones(clientConfig.getRegion()), myInfo);
        // 获取server的url
        List<String> replicaUrls = EndpointUtils
                .getDiscoveryServiceUrls(clientConfig, zone, new EndpointUtils.InstanceInfoBasedUrlRandomizer(myInfo));

        // 从url移除本服务的url
        int idx = 0;
        while (idx < replicaUrls.size()) {
            if (isThisMyUrl(replicaUrls.get(idx))) {
                replicaUrls.remove(idx);
            } else {
                idx++;
            }
        }
        return replicaUrls;
    }

    /**
     * 给定新的副本URL集，请销毁不再可用的{@link PeerEurekaNode}，并创建新的URL
     *
     * Given new set of replica URLs, destroy {@link PeerEurekaNode}s no longer available, and
     * create new ones.
     *
     * @param newPeerUrls peer node URLs; this collection should have local node's URL filtered out
     */
    protected void updatePeerEurekaNodes(List<String> newPeerUrls) {
        if (newPeerUrls.isEmpty()) {
            logger.warn("The replica size seems to be empty. Check the route 53 DNS Registry");
            return;
        }

        // 计算 要关闭的集群节点地址
        Set<String> toShutdown = new HashSet<>(peerEurekaNodeUrls);
        toShutdown.removeAll(newPeerUrls);

        // 计算 要新增的的集群节点地址
        Set<String> toAdd = new HashSet<>(newPeerUrls);
        toAdd.removeAll(peerEurekaNodeUrls);

        // 没有改变
        if (toShutdown.isEmpty() && toAdd.isEmpty()) { // No change
            return;
        }

        // Remove peers no long available
        List<PeerEurekaNode> newNodeList = new ArrayList<>(peerEurekaNodes);

        // 关闭删除的集群节点
        if (!toShutdown.isEmpty()) {
            logger.info("Removing no longer available peer nodes {}", toShutdown);
            int i = 0;
            while (i < newNodeList.size()) {
                PeerEurekaNode eurekaNode = newNodeList.get(i);
                if (toShutdown.contains(eurekaNode.getServiceUrl())) {
                    newNodeList.remove(i);
                    eurekaNode.shutDown();
                } else {
                    i++;
                }
            }
        }

        // Add new peers
        // 添加新增的集群节点
        if (!toAdd.isEmpty()) {
            logger.info("Adding new peer nodes {}", toAdd);
            for (String peerUrl : toAdd) {
                newNodeList.add(this.createPeerEurekaNode(peerUrl));
            }
        }

        this.peerEurekaNodes = newNodeList;
        this.peerEurekaNodeUrls = new HashSet<>(newPeerUrls);
    }

    protected PeerEurekaNode createPeerEurekaNode(String peerEurekaNodeUrl) {
        HttpReplicationClient replicationClient = JerseyReplicationClient.createReplicationClient(serverConfig,
                serverCodecs, peerEurekaNodeUrl);
        String targetHost = hostFromUrl(peerEurekaNodeUrl);
        if (targetHost == null) {
            targetHost = "host";
        }
        return new PeerEurekaNode(registry, targetHost, peerEurekaNodeUrl, replicationClient, serverConfig);
    }

    /**
     * @param url the service url of the replica node that the check is made.
     * @return true, if the url represents the current node which is trying to
     * replicate, false otherwise.
     * @deprecated 2016-06-27 use instance version of {@link #isThisMyUrl(String)}
     * <p>
     * Checks if the given service url contains the current host which is trying
     * to replicate. Only after the EIP binding is done the host has a chance to
     * identify itself in the list of replica nodes and needs to take itself out
     * of replication traffic.
     */
    public static boolean isThisMe(String url) {
        InstanceInfo myInfo = ApplicationInfoManager.getInstance().getInfo();
        String hostName = hostFromUrl(url);
        return hostName != null && hostName.equals(myInfo.getHostName());
    }

    /**
     * 获取自身节点的url，与其比较
     * checks if the given service url contains the current host which is trying
     * to replicate. only after the eip binding is done the host has a chance to
     * identify itself in the list of replica nodes and needs to take itself out
     * of replication traffic.
     *
     * @param url the service url of the replica node that the check is made.
     * @return true, if the url represents the current node which is trying to
     * replicate, false otherwise.
     */
    public boolean isThisMyUrl(String url) {
        final String myUrlConfigured = serverConfig.getMyUrl();
        if (myUrlConfigured != null) {
            return myUrlConfigured.equals(url);
        }
        return isInstanceURL(url, applicationInfoManager.getInfo());
    }

    /**
     * 是否为实例url
     * Checks if the given service url matches the supplied instance
     *
     * @param url      the service url of the replica node that the check is made.
     * @param instance the instance to check the service url against
     * @return true, if the url represents the supplied instance, false otherwise.
     */
    public boolean isInstanceURL(String url, InstanceInfo instance) {
        String hostName = hostFromUrl(url);
        String myInfoComparator = instance.getHostName();
        if (clientConfig.getTransportConfig().applicationsResolverUseIp()) {
            myInfoComparator = instance.getIPAddr();
        }
        return hostName != null && hostName.equals(myInfoComparator);
    }

    public static String hostFromUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            logger.warn("Cannot parse service URI {}", url, e);
            return null;
        }
        return uri.getHost();
    }
}
