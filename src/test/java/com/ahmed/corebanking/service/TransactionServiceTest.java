package com.ahmed.corebanking.service;

import com.ahmed.corebanking.domain.Account;
import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.TransactionStatus;
import com.ahmed.corebanking.repository.AccountRepository;
import com.ahmed.corebanking.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    // 2. Automatically instantiate TransferService and inject the mocked repositories into it
    @InjectMocks
    private TransferService transferService;

    private Account sourceAccount;
    private Account targetAccount;

    @BeforeEach
    public void init() {
        sourceAccount = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("acc1")
                .ownerName("Ahmed")
                .balance(BigDecimal.valueOf(1000))
                .build();

        targetAccount = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("acc2")
                .ownerName("Markos")
                .balance(BigDecimal.valueOf(300))
                .build();
    }

    @Test
    public void shouldExecuteTransferSuccessfully() {
        BigDecimal transferAmount = BigDecimal.valueOf(100);
        // Arrange
        TransferRequest request = new TransferRequest("acc1", "acc2", transferAmount);

        // Create the expected output transaction receipt layout matching your domain model specifications
        Transaction mockSavedTransaction = new Transaction(
                UUID.randomUUID(),
                "acc1",
                "acc2",
                BigDecimal.valueOf(100),
                LocalDateTime.now(),
                TransactionStatus.SUCCESS
        );

        // When
        when(accountRepository.findByAccountNumberWithLock("acc1")).thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByAccountNumberWithLock("acc2")).thenReturn(Optional.of(targetAccount));
        when(transactionRepository.save(any(Transaction.class))).thenReturn(mockSavedTransaction);

        // Act
        Transaction result = transferService.transferMoney(request);

        assertNotNull(result);
        assertEquals(TransactionStatus.SUCCESS, result.getStatus());

        assertEquals(0, BigDecimal.valueOf(900).compareTo(sourceAccount.getBalance()), "Expected balance to be 900, but was " + sourceAccount.getBalance());
        assertEquals(0, BigDecimal.valueOf(400).compareTo(targetAccount.getBalance()));

        // Verify storage layer commits
        verify(accountRepository, times(1)).save(sourceAccount);
        verify(accountRepository, times(1)).save(targetAccount);
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    public void shouldThrowExceptionWhenFundsAreInsufficient() {
        BigDecimal overdraftAmount = BigDecimal.valueOf(10000);

        // Arrange
        TransferRequest request = new TransferRequest("acc1", "acc2", overdraftAmount);

        // When
        when(accountRepository.findByAccountNumberWithLock("acc1")).thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByAccountNumberWithLock("acc2")).thenReturn(Optional.of(targetAccount));

        assertThrows(IllegalArgumentException.class, () -> {
            transferService.transferMoney(request);
        }, "Should throw an exception because source account balance (100) is lower than transfer amount (10000)");

        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }
}
