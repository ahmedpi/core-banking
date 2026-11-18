package com.ahmed.corebanking.infrastructure.messaging;

import com.ahmed.corebanking.domain.FraudAlert;
import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.TransactionStatus;
import com.ahmed.corebanking.domain.event.FraudAssessment;
import com.ahmed.corebanking.domain.event.TransferCompletedEvent;
import com.ahmed.corebanking.repository.FraudAlertRepository;
import com.ahmed.corebanking.repository.TransactionRepository;
import com.ahmed.corebanking.service.FraudRiskEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionAnomalyConsumer {

    private static final int HIGH_RISK_THRESHOLD = 75;

    private final ObjectMapper objectMapper;
    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final FraudRiskEvaluator fraudRiskEvaluator;

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoCreateTopics = "true"
    )
    @KafkaListener(topics = "finflow.transfers.completed", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeTransferCompleted(
            String rawPayload,
            @Header(KafkaHeaders.RECEIVED_KEY) String partitionKey,
            Acknowledgment acknowledgment) {

        TransferCompletedEvent event = objectMapper.readValue(rawPayload, TransferCompletedEvent.class);
        log.debug("Evaluating transfer event txId={} from account={}", event.transactionId(), partitionKey);

        // 1. Consumer-side idempotency check (protects against at-least-once outbox redelivery)
        if (fraudAlertRepository.existsByTransactionId(event.transactionId())) {
            log.info("Skipping duplicate fraud evaluation for already processed txId={}", event.transactionId());
            acknowledgment.acknowledge();
            return;
        }

        // 2. Gather 1-hour sender velocity context
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        List<Transaction> recentTransfers = transactionRepository
                .findBySourceAccountNumberAndStatusAndTimestampAfter(
                        event.sourceAccountNumber(),
                        TransactionStatus.SUCCESS,
                        oneHourAgo
                );

        // 3. Score transaction risk
        FraudAssessment assessment = fraudRiskEvaluator.evaluate(event, recentTransfers);

        // 4. Persist alert if riskScore >= 75
        if (assessment.riskScore() >= HIGH_RISK_THRESHOLD) {
            try {
                FraudAlert alert = FraudAlert.builder()
                        .transactionId(event.transactionId())
                        .accountNumber(event.sourceAccountNumber())
                        .riskScore(assessment.riskScore())
                        .riskLevel(assessment.riskLevel())
                        .reasoning(assessment.reasoning())
                        .createdAt(Instant.now())
                        .build();

                fraudAlertRepository.save(alert);
                log.warn("FRAUD ALERT persisted: txId={}, account={}, score={}, reason={}",
                        event.transactionId(), event.sourceAccountNumber(),
                        assessment.riskScore(), assessment.reasoning());
            } catch (DataIntegrityViolationException ex) {
                // Concurrent duplicate race condition caught cleanly by DB UNIQUE constraint
                log.info("Concurrent duplicate fraud alert ignored for txId={}", event.transactionId());
            }
        } else {
            log.debug("Transfer cleared: txId={}, riskScore={}", event.transactionId(), assessment.riskScore());
        }

        // 5. Commit Kafka offset only after durable processing completes
        acknowledgment.acknowledge();
    }

    @DltHandler
    public void handleDeadLetter(
            String rawPayload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String errorMessage) {
        log.error("Message routed to DLT [{}]: error='{}', payload={}", topic, errorMessage, rawPayload);
    }
}