package com.ahmed.corebanking.service;

import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.event.TransferCompletedEvent;
import com.ahmed.corebanking.dto.FraudAssessment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class RuleBasedFraudRiskEvaluator implements FraudRiskEvaluator {

    private static final BigDecimal SINGLE_TX_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal HOURLY_VOLUME_LIMIT = new BigDecimal("10000.00");
    private static final int HOURLY_COUNT_LIMIT = 5;

    @Override
    public FraudAssessment evaluate(TransferCompletedEvent event, List<Transaction> recentHourlyTransfers) {
        int txCount = recentHourlyTransfers.size();
        BigDecimal hourlyVolume = recentHourlyTransfers.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean highSingleAmount = event.amount().compareTo(SINGLE_TX_LIMIT) >= 0;
        boolean highHourlyVolume = hourlyVolume.compareTo(HOURLY_VOLUME_LIMIT) >= 0;
        boolean highVelocityCount = txCount >= HOURLY_COUNT_LIMIT;

        // High-risk threshold triggers for testing & baseline rules before LLM integration
        if (highSingleAmount || highHourlyVolume || highVelocityCount) {
            return new FraudAssessment(
                    85,
                    FraudAssessment.RiskLevel.HIGH,
                    String.format("High-risk transfer detected: amount=%s, 1h_tx_count=%d, 1h_volume=%s",
                            event.amount(), txCount, hourlyVolume)
            );
        }

        return new FraudAssessment(
                15,
                FraudAssessment.RiskLevel.LOW,
                "Normal transfer velocity and amount within expected baseline."
        );
    }
}