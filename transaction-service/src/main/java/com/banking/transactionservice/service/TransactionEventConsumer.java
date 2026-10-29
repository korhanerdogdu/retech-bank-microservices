package com.banking.transactionservice.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Fraud verdicts → SAGA transitions. Handlers are idempotent (state checks +
 * optimistic locking), so exceptions are rethrown and the Kafka error handler
 * retries safely before dead-lettering.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final TransactionService transactionService;

    // Decision REVIEW
    @KafkaListener(topics = "verification.required")
    public void consumeVerificationRequired(
            @Payload Map<String, Object> payload){

        String transactionId =  (String) payload.get("transactionId");
        String reason =  (String) payload.get("reason");

        log.info("Verification required - transaction: {} reason: {}",
                transactionId, reason);

        transactionService.requireVerification(transactionId, reason, payload.get("amount"));
    }

    // Decision ALLOW
    @KafkaListener(topics = "fraud.check.clean")
    public void consumeFraudCheckCleanResult(
            @Payload Map<String, Object> payload){

        String transactionId =  (String) payload.get("transactionId");
        transactionService.processCleanResult(transactionId);
    }

    // Decision BLOCK
    @KafkaListener(topics = "fraud.check.blocked")
    public void consumeFraudCheckBlockedResult(
            @Payload Map<String, Object> payload){

        String transactionId =  (String) payload.get("transactionId");
        String reason =  (String) payload.get("reason");

        log.warn("Fraud check BLOCKED transaction: {} reason: {}", transactionId, reason);
        transactionService.processBlockedResult(transactionId, reason);
    }
}
