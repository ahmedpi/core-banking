package com.ahmed.corebanking.infrastructure.messaging;

import com.ahmed.corebanking.domain.OutboxEvent;
import com.ahmed.corebanking.domain.OutboxStatus;
import com.ahmed.corebanking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherJob {
    private static final String TOPIC = "finflow.transfers.completed";

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${finflow.outbox.poll-interval-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        log.debug("Polling outbox_events table for PENDING events...");

        List<OutboxEvent> events = outboxRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        if (events.isEmpty()) {
            log.debug("No pending outbox events found.");
            return;
        }

        for (OutboxEvent event : events) {
            try {
                log.debug("Publishing outbox event id={}, aggregateType={}, aggregateId={}, payload={}",
                        event.getId(), event.getAggregateType(), event.getAggregateId(), event.getPayload());

                // Partition by aggregateId (transactionId) or sourceAccountNumber for ordering
                SendResult<String, String> result = kafkaTemplate.send(TOPIC, event.getAggregateId(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());

                log.info("Published outbox event id={} to topic={}, partition={}, offset={}",
                        event.getId(),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());

            } catch (Exception ex) {
                log.error("Failed to publish outbox event id={}", event.getId(), ex);
                // Leave as PENDING so next cycle retries; add retry_count + DLQ for poison pills
                break;
            }
        }
        outboxRepository.saveAll(events);
        log.debug("Finished outbox polling cycle.");
    }
}
