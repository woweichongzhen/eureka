package com.netflix.discovery.shared.transport;

import com.netflix.discovery.shared.resolver.EurekaEndpoint;

/**
 * 创建 EurekaHttpClient 的工厂接口
 * <p>
 * A low level client factory interface. Not advised to be used by top level consumers.
 *
 * @author David Liu
 */
public interface TransportClientFactory {

    EurekaHttpClient newClient(EurekaEndpoint serviceUrl);

    void shutdown();

}
