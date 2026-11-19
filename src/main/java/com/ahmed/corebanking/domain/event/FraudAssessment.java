package com.ahmed.corebanking.domain.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record FraudAssessment(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Integer fraud risk score from 0 (safe) to 100 (certain fraud)")
        Integer riskScore,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Risk classification: must be exactly LOW, MEDIUM, or HIGH")
        String riskLevel,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Concise 1-sentence explanation of why this score was assigned")
        String reasoning
) {}
