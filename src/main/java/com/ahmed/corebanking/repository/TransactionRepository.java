package com.ahmed.corebanking.repository;

import com.ahmed.corebanking.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // Native Microsoft SQL Server query to pull ledger rows from the last X days
    @Query(value = "SELECT * FROM transactions WHERE " +
            "(source_account_number = :accNum OR target_account_number = :accNum) " +
            "AND timestamp >= DATEADD(day, -:days, GETDATE()) " +
            "ORDER BY timestamp DESC",
            nativeQuery = true)
    List<Transaction> findRecentTransactions(@Param("accNum") String accountNumber, @Param("days") int days);
}
