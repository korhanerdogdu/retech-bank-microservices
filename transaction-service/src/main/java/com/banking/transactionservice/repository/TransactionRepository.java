package com.banking.transactionservice.repository;

import com.banking.transactionservice.model.Transaction;
import com.banking.transactionservice.model.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findBySenderAccountNumberOrderByCreatedAtDesc(String accountNumber);

    // SAGA recovery queries
    List<Transaction> findByStatusAndCreatedAtBefore(TransactionStatus status, LocalDateTime before);

    List<Transaction> findByStatusAndVerificationExpiresAtBefore(TransactionStatus status, LocalDateTime before);

    List<Transaction> findByStatus(TransactionStatus status);

    List<Transaction> findByStatusAndCompletionEventPublishedFalseAndCompletedAtBefore(
            TransactionStatus status, LocalDateTime before);
}
