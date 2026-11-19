package com.ahmed.corebanking.service;

import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.event.FraudAssessment;
import com.ahmed.corebanking.domain.event.TransferCompletedEvent;
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
    private final RuleBasedFraudRiskEvaluator ruleBasedFallback;

    public SpringAiFraudRiskEvaluator(ChatClient.Builder chatClientBuilder, RuleBasedFraudRiskEvaluator ruleBasedFallback) {
        this.chatClient = chatClientBuilder.build();
        this.ruleBasedFallback = ruleBasedFallback;
    }

    @Override
    public FraudAssessment evaluate(TransferCompletedEvent event, List<Transaction> recentHourlyTransfers) {
        int txCount = recentHourlyTransfers.size();
        BigDecimal hourlyVolume = recentHourlyTransfers.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String userPrompt = String.format("""
                        Evaluate the following completed transfer:
                        - Transaction ID: %s
                        - Source Account: %s
                        - Target Account: %s
                        - Amount: $%s
                        - Timestamp (UTC): %s
                        - Sender 1-Hour Completed Transfers: %d
                        - Sender 1-Hour Cumulative Volume: $%s
                        """,
                event.transactionId(),
                event.sourceAccountNumber(),
                event.targetAccountNumber(),
                event.amount(),
                event.timestamp(),
                txCount,
                hourlyVolume
        );

        try {
            FraudAssessment assessment = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .entity(FraudAssessment.class);

            if (assessment == null || assessment.riskLevel() == null || assessment.reasoning() == null) {
                log.debug("Incomplete assessment payload from LLM: {}", assessment);
                throw new IllegalStateException("LLM returned an incomplete FraudAssessment payload");
            }

            log.info("AI Fraud Assessment completed for txId={}: score={}, level={}",
                    event.transactionId(), assessment.riskScore(), assessment.riskLevel());

            return assessment;
        } catch (Exception ex) {
            log.warn("Spring AI risk evaluation failed for txId={}, falling back to deterministic rules: {}",
                    event.transactionId(), ex.getMessage());
            return ruleBasedFallback.evaluate(event, recentHourlyTransfers);
        }
    }
}
