package com.ahmed.corebanking.service;

import com.ahmed.corebanking.config.FraudEvaluationProperties;
import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.event.TransferCompletedEvent;
import com.ahmed.corebanking.dto.FraudAssessment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@Primary
public class SpringAiFraudRiskEvaluator implements FraudRiskEvaluator {
    private static final String ROUTINE_TRANSFER_REASON = "Routine low-value transfer";

    // Static system prompt: maximizes prompt-prefix caching in Ollama & cloud APIs
    private static final String SYSTEM_PROMPT = """
            You are a senior financial fraud risk analyst for FinFlow core banking.
            Evaluate peer-to-peer (P2P) money transfers for anomaly and fraud risk using these scoring guidelines:
            - riskScore 0 to 39 (riskLevel: LOW): Routine transfer within normal limits.
            - riskScore 40 to 74 (riskLevel: MEDIUM): Moderately elevated activity, below alert threshold.
            - riskScore 75 to 100 (riskLevel: HIGH): High-risk anomaly, including single transfer >= $5,000.00,
              1-hour transfer count >= 5, 1-hour cumulative transfer volume >= $10,000.00, or rapid structuring.
            
            Provide a concise, objective 1-2 sentence explanation in the reasoning field.
            
            Respond ONLY with a single JSON object matching this exact structure (all 3 fields are mandatory and non-null):
            {"riskScore": 85, "riskLevel": "HIGH", "reasoning": "Account initiated 8 transfers within 1 hour, exceeding normal velocity."}
            
            """;

    private final ChatClient chatClient;
    private final RuleBasedFraudRiskEvaluator ruleBasedEvaluator;
    private final FraudEvaluationProperties properties;

    public SpringAiFraudRiskEvaluator(ChatClient.Builder chatClientBuilder, RuleBasedFraudRiskEvaluator ruleBasedEvaluator, FraudEvaluationProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.ruleBasedEvaluator = ruleBasedEvaluator;
        this.properties = properties;
    }

    @Override
    public FraudAssessment evaluate(TransferCompletedEvent currentEvent, List<Transaction> recentHourlyTransfers) {
        int txCount = recentHourlyTransfers.size();
        // 1. Fast-path: Skip both rules and AI for trivial transfers
        if (properties.isRoutine(currentEvent.amount(), txCount)) {
            return new FraudAssessment(
                    properties.trivialDefaultRiskScore(),
                    FraudAssessment.RiskLevel.LOW,
                    ROUTINE_TRANSFER_REASON
            );
        }

        // 2. Run deterministic rules first; skip AI if rules already classify as clear LOW or definitive HIGH
        FraudAssessment ruleAssessment = ruleBasedEvaluator.evaluate(currentEvent, recentHourlyTransfers);
        if (!properties.requiresAiReview(ruleAssessment.riskScore())) {
            log.debug("Skipping AI evaluation for txId={}, deterministic rule score={}",
                    currentEvent.transactionId(), ruleAssessment.riskScore());
            return ruleAssessment;
        }

        // 3. Invoke Ollama only for ambiguous MEDIUM-risk cases
        return evaluateWithLlm(currentEvent, recentHourlyTransfers, txCount, ruleAssessment);
    }

    private FraudAssessment evaluateWithLlm(
            TransferCompletedEvent currentEvent,
            List<Transaction> recentHourlyTransfers,
            int txCount,
            FraudAssessment fallbackAssessment) {

        BigDecimal hourlyVolume = recentHourlyTransfers.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String userPrompt = """
                Evaluate the following completed transfer:
                - Transaction ID: %s
                - Source Account: %s
                - Target Account: %s
                - Amount: $%s
                - Timestamp (UTC): %s
                - Sender 1-Hour Completed Transfers: %d
                - Sender 1-Hour Cumulative Volume: $%s
                """.formatted(
                currentEvent.transactionId(),
                currentEvent.sourceAccountNumber(),
                currentEvent.targetAccountNumber(),
                currentEvent.amount(),
                currentEvent.timestamp(),
                txCount,
                hourlyVolume
        );

        try {
            FraudAssessment assessment = chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .entity(FraudAssessment.class);

            if (isIncomplete(assessment)) {
                throw new IllegalStateException("LLM returned an incomplete FraudAssessment payload");
            }

            log.info("AI Fraud Assessment completed for txId={}: score={}, level={}",
                    currentEvent.transactionId(), assessment.riskScore(), assessment.riskLevel());

            return assessment;
        } catch (Exception ex) {
            log.warn("Spring AI risk evaluation failed for txId={}, falling back to deterministic rules: {}",
                    currentEvent.transactionId(), ex.getMessage());
            return fallbackAssessment;
        }
    }

    private boolean isIncomplete(FraudAssessment assessment) {
        return assessment == null
                || assessment.riskScore() == null
                || assessment.riskLevel() == null
                || assessment.reasoning() == null;
    }
}
