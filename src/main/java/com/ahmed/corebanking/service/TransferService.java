package com.ahmed.corebanking.service;

import com.ahmed.corebanking.domain.*;
import com.ahmed.corebanking.domain.event.TransferCompletedEvent;
import com.ahmed.corebanking.domain.exception.AccountNotFoundException;
import com.ahmed.corebanking.domain.exception.InsufficientFundsException;
import com.ahmed.corebanking.dto.TransferRequest;
import com.ahmed.corebanking.repository.AccountRepository;
import com.ahmed.corebanking.repository.OutboxEventRepository;
import com.ahmed.corebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Executes a secure P2P money transfer between accounts.
     * Uses Isolation.REPEATABLE_READ to ensure balances remain stable mid-calculation.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Transaction transferMoney(TransferRequest request) throws InterruptedException {

        // 1. Structural business validation rules
        if (request.sourceAccountNumber().equals(request.targetAccountNumber())) {
            throw new IllegalArgumentException("Source and target accounts must be different.");
        }

        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be greater than zero.");
        }

        // 2. Acquire pessimistic write locks
        Map<String, Account> lockedAccounts = lockAccountsInOrder(
                request.sourceAccountNumber(),
                request.targetAccountNumber()
        );

        Account sourceAccount = lockedAccounts.get(request.sourceAccountNumber());
        Account targetAccount = lockedAccounts.get(request.targetAccountNumber());

        // 3. Financial solvency check
        if (sourceAccount.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException("Insufficient funds for account: " + request.sourceAccountNumber());
        }

        // 4. Update balances (Dirty checking will flush on commit, explicit save is fine too)
        sourceAccount.setBalance(sourceAccount.getBalance().subtract(request.amount()));
        targetAccount.setBalance(targetAccount.getBalance().add(request.amount()));

        accountRepository.save(sourceAccount);
        accountRepository.save(targetAccount);

        // 5. Persist immutable ledger log using UTC Instant
        Instant now = Instant.now();
        Transaction auditLedgerLog = Transaction.builder()
                .sourceAccountNumber(sourceAccount.getAccountNumber())
                .targetAccountNumber(targetAccount.getAccountNumber())
                .amount(request.amount())
                .timestamp(now)
                .status(TransactionStatus.SUCCESS)
                .build();

        Transaction savedTransaction = transactionRepository.save(auditLedgerLog);

        // 6. Write Outbox Event in the exact same ACID boundary
        saveOutboxEvent(savedTransaction);

        return savedTransaction;
    }

    private Map<String, Account> lockAccountsInOrder(String sourceAccountNumber, String targetAccountNumber) throws InterruptedException {
        // 2. Deterministic lock ordering to prevent bidirectional deadlocks
        // Sort the account numbers lexicographically before acquiring PESSIMISTIC_WRITE locks so
        // both threads always lock ACC1 first, then ACC2.

        List<String> lockKeys = new ArrayList<>(List.of(sourceAccountNumber, targetAccountNumber));

        Collections.sort(lockKeys);

        Account firstAccount = lockAccountOrThrow(lockKeys.get(0));
        Thread.sleep(10);
        Account secondAccount = lockAccountOrThrow(lockKeys.get(1));

        return Map.of(
                firstAccount.getAccountNumber(), firstAccount,
                secondAccount.getAccountNumber(), secondAccount
        );

    }

    private Account lockAccountOrThrow(String accountNumber) {
        return accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
    }

    private void saveOutboxEvent(Transaction tx) {
        TransferCompletedEvent eventPayload = new TransferCompletedEvent(
                tx.getId(),
                tx.getSourceAccountNumber(),
                tx.getTargetAccountNumber(),
                tx.getAmount(),
                tx.getTimestamp()
        );

        try {
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("ACCOUNT") // Partition by source account for strict per-user ordering
                    .aggregateId(tx.getSourceAccountNumber())
                    .eventType("TransferCompletedEvent")
                    .payload(objectMapper.writeValueAsString(eventPayload))
                    .status(OutboxStatus.PENDING)
                    .createdAt(tx.getTimestamp())
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (JacksonException e) {
            // Triggers full transaction rollback so ledger and outbox never drift
            throw new IllegalStateException("Failed to serialize TransferCompletedEvent for tx: " + tx.getId(), e);
        }
    }
}
