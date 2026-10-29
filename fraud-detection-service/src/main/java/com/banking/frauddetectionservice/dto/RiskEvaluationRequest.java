package com.banking.frauddetectionservice.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Facts collected by the Fraud Detection Service and sent to the Rust Risk Engine.
 * Money travels as JSON strings so no precision is lost between BigDecimal and Rust Decimal.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RiskEvaluationRequest {
    private String transactionId;
    private String accountNumber;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal amount;

    // Sender balance BEFORE the transfer
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal balance;

    // Transfers in the current 60s window, including this one
    private int velocityCount;

    // Running average of previous transfers; null when there is no history
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal averageAmount;
}
