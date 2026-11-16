package com.ahmed.corebanking.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcType(VarcharJdbcType.class) // Explicitly instructs Hibernate to process this UUID as a standard String
    @Column(columnDefinition = "VARCHAR(36)") // Forces Hibernate to map UUID to VARCHAR instead of uniqueidentifier
    private UUID id;

    @Column(name = "source_account_number", nullable = false)
    private String sourceAccountNumber;

    @Column(name = "target_account_number", nullable = false)
    private String targetAccountNumber;

    @Column(nullable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;
}
