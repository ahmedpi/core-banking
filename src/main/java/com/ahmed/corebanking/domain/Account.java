package com.ahmed.corebanking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "VARCHAR(36)") // Forces Hibernate to map UUID to VARCHAR instead of uniqueidentifier
    private UUID id;

    @Column(name="account_number", nullable = false)
    private String accountNumber;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(nullable = false)
    private BigDecimal balance;

    @Version
    private Long version; //Optimistic lock tracking parameter
}
