package com.banking.transactionservice.exception;

/** The logged-in user tried to act on an account they don't own. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
