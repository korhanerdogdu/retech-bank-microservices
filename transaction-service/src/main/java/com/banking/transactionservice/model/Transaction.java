package com.banking.transactionservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String senderAccountNumber;

    @Column(nullable = false)
    private String receiverAccountNumber;

    // The IBAN the customer sent to (null for internal transfers made by account number)
    @Column(length = 34)
    private String receiverIban;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    private String description;
    @Column(length = 1000)
    private String failureReason;
    private String referenceNumber;

    // User Service id of the logged-in user who started the transfer
    private String initiatedByUserId;

    // Sender balance before the debit — used by the balance fraud rule
    @Column(precision = 15, scale = 2)
    private BigDecimal senderBalanceBefore;

    // Deadline for OTP verification; expired transfers are compensated automatically
    private LocalDateTime verificationExpiresAt;

    // True once transaction.completed was acknowledged by Kafka
    private Boolean completionEventPublished;

    // Optimistic lock: concurrent OTP submissions / duplicate events cannot both win
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
