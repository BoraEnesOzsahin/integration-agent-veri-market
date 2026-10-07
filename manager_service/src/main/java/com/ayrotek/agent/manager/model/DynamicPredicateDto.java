package com.ayrotek.agent.manager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DynamicPredicateDto(
        @JsonProperty("name") String name,
        @JsonProperty("pType") String pType,
        @JsonProperty("pValue") long pValue
) {
    public DynamicPredicateDto(String name, String pType, long pValue) {
        this.name = (name != null && !name.isBlank()) ? name.trim() : "attribute";
        this.pType = (pType != null && !pType.isBlank()) ? pType.trim() : ">=";
        this.pValue = pValue;
    }
}
