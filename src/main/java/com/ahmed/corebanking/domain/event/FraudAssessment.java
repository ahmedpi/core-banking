package com.ahmed.corebanking.domain.event;

public record FraudAssessment(
        int riskScore,
        String riskLevel,
        String reasoning
) {
}
