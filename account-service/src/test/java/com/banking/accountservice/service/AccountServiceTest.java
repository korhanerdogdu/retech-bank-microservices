package com.banking.accountservice.service;

import com.banking.accountservice.exception.AccountException;
import com.banking.accountservice.iban.Iban;
import com.banking.accountservice.model.Account;
import com.banking.accountservice.model.AccountOperation;
import com.banking.accountservice.model.AccountStatus;
import com.banking.accountservice.model.OperationType;
import com.banking.accountservice.repository.AccountOperationRepository;
import com.banking.accountservice.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private static final String SENDER = "000000000001";

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountOperationRepository operationRepository = mock(AccountOperationRepository.class);
    private final Map<String, AccountOperation> ledger = new HashMap<>();
    private AccountService accountService;
    private Account sender;

    @BeforeEach
    void setUp() {
        sender = new Account();
        sender.setAccountNumber(SENDER);
        sender.setStatus(AccountStatus.ACTIVE);
        sender.setBalance(new BigDecimal("1000.00"));

        when(accountRepository.findByAccountNumberForUpdate(SENDER)).thenReturn(Optional.of(sender));
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        when(operationRepository.findByOperationKey(anyString()))
                .thenAnswer(i -> Optional.ofNullable(ledger.get(i.<String>getArgument(0))));
        when(operationRepository.save(any(AccountOperation.class))).thenAnswer(i -> {
            AccountOperation op = i.getArgument(0);
            ledger.put(op.getOperationKey(), op);
            return op;
        });

        accountService = new AccountService(accountRepository, operationRepository);
    }

    @Test
    void deductRecordsBalanceBeforeAndAfter() {
        AccountOperation op = accountService.deductBalance(SENDER, new BigDecimal("250.00"), "tx-1");

        assertThat(sender.getBalance()).isEqualByComparingTo("750.00");
        assertThat(op.getBalanceBefore()).isEqualByComparingTo("1000.00");
        assertThat(op.getBalanceAfter()).isEqualByComparingTo("750.00");
        assertThat(op.getOperationKey()).isEqualTo("DEBIT:tx-1");
    }

    @Test
    void duplicateDeductWithSameReferenceDeductsOnce() {
        accountService.deductBalance(SENDER, new BigDecimal("250.00"), "tx-1");
        AccountOperation replay = accountService.deductBalance(SENDER, new BigDecimal("250.00"), "tx-1");

        assertThat(sender.getBalance()).isEqualByComparingTo("750.00");
        assertThat(replay.getBalanceBefore()).isEqualByComparingTo("1000.00");
    }

    @Test
    void reusingReferenceForDifferentAmountIsRejected() {
        accountService.deductBalance(SENDER, new BigDecimal("250.00"), "tx-1");

        assertThatThrownBy(() -> accountService.deductBalance(SENDER, new BigDecimal("10.00"), "tx-1"))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sender.getBalance()).isEqualByComparingTo("750.00");
    }

    @Test
    void insufficientBalanceIsRejectedWithoutChanges() {
        assertThatThrownBy(() -> accountService.deductBalance(SENDER, new BigDecimal("1000.01"), "tx-1"))
                .isInstanceOf(AccountException.class)
                .hasMessage("Insufficient balance");
        assertThat(sender.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(ledger).isEmpty();
    }

    @Test
    void blockedAccountCannotBeDebited() {
        sender.setStatus(AccountStatus.BLOCKED);

        assertThatThrownBy(() -> accountService.deductBalance(SENDER, BigDecimal.TEN, "tx-1"))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void reverseRefundsTheDebitExactlyOnce() {
        accountService.deductBalance(SENDER, new BigDecimal("250.00"), "tx-1");

        assertThat(accountService.reverseDebit(SENDER, "tx-1")).isPresent();
        assertThat(accountService.reverseDebit(SENDER, "tx-1")).isPresent();

        assertThat(sender.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(ledger).containsKeys("DEBIT:tx-1", "REVERSAL:tx-1").hasSize(2);
    }

    @Test
    void reverseRefundsBlockedAccounts() {
        accountService.deductBalance(SENDER, new BigDecimal("250.00"), "tx-1");
        sender.setStatus(AccountStatus.BLOCKED);

        accountService.reverseDebit(SENDER, "tx-1");

        assertThat(sender.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void reverseWithoutDebitCreditsNothingAndBlocksALateDeduct() {
        assertThat(accountService.reverseDebit(SENDER, "tx-1")).isEmpty();
        assertThat(sender.getBalance()).isEqualByComparingTo("1000.00");

        assertThatThrownBy(() -> accountService.deductBalance(SENDER, BigDecimal.TEN, "tx-1"))
                .isInstanceOf(AccountException.class)
                .hasMessageContaining("already cancelled");
        assertThat(sender.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void creditWithReferenceIsIdempotent() {
        accountService.creditBalance(SENDER, new BigDecimal("100.00"), "tx-9");
        accountService.creditBalance(SENDER, new BigDecimal("100.00"), "tx-9");

        assertThat(sender.getBalance()).isEqualByComparingTo("1100.00");
        assertThat(ledger).containsOnlyKeys(OperationType.CREDIT.keyFor("tx-9"));
    }

    @Test
    void creditWithoutReferenceKeepsLegacyBehaviour() {
        accountService.creditBalance(SENDER, new BigDecimal("100.00"), null);
        accountService.creditBalance(SENDER, new BigDecimal("100.00"), null);

        assertThat(sender.getBalance()).isEqualByComparingTo("1200.00");
    }

    @Test
    void ownerCanReadTheirAccountButOthersSeeNotFound() {
        sender.setOwnerUserId("user-1");
        when(accountRepository.findByAccountNumber(SENDER)).thenReturn(Optional.of(sender));

        assertThat(accountService.getBalanceForUser(SENDER, "user-1")).isEqualByComparingTo("1000.00");
        assertThatThrownBy(() -> accountService.getAccountForUser(SENDER, "user-2"))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ibanLookupRevealsOnlyAnAbbreviatedName() {
        String iban = accountService.ibanFor(SENDER);
        sender.setIban(iban);
        sender.setAccountHolderName("Bob  Van Kampen");
        when(accountRepository.findByIban(iban)).thenReturn(Optional.of(sender));

        // spaces and lower case are accepted, as people type or paste them
        var recipient = accountService.lookupByIban(Iban.format(iban).toLowerCase());

        assertThat(recipient.getDisplayName()).isEqualTo("Bob K.");
        assertThat(recipient.getAccountNumber()).isEqualTo(SENDER);
        assertThat(AccountService.abbreviate("Cher")).isEqualTo("Cher");
    }

    @Test
    void ibanLookupRejectsTyposOtherBanksAndUnknownAccounts() {
        String iban = accountService.ibanFor(SENDER);
        String typo = iban.substring(0, 25) + (iban.charAt(25) == '9' ? '0' : '9');

        assertThatThrownBy(() -> accountService.lookupByIban(typo))
                .extracting(e -> ((AccountException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> accountService.lookupByIban("TR330006100519786457841326"))
                .extracting(e -> ((AccountException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        when(accountRepository.findByIban(iban)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> accountService.lookupByIban(iban))
                .extracting(e -> ((AccountException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void existingAccountsGetTheirIbanOnce() {
        sender.setIban(null);
        when(accountRepository.findByIbanIsNull()).thenReturn(java.util.List.of(sender));

        assertThat(accountService.assignMissingIbans()).isEqualTo(1);
        assertThat(sender.getIban()).isEqualTo(accountService.ibanFor(SENDER)).startsWith("TR");
    }

    @Test
    void unknownAccountIsNotFound() {
        assertThatThrownBy(() -> accountService.deductBalance("999", BigDecimal.ONE, "tx"))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
