package com.banking.transactionservice.service;

import com.banking.transactionservice.model.Transaction;
import com.banking.transactionservice.model.TransactionStatus;
import com.banking.transactionservice.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TransactionRecoverySchedulerTest {

    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionService service = mock(TransactionService.class);
    private final TransactionRecoveryScheduler scheduler = new TransactionRecoveryScheduler(repository, service);

    @Test
    void expiresUnansweredOtpVerifications() {
        Transaction expired = tx("a");
        when(repository.findByStatusAndVerificationExpiresAtBefore(eq(TransactionStatus.PENDING_VERIFICATION), any()))
                .thenReturn(List.of(expired));

        scheduler.recover();

        verify(service).expireVerification(expired);
    }

    @Test
    void drivesEveryInterruptedStateForward() {
        Transaction compensating = tx("c");
        Transaction pending = tx("p");
        Transaction processing = tx("r");
        Transaction unpublished = tx("u");
        when(repository.findByStatus(TransactionStatus.COMPENSATING)).thenReturn(List.of(compensating));
        when(repository.findByStatusAndCreatedAtBefore(eq(TransactionStatus.PENDING), any())).thenReturn(List.of(pending));
        when(repository.findByStatusAndCreatedAtBefore(eq(TransactionStatus.PROCESSING), any())).thenReturn(List.of(processing));
        when(repository.findByStatusAndCompletionEventPublishedFalseAndCompletedAtBefore(eq(TransactionStatus.COMPLETED), any()))
                .thenReturn(List.of(unpublished));

        scheduler.recover();

        verify(service).finishCompensation(compensating);
        verify(service).recoverPending(pending);
        verify(service).republishInitiated(processing);
        verify(service).publishCompletion(unpublished);
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        Transaction first = tx("1");
        Transaction second = tx("2");
        when(repository.findByStatus(TransactionStatus.COMPENSATING)).thenReturn(List.of(first, second));
        doThrow(new ObjectOptimisticLockingFailureException(Transaction.class, "1"))
                .when(service).finishCompensation(first);

        scheduler.recover();

        verify(service).finishCompensation(second);
    }

    private static Transaction tx(String id) {
        Transaction tx = new Transaction();
        tx.setId(id);
        return tx;
    }
}
