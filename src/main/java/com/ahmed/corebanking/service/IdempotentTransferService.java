package com.ahmed.corebanking.service;

import com.ahmed.corebanking.domain.IdempotencyRecord;
import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.exception.IdempotencyConflictException;
import com.ahmed.corebanking.dto.TransferRequest;
import com.ahmed.corebanking.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentTransferService {
    private static final Duration KEY_TTL = Duration.ofHours(24);

    private final TransferService transferService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IdempotentTransferResult executeTransfer(String idempotencyKey, TransferRequest request) {
        String requestHash = computeRequestHash(request);

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            return resolveExistingRecord(existing.get(), requestHash);
        }

        try {
            return transactionTemplate.execute(status -> {
                // Execute atomic ledger transfer + outbox persistence
                Transaction transaction = null;
                try {
                    transaction = transferService.transferMoney(request);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                Instant now = Instant.now();
                IdempotencyRecord record = IdempotencyRecord.builder()
                        .idempotencyKey(idempotencyKey)
                        .requestHash(requestHash)
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody(objectMapper.writeValueAsString(transaction))
                        .createdAt(now)
                        .expiresAt(now.plus(KEY_TTL))
                        .build();

                idempotencyRecordRepository.saveAndFlush(record);
                return new IdempotentTransferResult(transaction, false);
            });
        } catch (DataIntegrityViolationException ex) {
            // Concurrent duplicate request committed first
            log.info("Concurrent idempotency key collision detected for key={}", idempotencyKey);
            IdempotencyRecord concurrentRecord = idempotencyRecordRepository.findById(idempotencyKey)
                    .orElseThrow(() -> ex);
            return resolveExistingRecord(concurrentRecord, requestHash);
        }
    }

    private IdempotentTransferResult resolveExistingRecord(IdempotencyRecord record, String incomingHash) {
        if (!record.getRequestHash().equals(incomingHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key '" + record.getIdempotencyKey() + "' was already used with a different transfer payload."
            );
        }
        Transaction cachedTransaction = objectMapper.readValue(record.getResponseBody(), Transaction.class);
        return new IdempotentTransferResult(cachedTransaction, true);
    }

    private String computeRequestHash(TransferRequest request) {
        try {
            String canonical = String.format("%s|%s|%s",
                    request.sourceAccountNumber(),
                    request.targetAccountNumber(),
                    request.amount() != null ? request.amount().stripTrailingZeros().toPlainString() : "null");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    public record IdempotentTransferResult(Transaction transaction, boolean replayed) {
    }
}
