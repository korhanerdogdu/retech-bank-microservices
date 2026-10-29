package com.banking.transactionservice.dto;

import com.banking.transactionservice.model.TransactionStatus;
import com.banking.transactionservice.model.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionResponse {
    private String id;
    private String senderAccountNumber;
    private String receiverAccountNumber;
    private String receiverIban;
    private BigDecimal amount;
    private TransactionType type;
    private TransactionStatus status;
    private String description;
    private String referenceNumber;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    // Set while PENDING_VERIFICATION — lets clients show the OTP countdown
    private LocalDateTime verificationExpiresAt;
    // Seconds left to enter the OTP, computed server-side so client clocks/time zones don't matter
    private Long verificationSecondsRemaining;
}
