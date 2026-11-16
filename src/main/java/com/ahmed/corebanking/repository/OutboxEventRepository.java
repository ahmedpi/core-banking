package com.ahmed.corebanking.repository;

import com.ahmed.corebanking.domain.OutboxEvent;
import com.ahmed.corebanking.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus outboxStatus);
}
