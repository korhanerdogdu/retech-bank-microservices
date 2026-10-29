package com.banking.transactionservice;

import com.banking.transactionservice.model.Transaction;
import com.banking.transactionservice.model.TransactionStatus;
import com.banking.transactionservice.model.TransactionType;
import com.banking.transactionservice.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence behaviour on a real PostgreSQL (Testcontainers). Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransactionPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TransactionRepository repository;

    @Test
    void sagaColumnsRoundTrip() {
        Transaction tx = newTransaction();
        tx.setStatus(TransactionStatus.COMPENSATING);
        tx.setSenderBalanceBefore(new BigDecimal("1234.56"));
        tx.setVerificationExpiresAt(LocalDateTime.now().plusMinutes(5));
        tx.setCompletionEventPublished(false);
        Transaction saved = repository.save(tx);

        Transaction loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(TransactionStatus.COMPENSATING);
        assertThat(loaded.getSenderBalanceBefore()).isEqualByComparingTo("1234.56");
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void staleUpdateLosesToConcurrentWinner() {
        Transaction saved = repository.save(newTransaction());

        Transaction first = repository.findById(saved.getId()).orElseThrow();
        Transaction second = repository.findById(saved.getId()).orElseThrow();

        first.setStatus(TransactionStatus.COMPLETED);
        repository.save(first);

        // e.g. a wrong-OTP request racing a correct one: it must not also refund
        second.setStatus(TransactionStatus.COMPENSATING);
        assertThatThrownBy(() -> repository.save(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(repository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void recoveryQueriesFindStuckTransactions() {
        Transaction expired = newTransaction();
        expired.setStatus(TransactionStatus.PENDING_VERIFICATION);
        expired.setVerificationExpiresAt(LocalDateTime.now().minusMinutes(1));
        repository.save(expired);

        assertThat(repository.findByStatusAndVerificationExpiresAtBefore(
                TransactionStatus.PENDING_VERIFICATION, LocalDateTime.now()))
                .extracting(Transaction::getId).contains(expired.getId());
    }

    private static Transaction newTransaction() {
        Transaction tx = new Transaction();
        tx.setSenderAccountNumber("111111111111");
        tx.setReceiverAccountNumber("222222222222");
        tx.setAmount(new BigDecimal("100.00"));
        tx.setType(TransactionType.TRANSFER);
        tx.setStatus(TransactionStatus.PROCESSING);
        return tx;
    }
}
