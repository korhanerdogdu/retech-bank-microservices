package com.banking.transactionservice.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 6 digit one-time passwords from a cryptographically secure RNG
 * (Math.random() is predictable and must not be used for OTPs).
 */
@Component
public class OtpGenerator {

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        return String.format("%06d", 100_000 + secureRandom.nextInt(900_000));
    }
}
