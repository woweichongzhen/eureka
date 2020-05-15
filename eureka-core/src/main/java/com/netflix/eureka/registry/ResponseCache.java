package com.netflix.eureka.registry;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 返回缓存接口
 *
 * @author David Liu
 */
public interface ResponseCache {

    /**
     * 销毁缓存
     *
     * @param appName          app名称
     * @param vipAddress       vip地址
     * @param secureVipAddress 安全vip地址
     */
    void invalidate(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress);

    /**
     * 获取版本增量
     *
     * @return 版本增量
     */
    AtomicLong getVersionDelta();

    /**
     * 获取远端版本增量
     *
     * @return 远端版本增量
     */
    AtomicLong getVersionDeltaWithRegions();

    /**
     * 获取正常的缓存应用信息
     * Get the cached information about applications.
     *
     * <p>
     * If the cached information is not available it is generated on the first
     * request. After the first request, the information is then updated
     * periodically by a background thread.
     * </p>
     *
     * @param key the key for which the cached information needs to be obtained.
     * @return payload which contains information about the applications.
     */
    String get(Key key);

    /**
     * 获取缓存，并进行gzip压缩
     * <p>
     * Get the compressed information about the applications.
     *
     * @param key the key for which the compressed cached information needs to be obtained.
     * @return compressed payload which contains information about the applications.
     */
    byte[] getGZIP(Key key);

    /**
     * 关闭缓存
     * <p>
     * Performs a shutdown of this cache by stopping internal threads and unregistering
     * Servo monitors.
     */
    void stop();
}
