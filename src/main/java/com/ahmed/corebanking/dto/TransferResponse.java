package com.ahmed.corebanking.dto;

import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        TransactionStatus status,
        Instant createdAt
) {
    public static TransferResponse from(Transaction tx) {
        return new TransferResponse(
                tx.getId(),
                tx.getSourceAccountNumber(),
                tx.getTargetAccountNumber(),
                tx.getAmount(),
                tx.getStatus(),
                tx.getTimestamp()
        );
    }
}
