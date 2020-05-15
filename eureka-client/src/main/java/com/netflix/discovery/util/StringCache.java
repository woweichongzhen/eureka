package com.netflix.discovery.util;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 字符串缓存
 * <p>
 * {@link String＃intern（）}的替代选择，没有容量限制
 * <p>
 * An alternative to {@link String#intern()} with no capacity constraints.
 *
 * @author Tomasz Bak
 */
public class StringCache {

    public static final int LENGTH_LIMIT = 38;

    /**
     * 字符串缓存单例
     */
    private static final StringCache INSTANCE = new StringCache();

    /**
     * 读写锁，保证读写互斥
     */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 缓存哈希表
     * 使用 WeakHashMap，当 StringCache 被回收时，其对应的值一起被移除
     */
    private final Map<String, WeakReference<String>> cache = new WeakHashMap<String, WeakReference<String>>();

    /**
     * 缓存字符串最大长度。默认值：38
     */
    private final int lengthLimit;

    public StringCache() {
        this(LENGTH_LIMIT);
    }

    public StringCache(int lengthLimit) {
        this.lengthLimit = lengthLimit;
    }

    /**
     * 获得字符串缓存
     * 若缓存不存在，则进行缓存
     * 和 String#intern() 的逻辑相同，区别在于 cache 支持自动扩容
     *
     * @param str 缓存字符串
     * @return 缓存后的字符串
     */
    public String cachedValueOf(final String str) {
        if (str != null && (lengthLimit < 0 || str.length() <= lengthLimit)) {
            try {
                // 读锁，读取缓存
                lock.readLock().lock();
                WeakReference<String> ref = cache.get(str);
                if (ref != null) {
                    return ref.get();
                }
            } finally {
                lock.readLock().unlock();
            }

            // 写锁，更新缓存内容
            try {
                lock.writeLock().lock();
                WeakReference<String> ref = cache.get(str);
                if (ref != null) {
                    return ref.get();
                }
                cache.put(str, new WeakReference<>(str));
            } finally {
                lock.writeLock().unlock();
            }
            return str;
        }
        return str;
    }

    /**
     * 缓存大小获取，读锁锁住
     *
     * @return 缓存大小
     */
    public int size() {
        try {
            lock.readLock().lock();
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 使用 INSTANCE 获取缓存字符串
     *
     * @param original 原始字符串
     * @return 缓存字符串
     */
    public static String intern(String original) {
        return INSTANCE.cachedValueOf(original);
    }
}
