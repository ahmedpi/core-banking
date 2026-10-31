package com.ahmed.corebanking.controller;

import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.service.TransferRequest;
import com.ahmed.corebanking.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class AccountController {

    private final TransferService transferService;

    /**
     * Exposes a POST endpoint to execute an atomic money transfer.
     * Maps incoming JSON payloads to Record DTO.
     */
    @PostMapping
    public ResponseEntity<Transaction> executeTransfer(@RequestBody TransferRequest request) {
        Transaction transactionLedger = transferService.transferMoney(request);
        return ResponseEntity.ok(transactionLedger);
    }
}
