package com.ahmed.corebanking.service;

import com.ahmed.corebanking.domain.Account;
import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.TransactionStatus;
import com.ahmed.corebanking.repository.AccountRepository;
import com.ahmed.corebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    /**
     * Executes a secure P2P money transfer between accounts.
     * Uses Isolation.REPEATABLE_READ to ensure balances remain stable mid-calculation.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Transaction transferMoney(TransferRequest request) {

        // 1. Structural business validation rules
        if (request.sourceAccountNumber().equals(request.targetAccountNumber())) {
            throw new IllegalArgumentException("Source and target accounts must be different.");
        }

        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be greater than zero.");
        }

        // 2. Fetch both entities using our row-locking repository queries
        Account sourceAccount = accountRepository.findByAccountNumberWithLock(request.sourceAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Source account not found: " + request.sourceAccountNumber()));
        Account targetAccount = accountRepository.findByAccountNumberWithLock(request.targetAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Target account not found: " + request.targetAccountNumber()));

        // 3. Financial solvency check
        if (sourceAccount.getBalance().compareTo(request.amount()) < 0) {
            throw new IllegalArgumentException("Insufficient funds for account: " + request.sourceAccountNumber());
        }

        // 4. Update the active account entities in memory
        sourceAccount.setBalance(sourceAccount.getBalance().subtract(request.amount()));
        targetAccount.setBalance(targetAccount.getBalance().add(request.amount()));

        // 5. Push updates to database storage
        accountRepository.save(sourceAccount);
        accountRepository.save(targetAccount);

        // 6. Generate and persist the immutable audit ledger log row
        Transaction auditLedgerLog = Transaction.builder()
                .sourceAccountNumber(sourceAccount.getAccountNumber())
                .targetAccountNumber(targetAccount.getAccountNumber())
                .amount(request.amount())
                .timestamp(LocalDateTime.now())
                .status(TransactionStatus.SUCCESS)
                .build();

        return transactionRepository.save(auditLedgerLog);
    }
}
