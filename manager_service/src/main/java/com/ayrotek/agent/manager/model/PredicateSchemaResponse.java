package com.ayrotek.agent.manager.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PredicateSchemaResponse(
        @JsonProperty("status") String status,
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("institutionName") String institutionName,
        @JsonProperty("jobId") String jobId,
        @JsonProperty("datasetName") String datasetName,
        @JsonProperty("targetMetric") String targetMetric,
        @JsonProperty("schemaName") String schemaName,
        @JsonProperty("zkpSchema") DynamicZkpSchema zkpSchema,
        @JsonProperty("vcSchema") DynamicVcSchema vcSchema,
        @JsonProperty("generatedAt") String generatedAt,
        @JsonProperty("message") String message
) {}
