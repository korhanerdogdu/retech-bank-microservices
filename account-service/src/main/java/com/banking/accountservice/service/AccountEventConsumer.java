package com.banking.accountservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Kafka listeners live outside AccountService so calls go through the
 * Spring proxy and run inside AccountService's @Transactional methods.
 * Exceptions are rethrown: the error handler retries and finally sends the
 * record to "<topic>.DLT" instead of silently dropping a credit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEventConsumer {

    private final AccountService accountService;

    @KafkaListener(topics = "transaction.completed")
    public void consumeTransactionCompleted(
            @Payload Map<String, Object> payload) {
        String transactionId = (String) payload.get("transactionId");
        String receiverAccount = (String) payload.get("receiverAccountNumber");
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());

        log.info("Crediting account: {} amount: {} transaction: {}", receiverAccount, amount, transactionId);
        // transactionId is the idempotency key — a redelivered event credits once
        accountService.creditBalance(receiverAccount, amount, transactionId);
    }


    @KafkaListener(topics = "fraud.detected")
    public void consumeFraudDetected(
            @Payload Map<String, Object> payload) {
        String accountNumber = (String) payload.get("accountNumber");
        log.info("Fraud detected — blocking account: {}", accountNumber);
        accountService.blockAccount(accountNumber);
    }
}
