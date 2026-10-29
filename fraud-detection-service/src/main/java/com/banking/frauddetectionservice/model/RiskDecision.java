package com.banking.frauddetectionservice.model;

public enum RiskDecision {
    // Clean — complete the transfer
    ALLOW,
    // Suspicious — require OTP verification
    REVIEW,
    // Clear abuse — cancel, refund and block the account
    BLOCK
}
