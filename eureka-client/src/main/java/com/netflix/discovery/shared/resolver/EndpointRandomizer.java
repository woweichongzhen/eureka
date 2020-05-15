package com.netflix.discovery.shared.resolver;

import java.util.List;

/**
 * 从list中随机获取端点
 */
public interface EndpointRandomizer {
    <T extends EurekaEndpoint> List<T> randomize(List<T> list);
}
