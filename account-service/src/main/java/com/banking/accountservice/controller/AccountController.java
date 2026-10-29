package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.dto.RecipientResponse;
import com.banking.accountservice.model.AccountOperation;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    // Balance before the operation, so callers (fraud checks) see the pre-transfer balance
    public static final String BALANCE_BEFORE_HEADER = "X-Balance-Before";
    // Set by the API Gateway from the validated JWT
    public static final String USER_ID_HEADER = "X-User-Id";

    private final AccountService accountService;

    // Create new bank account
    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(request));
    }

    // Get account details. Through the gateway (X-User-Id set) only the owner may read it;
    // internal service calls carry no user header.
    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(
            @PathVariable String accountNumber,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return ResponseEntity.ok(userId == null
                ? accountService.getAccount(accountNumber)
                : accountService.getAccountForUser(accountNumber, userId));
    }

    // Get account balance
    @GetMapping("/{accountNumber}/balance")
    public ResponseEntity<BigDecimal> getBalance(
            @PathVariable String accountNumber,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return ResponseEntity.ok(userId == null
                ? accountService.getBalance(accountNumber)
                : accountService.getBalanceForUser(accountNumber, userId));
    }

    // Recipient check before sending money: IBAN → "Bob K." (spaces in the IBAN are allowed)
    @GetMapping("/iban/{iban}")
    public ResponseEntity<RecipientResponse> lookupByIban(
            @PathVariable String iban) {
        return ResponseEntity.ok(accountService.lookupByIban(iban));
    }

    // Block account
    @PutMapping("/{accountNumber}/block")
    public ResponseEntity<String> blockAccount(
            @PathVariable String accountNumber) {
        accountService.blockAccount(accountNumber);
        return ResponseEntity.ok("Account blocked successfully");
    }

    /**
     * SAGA STEP 1 — Deduct balance.
     * Called by Transaction Service when transfer is initiated.
     * The optional reference (transaction id) makes retries idempotent.
     */
    @PutMapping("/{accountNumber}/deduct")
    public ResponseEntity<String> deductBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String reference) {
        AccountOperation operation = accountService.deductBalance(accountNumber, amount, reference);
        return ResponseEntity.ok()
                .header(BALANCE_BEFORE_HEADER, operation.getBalanceBefore().toPlainString())
                .body("Balance deducted successfully");
    }


    @PutMapping("/{accountNumber}/credit")
    public ResponseEntity<String> creditBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String reference) {
        accountService.creditBalance(accountNumber, amount, reference);
        return ResponseEntity.ok("Balance credited successfully");
    }

    /**
     * SAGA COMPENSATION — refund the debit made with this reference.
     * Idempotent, and a no-op when no such debit exists.
     */
    @PutMapping("/{accountNumber}/reverse")
    public ResponseEntity<String> reverseDebit(
            @PathVariable String accountNumber,
            @RequestParam String reference) {
        Optional<AccountOperation> reversal = accountService.reverseDebit(accountNumber, reference);
        return ResponseEntity.ok(reversal.isPresent()
                ? "Debit reversed successfully"
                : "No debit found for reference - nothing to reverse");
    }
}
