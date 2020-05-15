package com.netflix.eureka.registry.rule;

import com.netflix.appinfo.InstanceInfo;

/**
 * 状态重写结果，由状态规则匹配得到
 * <p>
 * Container for a result computed by an {@link InstanceStatusOverrideRule}.
 * <p>
 * Created by Nikos Michalakis on 7/13/16.
 */
public class StatusOverrideResult {

    public static StatusOverrideResult NO_MATCH = new StatusOverrideResult(false, null);

    public static StatusOverrideResult matchingStatus(InstanceInfo.InstanceStatus status) {
        return new StatusOverrideResult(true, status);
    }

    // Does the rule match?
    /**
     * 是否匹配
     */
    private final boolean matches;

    // The status computed by the rule.
    /**
     * 计算后的实例状态
     */
    private final InstanceInfo.InstanceStatus status;

    private StatusOverrideResult(boolean matches, InstanceInfo.InstanceStatus status) {
        this.matches = matches;
        this.status = status;
    }

    public boolean matches() {
        return matches;
    }

    public InstanceInfo.InstanceStatus status() {
        return status;
    }
}
