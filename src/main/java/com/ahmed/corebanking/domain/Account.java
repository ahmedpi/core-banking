package com.ahmed.corebanking.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType;

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
    @JdbcType(VarcharJdbcType.class) // Explicitly instructs Hibernate to process this UUID as a standard String
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
