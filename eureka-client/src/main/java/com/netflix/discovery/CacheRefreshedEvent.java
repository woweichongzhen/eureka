package com.netflix.discovery;

/**
 * 缓存刷新事件
 * <p>
 * This event is sent by {@link EurekaClient) whenever it has refreshed its local
 * local cache with information received from the Eureka server.
 *
 * @author brenuart
 */
public class CacheRefreshedEvent extends DiscoveryEvent {
    @Override
    public String toString() {
        return "CacheRefreshedEvent[timestamp=" + getTimestamp() + "]";
    }
}
