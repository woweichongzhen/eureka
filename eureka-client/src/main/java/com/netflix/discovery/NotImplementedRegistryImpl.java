package com.netflix.discovery;

import com.netflix.discovery.shared.Applications;

import javax.inject.Singleton;

/**
 * 未实现的后备注册中心
 *
 * @author Nitesh Kant
 */
@Singleton
public class NotImplementedRegistryImpl implements BackupRegistry {

    @Override
    public Applications fetchRegistry() {
        return null;
    }

    @Override
    public Applications fetchRegistry(String[] includeRemoteRegions) {
        return null;
    }
}
