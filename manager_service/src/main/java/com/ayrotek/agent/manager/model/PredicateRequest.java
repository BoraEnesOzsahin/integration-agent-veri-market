package com.ayrotek.agent.manager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PredicateRequest(
        @JsonProperty("jobId") String jobId,
        @JsonProperty("datasetName") String datasetName,
        @JsonProperty("targetMetric") String targetMetric,
        @JsonProperty("predicates") List<DynamicPredicateDto> predicates,
        @JsonProperty("revealedAttributes") List<String> revealedAttributes,
        @JsonProperty("customAttributes") Map<String, Object> customAttributes,
        @JsonProperty("rawQuery") String rawQuery,
        @JsonProperty("requesterId") String requesterId
) {
    public PredicateRequest {
        if (predicates == null) {
            predicates = Collections.emptyList();
        }
        if (revealedAttributes == null) {
            revealedAttributes = Collections.emptyList();
        }
        if (customAttributes == null) {
            customAttributes = Collections.emptyMap();
        }
    }
}
