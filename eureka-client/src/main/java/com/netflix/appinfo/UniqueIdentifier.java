package com.netflix.appinfo;

/**
 * 通常表示数据中心的唯一标识
 * Generally indicates the unique identifier of a {@link com.netflix.appinfo.DataCenterInfo}, if applicable.
 *
 * @author rthomas@atlassian.com
 */
public interface UniqueIdentifier {
    String getId();
}
