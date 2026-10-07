package com.ayrotek.agent.manager.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record DynamicZkpSchema(
        @JsonProperty("schemaName") String schemaName,
        @JsonProperty("version") String version,
        @JsonProperty("issuerDid") String issuerDid,
        @JsonProperty("attributeNames") List<String> attributeNames,
        @JsonProperty("predicates") List<Map<String, Object>> predicates,
        @JsonProperty("revealedAttributes") List<String> revealedAttributes,
        @JsonProperty("anoncredsEngineRegistered") boolean anoncredsEngineRegistered,
        @JsonProperty("anoncredsSchemaId") String anoncredsSchemaId,
        @JsonProperty("anoncredsSchemaPayload") Map<String, Object> anoncredsSchemaPayload,
        @JsonProperty("presentationRequestTemplate") Map<String, Object> presentationRequestTemplate
) {}
