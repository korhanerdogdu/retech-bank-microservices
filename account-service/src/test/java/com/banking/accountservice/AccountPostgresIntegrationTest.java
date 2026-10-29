package com.banking.accountservice;

import com.banking.accountservice.model.Account;
import com.banking.accountservice.model.AccountOperation;
import com.banking.accountservice.model.AccountStatus;
import com.banking.accountservice.model.AccountType;
import com.banking.accountservice.model.OperationType;
import com.banking.accountservice.repository.AccountOperationRepository;
import com.banking.accountservice.repository.AccountRepository;
import com.banking.accountservice.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a real PostgreSQL (Testcontainers). Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.jpa.show-sql=false"
})
class AccountPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    AccountService accountService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    AccountOperationRepository operationRepository;

    private String accountNumber;

    @BeforeEach
    void createAccount() {
        Account account = new Account();
        accountNumber = String.format("%012d", System.nanoTime() % 1_000_000_000_000L);
        account.setAccountNumber(accountNumber);
        account.setAccountHolderName("Test Holder");
        account.setEmail(accountNumber + "@example.com");
        account.setPhone("5550000000");
        account.setAccountType(AccountType.SAVINGS);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(new BigDecimal("1000.00"));
        account.setDailyTransactionLimit(new BigDecimal("100000"));
        accountRepository.save(account);
    }

    @Test
    void moneyColumnsKeepTwoDecimalPrecision() {
        accountService.deductBalance(accountNumber, new BigDecimal("0.01"), "precision");

        assertThat(accountService.getBalance(accountNumber)).isEqualByComparingTo("999.99");
    }

    @Test
    void concurrentDeductsNeverOverdraw() throws Exception {
        // 20 parallel transfers of 100 against a balance of 1000: exactly 10 may succeed
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String reference = "concurrent-" + accountNumber + "-" + i;
            tasks.add(() -> {
                try {
                    accountService.deductBalance(accountNumber, new BigDecimal("100.00"), reference);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
        }
        long succeeded = 0;
        for (Future<Boolean> f : pool.invokeAll(tasks)) {
            if (f.get()) succeeded++;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(10);
        assertThat(accountService.getBalance(accountNumber)).isEqualByComparingTo("0.00");
    }

    @Test
    void concurrentDuplicateDeductsMoveMoneyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Object>> tasks = new ArrayList<>();
        String reference = "dup-" + accountNumber;
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> accountService.deductBalance(accountNumber, new BigDecimal("300.00"), reference));
        }
        for (Future<Object> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();

        assertThat(accountService.getBalance(accountNumber)).isEqualByComparingTo("700.00");
    }

    @Test
    void ledgerRejectsDuplicateOperationKeys() {
        accountService.deductBalance(accountNumber, BigDecimal.TEN, "unique-" + accountNumber);

        AccountOperation duplicate = new AccountOperation();
        duplicate.setOperationKey(OperationType.DEBIT.keyFor("unique-" + accountNumber));
        duplicate.setAccountNumber(accountNumber);
        duplicate.setType(OperationType.DEBIT);
        duplicate.setAmount(BigDecimal.TEN);
        duplicate.setBalanceBefore(BigDecimal.ZERO);
        duplicate.setBalanceAfter(BigDecimal.ZERO);

        assertThatThrownBy(() -> operationRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reverseAfterDebitRestoresBalanceOnce() {
        String reference = "rev-" + accountNumber;
        accountService.deductBalance(accountNumber, new BigDecimal("400.00"), reference);
        accountService.reverseDebit(accountNumber, reference);
        accountService.reverseDebit(accountNumber, reference);

        assertThat(accountService.getBalance(accountNumber)).isEqualByComparingTo("1000.00");
    }
}
