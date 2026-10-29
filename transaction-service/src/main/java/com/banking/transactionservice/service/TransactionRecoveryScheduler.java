package com.banking.transactionservice.service;

import com.banking.transactionservice.model.Transaction;
import com.banking.transactionservice.model.TransactionStatus;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drives interrupted SAGAs to a final state. Each step it retries is
 * idempotent, and optimistic locking makes it safe alongside live requests
 * and other service instances.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TransactionRecoveryScheduler {

    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;

    @Value("${transaction.recovery.pending-timeout-seconds:120}")
    private long pendingTimeoutSeconds = 120;

    @Value("${transaction.recovery.processing-timeout-seconds:300}")
    private long processingTimeoutSeconds = 300;

    @Value("${transaction.recovery.publish-retry-seconds:60}")
    private long publishRetrySeconds = 60;

    @Scheduled(fixedDelayString = "${transaction.recovery.interval-ms:30000}",
            initialDelayString = "${transaction.recovery.interval-ms:30000}")
    public void recover() {
        LocalDateTime now = LocalDateTime.now();

        // OTP never submitted — cancel and refund without waiting for the customer
        each("expire verification",
                transactionRepository.findByStatusAndVerificationExpiresAtBefore(
                        TransactionStatus.PENDING_VERIFICATION, now),
                transactionService::expireVerification);

        // Refund started but did not finish
        each("finish compensation",
                transactionRepository.findByStatus(TransactionStatus.COMPENSATING),
                transactionService::finishCompensation);

        // Debit outcome unknown (crash / timeout between saving and deducting)
        each("recover pending",
                transactionRepository.findByStatusAndCreatedAtBefore(
                        TransactionStatus.PENDING, now.minusSeconds(pendingTimeoutSeconds)),
                transactionService::recoverPending);

        // Fraud verdict never arrived
        each("republish initiated",
                transactionRepository.findByStatusAndCreatedAtBefore(
                        TransactionStatus.PROCESSING, now.minusSeconds(processingTimeoutSeconds)),
                transactionService::republishInitiated);

        // transaction.completed was not acknowledged — receiver credit is idempotent
        each("republish completion",
                transactionRepository.findByStatusAndCompletionEventPublishedFalseAndCompletedAtBefore(
                        TransactionStatus.COMPLETED, now.minusSeconds(publishRetrySeconds)),
                transactionService::publishCompletion);
    }

    private void each(String step, List<Transaction> transactions, Consumer<Transaction> action) {
        for (Transaction transaction : transactions) {
            try {
                action.accept(transaction);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.info("Recovery '{}' skipped {} - updated concurrently", step, transaction.getId());
            } catch (RuntimeException e) {
                log.error("Recovery '{}' failed for {} - will retry: {}", step, transaction.getId(), e.getMessage());
            }
        }
    }
}
