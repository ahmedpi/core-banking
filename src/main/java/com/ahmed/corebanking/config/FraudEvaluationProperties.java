package com.ahmed.corebanking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "app.fraud.evaluation")
public record FraudEvaluationProperties(
        @DefaultValue("50.00")
        BigDecimal trivialMaxAmount,
        @DefaultValue("2") int trivialMaxHourlyTransfers,
        @DefaultValue("5") int trivialDefaultRiskScore,
        @DefaultValue("39") int lowRiskMaxScore,
        @DefaultValue("74") int mediumRiskMaxScore
) {
    public boolean isRoutine(BigDecimal amount, int hourlyTransferCount) {
        return amount.compareTo(trivialMaxAmount) <= 0
                && hourlyTransferCount <= trivialMaxHourlyTransfers;
    }

    public boolean requiresAiReview(int ruleScore) {
        return ruleScore > lowRiskMaxScore && ruleScore <= mediumRiskMaxScore;
    }
}
