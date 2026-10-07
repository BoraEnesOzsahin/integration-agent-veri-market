package com.ayrotek.agent.manager.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record DynamicVcSchema(
        @JsonProperty("schemaId") String schemaId,
        @JsonProperty("schemaType") String schemaType,
        @JsonProperty("context") List<String> context,
        @JsonProperty("credentialTypes") List<String> credentialTypes,
        @JsonProperty("issuerDid") String issuerDid,
        @JsonProperty("requiredClaims") List<String> requiredClaims,
        @JsonProperty("predicateRulesSummary") List<String> predicateRulesSummary,
        @JsonProperty("credentialSubjectJsonSchema") Map<String, Object> credentialSubjectJsonSchema
) {}
