package com.banking.transactionservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(name = "account-service", url = "${account.service.url}")
public interface AccountServiceClient {

    String BALANCE_BEFORE_HEADER = "X-Balance-Before";

    // reference = transaction id → idempotent; response header carries the pre-debit balance
    @PutMapping("/api/v1/accounts/{accountNumber}/deduct")
    ResponseEntity<String> deductBalance(@PathVariable String accountNumber,
                                         @RequestParam BigDecimal amount,
                                         @RequestParam String reference);


    @PutMapping("/api/v1/accounts/{accountNumber}/credit")
    String creditBalance(@PathVariable String accountNumber,
                         @RequestParam BigDecimal amount);

    // SAGA compensation — refunds the debit with this reference, at most once
    @PutMapping("/api/v1/accounts/{accountNumber}/reverse")
    String reverseDebit(@PathVariable String accountNumber,
                        @RequestParam String reference);

    @GetMapping("/api/v1/accounts/{accountNumber}/balance")
    BigDecimal getBalance(@PathVariable String accountNumber);

    // Ownership check: who owns the account a user wants to send from
    @GetMapping("/api/v1/accounts/{accountNumber}")
    AccountOwner getAccount(@PathVariable String accountNumber);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountOwner(String accountNumber, String ownerUserId) {
    }

    // Recipient by IBAN — 400 invalid IBAN, 422 another bank, 404 unknown
    @GetMapping("/api/v1/accounts/iban/{iban}")
    Recipient lookupIban(@PathVariable String iban);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Recipient(String iban, String accountNumber, String displayName, boolean canReceive) {
    }
}
