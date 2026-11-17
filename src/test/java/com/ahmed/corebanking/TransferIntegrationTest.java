package com.ahmed.corebanking;

import com.ahmed.corebanking.domain.Account;
import com.ahmed.corebanking.domain.OutboxEvent;
import com.ahmed.corebanking.domain.OutboxStatus;
import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.event.TransferCompletedEvent;
import com.ahmed.corebanking.repository.AccountRepository;
import com.ahmed.corebanking.repository.OutboxEventRepository;
import com.ahmed.corebanking.repository.TransactionRepository;
import com.ahmed.corebanking.service.TransferRequest;
import com.ahmed.corebanking.service.TransferService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.awaitility.Awaitility.await;

public class TransferIntegrationTest extends AbstractIntegrationTest {

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
    private ObjectMapper objectMapper;

    @Autowired
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                // Prevent 4xx responses from throwing RestClientResponseException so we can assert status codes directly
                .defaultStatusHandler(status -> true, (req, res) -> {
                })
                .build();

        outboxEventRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        accountRepository.save(Account.builder()
                .accountNumber("ACC123")
                .ownerName("Alice")
                .balance(new BigDecimal("10000.00"))
                .build());

        accountRepository.save(Account.builder()
                .accountNumber("ACC456")
                .ownerName("Bob")
                .balance(new BigDecimal("10000.00"))
                .build());
    }

    @Test
    @DisplayName("Should atomically update ledger, persist outbox event, and publish to Kafka with partition key")
    void shouldExecuteTransferAndPublishOutboxEventToKafka() throws Exception {
        TransferRequest request = new TransferRequest("ACC123", "ACC456", new BigDecimal("250.00"));

        ResponseEntity<Transaction> response = restClient.post()
                .uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(Transaction.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        assert response.getBody() != null;
        UUID txId = response.getBody().getId();

        Account alice = accountRepository.findByAccountNumber("ACC123").orElseThrow();
        Account bob = accountRepository.findByAccountNumber("ACC456").orElseThrow();
        assertThat(alice.getBalance()).isEqualByComparingTo("9750.00");
        assertThat(bob.getBalance()).isEqualByComparingTo("10250.00");

        // Poll until OutboxPublisherJob marks event PUBLISHED
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events.size()).isEqualTo(1);
            assertThat(events.getFirst().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(events.getFirst().getPublishedAt()).isNotNull();
        });

        // Verify Kafka broker received the event partitioned by ACC123
        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            consumer.subscribe(Collections.singletonList("finflow.transfers.completed"));

            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(received::add);
                assertThat(received.isEmpty()).isFalse();
            });

            ConsumerRecord<String, String> record = received.getFirst();
            assertThat(record.key()).isEqualTo("ACC123");

            TransferCompletedEvent event = objectMapper.readValue(record.value(), TransferCompletedEvent.class);
            assertThat(event.transactionId()).isEqualTo(txId);
            assertThat(event.amount()).isEqualByComparingTo("250.00");
        }
    }

    @Test
    @DisplayName("Should prevent deadlocks and maintain exact balances under 100 bidirectional concurrent transfers")
    void shouldHandleBidirectionalConcurrentTransfersWithoutDeadlocks() throws Exception {
        int totalTransfers = 100;
        int threadPoolSize = 40;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch readyLatch = new CountDownLatch(totalTransfers);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();

        for (int i = 0; i < totalTransfers; i++) {
            final boolean forward = (i % 2 == 0);
            TransferRequest req = forward
                    ? new TransferRequest("ACC123", "ACC456", new BigDecimal("10.00"))
                    : new TransferRequest("ACC456", "ACC123", new BigDecimal("10.00"));

            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return restClient.post()
                        .uri("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(req)
                        .retrieve()
                        .toEntity(String.class);
            }));
        }

        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown();

        int successCount = 0;
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> res = future.get(30, TimeUnit.SECONDS);
            if (res.getStatusCode() == HttpStatus.OK) {
                successCount++;
            }
        }
        executor.shutdown();

        assertThat(successCount).isEqualTo(totalTransfers);

        Account alice = accountRepository.findByAccountNumber("ACC123").orElseThrow();
        Account bob = accountRepository.findByAccountNumber("ACC456").orElseThrow();
        assertThat(alice.getBalance()).isEqualByComparingTo("10000.00");
        assertThat(bob.getBalance()).isEqualByComparingTo("10000.00");

        assertThat(transactionRepository.count()).isEqualTo(totalTransfers);
        assertThat(outboxEventRepository.count()).isEqualTo(totalTransfers);
    }

    @Test
    @DisplayName("Should roll back ledger and outbox completely on insufficient funds")
    void shouldRollbackTransactionOnInsufficientFunds() {
        TransferRequest overdraftRequest = new TransferRequest("ACC123", "ACC456", new BigDecimal("50000.00"));

        ResponseEntity<String> response = restClient.post()
                .uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(overdraftRequest)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();

        Account alice = accountRepository.findByAccountNumber("ACC123").orElseThrow();
        Account bob = accountRepository.findByAccountNumber("ACC456").orElseThrow();
        assertThat(alice.getBalance()).isEqualByComparingTo("10000.00");
        assertThat(bob.getBalance()).isEqualByComparingTo("10000.00");
        assertThat(transactionRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    private KafkaConsumer<String, String> createTestConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "finflow-test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
