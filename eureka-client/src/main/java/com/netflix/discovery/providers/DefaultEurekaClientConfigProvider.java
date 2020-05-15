package com.netflix.discovery.providers;

import com.google.inject.Inject;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryManager;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.EurekaNamespace;

import javax.inject.Provider;

/**
 * 创建 DefaultEurekaClientConfig 的工厂
 *
 * This provider is necessary because the namespace is optional.
 *
 * @author elandau
 */
public class DefaultEurekaClientConfigProvider implements Provider<EurekaClientConfig> {

    @Inject(optional = true)
    @EurekaNamespace
    private String namespace;

    private DefaultEurekaClientConfig config;

    @Override
    public synchronized EurekaClientConfig get() {
        if (config == null) {
            config = (namespace == null)
                    ? new DefaultEurekaClientConfig()
                    : new DefaultEurekaClientConfig(namespace);

            // TODO: Remove this when DiscoveryManager is finally no longer used
            // 服务发现管理，注入客户端配置
            DiscoveryManager.getInstance().setEurekaClientConfig(config);
        }

        return config;
    }
}
