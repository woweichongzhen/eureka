package com.netflix.eureka.cluster.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.netflix.discovery.provider.Serializer;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量同步返回
 * The jersey resource class that generates the replication batch response.
 */
@Serializer("jackson") // For backwards compatibility with DiscoveryJerseyProvider
public class ReplicationListResponse {

    /**
     * 返回列表
     */
    private List<ReplicationInstanceResponse> responseList;

    public ReplicationListResponse() {
        this.responseList = new ArrayList<ReplicationInstanceResponse>();
    }

    @JsonCreator
    public ReplicationListResponse(@JsonProperty("responseList") List<ReplicationInstanceResponse> responseList) {
        this.responseList = responseList;
    }

    public List<ReplicationInstanceResponse> getResponseList() {
        return responseList;
    }

    public void addResponse(ReplicationInstanceResponse singleResponse) {
        responseList.add(singleResponse);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        ReplicationListResponse that = (ReplicationListResponse) o;

        return !(responseList != null ? !responseList.equals(that.responseList) : that.responseList != null);

    }

    @Override
    public int hashCode() {
        return responseList != null ? responseList.hashCode() : 0;
    }
}
