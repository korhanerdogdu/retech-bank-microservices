package com.banking.transactionservice.exception;

/**
 * A dependency failed mid-transfer. Any deducted amount is returned —
 * immediately, or by the SAGA recovery job if the refund also failed.
 */
public class TransferFailedException extends RuntimeException {
    public TransferFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
