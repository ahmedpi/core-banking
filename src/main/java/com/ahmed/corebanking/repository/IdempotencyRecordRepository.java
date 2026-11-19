package com.ahmed.corebanking.repository;

import com.ahmed.corebanking.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    void deleteByExpiresAtBefore(Instant cutoff);
}