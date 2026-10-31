package com.ahmed.corebanking.service;

import java.math.BigDecimal;

public record TransferRequest(
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount
) {
}
