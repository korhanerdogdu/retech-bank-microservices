package com.banking.accountservice.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business rule violation with the HTTP status callers should see.
 * A 4xx tells the Transaction Service that no money was moved.
 */
@Getter
public class AccountException extends RuntimeException {

    private final HttpStatus status;

    public AccountException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static AccountException notFound(String accountNumber) {
        return new AccountException(HttpStatus.NOT_FOUND, "Account not found: " + accountNumber);
    }
}
