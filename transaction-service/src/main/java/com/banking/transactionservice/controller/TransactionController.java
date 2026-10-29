package com.banking.transactionservice.controller;

import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Requests from the API Gateway carry X-User-Id (from the validated JWT);
 * every operation is then limited to the user's own accounts and transfers.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
public class TransactionController {

    public static final String USER_ID_HEADER = "X-User-Id";

    private final TransactionService transactionService;

    // Transfer money between accounts
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.transfer(request, userId));
    }

    // Get transaction by ID
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable String transactionId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return ResponseEntity.ok(
                transactionService.getTransaction(transactionId, userId));
    }

    // Get transaction history for account
    @GetMapping("/account/{accountNumber}")
    public ResponseEntity<List<TransactionResponse>> getHistory(
            @PathVariable String accountNumber,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return ResponseEntity.ok(
                transactionService.getTransactionHistory(accountNumber, userId));
    }


    @PostMapping("/{transactionId}/verify")
    public ResponseEntity<TransactionResponse> verifyTransaction(
            @PathVariable String transactionId,
            @RequestParam String otp,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        log.info("OTP verification request — transaction: {}",
                transactionId);
        return ResponseEntity.ok(
                transactionService.verifyOTP(transactionId, otp, userId));
    }

    // "This wasn't me" — cancel, refund and block instead of entering the OTP
    @PostMapping("/{transactionId}/report-fraud")
    public ResponseEntity<TransactionResponse> reportFraud(
            @PathVariable String transactionId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return ResponseEntity.ok(
                transactionService.reportFraud(transactionId, userId));
    }
}
