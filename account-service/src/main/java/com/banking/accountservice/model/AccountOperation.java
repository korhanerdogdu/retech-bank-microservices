package com.banking.accountservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ledger of balance changes made on behalf of another service.
 * The unique operation key (e.g. "DEBIT:{transactionId}") makes
 * deduct / credit / reverse calls idempotent, so retries and duplicate
 * Kafka events can never move money twice.
 */
@Entity
@Table(name = "account_operations")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AccountOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String operationKey;

    @Column(nullable = false)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationType type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceBefore;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
