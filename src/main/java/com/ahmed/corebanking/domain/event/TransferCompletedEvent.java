package com.ahmed.corebanking.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferCompletedEvent(
        UUID transactionId,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        Instant timestamp
) {
}
