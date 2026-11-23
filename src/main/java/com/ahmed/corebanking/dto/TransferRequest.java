package com.ahmed.corebanking.dto;

import java.math.BigDecimal;

public record TransferRequest(
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount
) {
}
