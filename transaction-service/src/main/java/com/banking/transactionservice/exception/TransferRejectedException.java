package com.banking.transactionservice.exception;

/** The transfer was refused (e.g. insufficient balance); no money was moved. */
public class TransferRejectedException extends RuntimeException {
    public TransferRejectedException(String message) {
        super(message);
    }
}
