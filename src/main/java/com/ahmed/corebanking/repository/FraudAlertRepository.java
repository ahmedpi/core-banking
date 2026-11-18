package com.ahmed.corebanking.repository;

import com.ahmed.corebanking.domain.FraudAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, UUID> {
    boolean existsByTransactionId(UUID transactionId);
    Optional<FraudAlert> findByTransactionId(UUID transactionId);
}