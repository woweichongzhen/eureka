package com.netflix.eureka.cluster;

import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.cluster.protocol.ReplicationList;
import com.netflix.eureka.cluster.protocol.ReplicationListResponse;
import com.netflix.eureka.resources.ASGResource.ASGStatus;

/**
 * 同步请求client
 *
 * @author Tomasz Bak
 */
public interface HttpReplicationClient extends EurekaHttpClient {

    /**
     * 状态更新
     */
    EurekaHttpResponse<Void> statusUpdate(String asgName, ASGStatus newStatus);

    /**
     * 批量更新
     */
    EurekaHttpResponse<ReplicationListResponse> submitBatchUpdates(ReplicationList replicationList);
}
