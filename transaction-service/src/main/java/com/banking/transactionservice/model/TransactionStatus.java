package com.banking.transactionservice.model;


public enum TransactionStatus {
    // Saved, sender debit not yet confirmed
    PENDING,
    // Sender debited, waiting for the fraud check
    PROCESSING,
    PENDING_VERIFICATION,
    COMPLETED,
    // Debit rejected — no money moved
    FAILED,
    // Cancellation claimed, refund in progress (retried until it succeeds)
    COMPENSATING,
    // Cancelled and refunded
    FLAGGED
}
