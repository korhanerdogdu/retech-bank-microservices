package com.banking.accountservice.repository;

import com.banking.accountservice.model.AccountOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountOperationRepository extends JpaRepository<AccountOperation, String> {
    Optional<AccountOperation> findByOperationKey(String operationKey);
}
