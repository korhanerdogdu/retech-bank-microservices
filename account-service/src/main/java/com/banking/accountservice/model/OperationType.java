package com.banking.accountservice.model;


public enum OperationType {
    DEBIT,
    CREDIT,
    REVERSAL;

    public String keyFor(String reference) {
        return name() + ":" + reference;
    }
}
