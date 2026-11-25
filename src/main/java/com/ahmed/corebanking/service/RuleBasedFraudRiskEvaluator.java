package com.ahmed.corebanking.service;

import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.event.TransferCompletedEvent;
import com.ahmed.corebanking.dto.FraudAssessment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class RuleBasedFraudRiskEvaluator implements FraudRiskEvaluator {

    // Hard HIGH-risk thresholds (definitive alert without needing LLM)
    private static final BigDecimal HIGH_SINGLE_TX_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal HIGH_HOURLY_VOLUME_LIMIT = new BigDecimal("10000.00");
    private static final int HIGH_HOURLY_COUNT_LIMIT = 5;

    // Gray-area MEDIUM-risk thresholds (routes to Ollama for behavioral analysis)
    private static final BigDecimal MEDIUM_SINGLE_TX_LIMIT = new BigDecimal("1500.00");
    private static final BigDecimal MEDIUM_HOURLY_VOLUME_LIMIT = new BigDecimal("4000.00");
    private static final int MEDIUM_HOURLY_COUNT_LIMIT = 3;

    private static final int HIGH_RISK_SCORE = 85;
    private static final int MEDIUM_RISK_SCORE = 55;
    private static final int LOW_RISK_SCORE = 15;

    @Override
    public FraudAssessment evaluate(TransferCompletedEvent event, List<Transaction> recentHourlyTransfers) {
        int txCount = recentHourlyTransfers.size();
        BigDecimal hourlyVolume = recentHourlyTransfers.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (isHighRisk(event.amount(), hourlyVolume, txCount)) {
            return new FraudAssessment(
                    HIGH_RISK_SCORE,
                    FraudAssessment.RiskLevel.HIGH,
                    String.format("High-risk transfer detected: amount=%s, 1h_tx_count=%d, 1h_volume=%s",
                            event.amount(), txCount, hourlyVolume)
            );
        }

        if (isMediumRisk(event.amount(), hourlyVolume, txCount)) {
            return new FraudAssessment(
                    MEDIUM_RISK_SCORE,
                    FraudAssessment.RiskLevel.MEDIUM,
                    String.format("Moderately elevated activity: amount=%s, 1h_tx_count=%d, 1h_volume=%s",
                            event.amount(), txCount, hourlyVolume)
            );
        }

        return new FraudAssessment(
                LOW_RISK_SCORE,
                FraudAssessment.RiskLevel.LOW,
                "Normal transfer velocity and amount within expected baseline."
        );
    }

    private boolean isHighRisk(BigDecimal amount, BigDecimal hourlyVolume, int txCount) {
        return amount.compareTo(HIGH_SINGLE_TX_LIMIT) >= 0
                || hourlyVolume.compareTo(HIGH_HOURLY_VOLUME_LIMIT) >= 0
                || txCount >= HIGH_HOURLY_COUNT_LIMIT;
    }

    private boolean isMediumRisk(BigDecimal amount, BigDecimal hourlyVolume, int txCount) {
        return amount.compareTo(MEDIUM_SINGLE_TX_LIMIT) >= 0
                || hourlyVolume.compareTo(MEDIUM_HOURLY_VOLUME_LIMIT) >= 0
                || txCount >= MEDIUM_HOURLY_COUNT_LIMIT;
    }
}