package com.ahmed.corebanking;

import com.ahmed.corebanking.domain.*;
import com.ahmed.corebanking.repository.AccountRepository;
import com.ahmed.corebanking.repository.FraudAlertRepository;
import com.ahmed.corebanking.repository.OutboxEventRepository;
import com.ahmed.corebanking.repository.TransactionRepository;
import com.ahmed.corebanking.dto.TransferRequest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.awaitility.Awaitility.await;

public class FraudConsumerIntegrationTest extends AbstractIntegrationTest {

    // REST -> TransferService -> Outbox -> Kafka -> TransactionAnomalyConsumer -> fraud_alerts

    private static final String TRANSFERS_URI = "/api/v1/transfers";

    @LocalServerPort
    private int port;

    private RestClient restClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private FraudAlertRepository fraudAlertRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> {
                })
                .build();

        accountRepository.deleteAll();
        transactionRepository.deleteAll();
        outboxEventRepository.deleteAll();
        fraudAlertRepository.deleteAll();

        accountRepository.save(Account.builder()
                .ownerName("Alice")
                .accountNumber("ACC123")
                .balance(new BigDecimal("5000.00"))
                .build());

        accountRepository.save(Account.builder()
                .ownerName("Bob")
                .accountNumber("ACC456")
                .balance(new BigDecimal("10000.00"))
                .build());
    }

    @Test
    @DisplayName("Should trigger high-risk fraud alert when single transfer exceeds threshold")
    void shouldTriggerHighRiskTransferOnSingleTXLimit() {
        TransferRequest request = new TransferRequest(
                "ACC123",
                "ACC456",
                new BigDecimal("5000.00"));

        ResponseEntity<Transaction> response = restClient.post()
                .uri(TRANSFERS_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(Transaction.class);

        Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertThat(response.getBody()).isNotNull();

        UUID txId = response.getBody().getId();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            FraudAlert alert = fraudAlertRepository.findByTransactionId(txId).orElse(null);
            Assertions.assertThat(alert).isNotNull();
            Assertions.assertThat(alert.getTransactionId()).isEqualTo(txId);
            Assertions.assertThat(alert.getAccountNumber()).isEqualTo("ACC123");
            Assertions.assertThat(alert.getRiskLevel()).isEqualTo("HIGH");
        });

    }

    @Test
    @DisplayName("Should ignore duplicate TransferCompletedEvent deliveries and maintain consumer idempotency")
    void shouldIgnoreDuplicateTransferEventAndNotCreateDuplicateAlert() throws ExecutionException, InterruptedException, TimeoutException {
        TransferRequest request = new TransferRequest(
                "ACC123",
                "ACC456",
                new BigDecimal("5000.00"));

        ResponseEntity<Transaction> response = restClient.post()
                .uri(TRANSFERS_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(Transaction.class);

        Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertThat(response.getBody()).isNotNull();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Assertions.assertThat(fraudAlertRepository.count()).isEqualTo(1L);
        });

        OutboxEvent event = outboxEventRepository.findAll().getFirst();

        String payload = event.getPayload();
        SendResult<String, String> result = kafkaTemplate.send("finflow.transfers.completed", "ACC123", payload).get(5, TimeUnit.SECONDS);

        Assertions.assertThat(result.getRecordMetadata()).isNotNull();

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Assertions.assertThat(fraudAlertRepository.count()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("Should trigger high-risk fraud alert when hourly velocity exceeds threshold")
    void shouldTriggerHighRiskTransferOnHourlyCountLimit() {

        //10–13 minutes in the past!
        seedHistoricalTransactions(4, Instant.now().minus(10, ChronoUnit.MINUTES));

        TransferRequest request = new TransferRequest(
                "ACC123",
                "ACC456",
                new BigDecimal("100.00"));

        ResponseEntity<Transaction> response = restClient.post()
                .uri(TRANSFERS_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(Transaction.class);

        Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertThat(response.getBody()).isNotNull();

        UUID txId = response.getBody().getId();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Assertions.assertThat(fraudAlertRepository.count()).isEqualTo(1);
            FraudAlert alert = fraudAlertRepository.findByTransactionId(txId).orElse(null);
            Assertions.assertThat(alert).isNotNull();
            Assertions.assertThat(alert.getAccountNumber()).isEqualTo("ACC123");
            Assertions.assertThat(alert.getRiskLevel()).isEqualTo("HIGH");
        });
    }

    @Test
    @DisplayName("Should not trigger alert when historical transfers are outside one hour window")
    void shouldNotTriggerAlertWhenHistoricalTransfersAreOutsideOneHourWindow() {
        //65-68 minutes in the past!
        seedHistoricalTransactions(4, Instant.now().minus(65, ChronoUnit.MINUTES));

        TransferRequest request = new TransferRequest(
                "ACC123",
                "ACC456",
                new BigDecimal("100.00"));

        ResponseEntity<Transaction> response = restClient.post()
                .uri(TRANSFERS_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(Transaction.class);

        Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertThat(response.getBody()).isNotNull();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Assertions.assertThat(outboxEventRepository.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        });

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Assertions.assertThat(fraudAlertRepository.count()).isZero();
        });

        Assertions.assertThat(transactionRepository.count()).isEqualTo(5);
    }

    private void seedHistoricalTransactions(int count, Instant baseTimestamp) {
        for (int i = 0; i < count; i++) {
            Transaction transaction = Transaction.builder()
                    .sourceAccountNumber("ACC123")
                    .targetAccountNumber("ACC456")
                    .amount(new BigDecimal("100.00"))
                    .timestamp(baseTimestamp.minusSeconds(i * 60L))
                    .status(TransactionStatus.SUCCESS)
                    .build();

            transactionRepository.save(transaction);
        }
    }
}
