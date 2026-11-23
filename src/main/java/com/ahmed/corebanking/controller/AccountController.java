package com.ahmed.corebanking.controller;

import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.dto.TransferRequest;
import com.ahmed.corebanking.dto.TransferResponse;
import com.ahmed.corebanking.service.IdempotentTransferService;
import com.ahmed.corebanking.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class AccountController {

    private final TransferService transferService;
    private final IdempotentTransferService idempotentTransferService;

    /**
     * Exposes a POST endpoint to execute an atomic money transfer.
     * Maps incoming JSON payloads to Record DTO.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TransferRequest request) {

        if (idempotencyKey == null) {
            Transaction transactionLedger = transferService.transferMoney(request);
            return ResponseEntity.ok(TransferResponse.from(transactionLedger));
        }

        String trimmedKey = idempotencyKey.trim();
        if (trimmedKey.isEmpty() || trimmedKey.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key header must be between 1 and 100 characters");
        }

        IdempotentTransferService.IdempotentTransferResult result = idempotentTransferService.executeTransfer(trimmedKey, request);

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
        if (result.replayed()) {
            responseBuilder.header("Idempotency-Replayed", "true");
        }

        return responseBuilder.body(TransferResponse.from(result.transaction()));
    }
}
