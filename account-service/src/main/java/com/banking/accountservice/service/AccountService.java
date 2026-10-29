package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.dto.RecipientResponse;
import com.banking.accountservice.exception.AccountException;
import com.banking.accountservice.iban.Iban;
import com.banking.accountservice.model.Account;
import com.banking.accountservice.model.AccountOperation;
import com.banking.accountservice.model.AccountStatus;
import com.banking.accountservice.model.AccountType;
import com.banking.accountservice.model.OperationType;
import com.banking.accountservice.repository.AccountOperationRepository;
import com.banking.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountOperationRepository operationRepository;
    private static SecureRandom secureRandom = new SecureRandom();

    @Value("${bank.iban.country-code:TR}")
    private String ibanCountryCode = "TR";

    // Retech Bank's (fictional) 5-digit bank code inside its IBANs
    @Value("${bank.iban.bank-code:00999}")
    private String ibanBankCode = "00999";


    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        log.info("Creating account for: {}", request.getEmail());

        if (accountRepository.existsByEmail(request.getEmail())) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "Account already exists for email: " + request.getEmail());
        }

        Account account = new Account();
        account.setAccountHolderName(request.getAccountHolderName());
        account.setEmail(request.getEmail());
        account.setPhone(request.getPhone());
        account.setAccountType(request.getAccountType());
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(request.getInitialDeposit());
        account.setAccountNumber(generateAccountNumber());
        account.setIban(ibanFor(account.getAccountNumber()));
        account.setOwnerUserId(request.getOwnerUserId());
        account.setDailyTransactionLimit(
                request.getAccountType() == AccountType.SAVINGS
                        ? new BigDecimal("100000")
                        : new BigDecimal("500000")
        );

        Account saved = accountRepository.save(account);
        log.info("Account created: {}", saved.getAccountNumber());
        return mapToResponse(saved);
    }


    public AccountResponse getAccount(String accountNumber) {
        Account account = findByAccountNumber(accountNumber);
        return mapToResponse(account);
    }


    public BigDecimal getBalance(String accountNumber) {
        return findByAccountNumber(accountNumber).getBalance();
    }

    /**
     * Account details for a request that came through the API Gateway on behalf
     * of a logged-in user. Someone else's account looks exactly like a missing
     * one, so account numbers can't be probed for balances.
     */
    public AccountResponse getAccountForUser(String accountNumber, String userId) {
        return mapToResponse(findOwnedAccount(accountNumber, userId));
    }

    public BigDecimal getBalanceForUser(String accountNumber, String userId) {
        return findOwnedAccount(accountNumber, userId).getBalance();
    }

    /**
     * Recipient check before a transfer, by IBAN. Rejects typos (checksum),
     * IBANs of other banks and unknown accounts with distinct messages; reveals
     * only an abbreviated name.
     */
    public RecipientResponse lookupByIban(String rawIban) {
        String iban = Iban.normalize(rawIban);
        if (!Iban.isValid(iban)) {
            throw new AccountException(HttpStatus.BAD_REQUEST,
                    "That isn't a valid IBAN. Check it for typos.");
        }
        if (!iban.startsWith(ibanCountryCode) || !Iban.bankCode(iban).equals(ibanBankCode)) {
            throw new AccountException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Retech Bank can only send money to other Retech Bank accounts for now.");
        }
        Account account = accountRepository.findByIban(iban)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                        "No Retech Bank account has this IBAN."));
        return new RecipientResponse(account.getIban(), account.getAccountNumber(),
                abbreviate(account.getAccountHolderName()),
                account.getStatus() != AccountStatus.CLOSED);
    }

    /** Gives accounts created before IBANs existed their IBAN. Idempotent. */
    @Transactional
    public int assignMissingIbans() {
        List<Account> missing = accountRepository.findByIbanIsNull();
        missing.forEach(account -> account.setIban(ibanFor(account.getAccountNumber())));
        accountRepository.saveAll(missing);
        return missing.size();
    }

    String ibanFor(String accountNumber) {
        return Iban.forAccount(ibanCountryCode, ibanBankCode, accountNumber);
    }

    private Account findOwnedAccount(String accountNumber, String userId) {
        Account account = findByAccountNumber(accountNumber);
        if (userId == null || !userId.equals(account.getOwnerUserId())) {
            throw AccountException.notFound(accountNumber);
        }
        return account;
    }

    static String abbreviate(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }


    @Transactional
    public void blockAccount(String accountNumber) {
        log.info("Blocking account: {}", accountNumber);
        Account account = lockAccount(accountNumber);
        account.setStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
        log.info("Account blocked: {}", accountNumber);
    }


    /**
     * SAGA STEP 1 — deduct from the sender.
     * With a reference (the transaction id) the call is idempotent:
     * a retry returns the original operation instead of deducting again.
     */
    @Transactional
    public AccountOperation deductBalance(String accountNumber, BigDecimal amount, String reference) {
        requirePositive(amount);
        log.info("Deducting {} from account: {} reference: {}", amount, accountNumber, reference);
        Account account = lockAccount(accountNumber);

        Optional<AccountOperation> replay = findReplay(OperationType.DEBIT, reference, accountNumber, amount);
        if (replay.isPresent()) {
            log.info("Duplicate deduct ignored - reference: {}", reference);
            return replay.get();
        }

        // A late deduct must not succeed after the transfer was already cancelled
        if (reference != null && !reference.isBlank()
                && operationRepository.findByOperationKey(OperationType.REVERSAL.keyFor(reference)).isPresent()) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "Transfer " + reference + " was already cancelled");
        }

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Account is not active: " + accountNumber);
        }

        if (account.getBalance().compareTo(amount) < 0) {
            throw new AccountException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient balance");
        }

        BigDecimal before = account.getBalance();
        account.setBalance(before.subtract(amount));
        accountRepository.save(account);
        log.info("Balance deducted. New balance: {}", account.getBalance());
        return recordOperation(account, OperationType.DEBIT, reference, amount, before);
    }


    @Transactional
    public AccountOperation creditBalance(String accountNumber, BigDecimal amount, String reference) {
        requirePositive(amount);
        log.info("Crediting {} to account: {} reference: {}", amount, accountNumber, reference);
        Account account = lockAccount(accountNumber);

        Optional<AccountOperation> replay = findReplay(OperationType.CREDIT, reference, accountNumber, amount);
        if (replay.isPresent()) {
            log.info("Duplicate credit ignored - reference: {}", reference);
            return replay.get();
        }

        BigDecimal before = account.getBalance();
        account.setBalance(before.add(amount));
        accountRepository.save(account);
        log.info("Balance credited. New balance: {}", account.getBalance());
        return recordOperation(account, OperationType.CREDIT, reference, amount, before);
    }


    /**
     * SAGA COMPENSATION — returns exactly the amount taken by the debit with
     * this reference. Safe to call repeatedly, and safe to call when the debit
     * never happened (nothing is credited), so callers can always use it when
     * the outcome of a deduct is unknown.
     */
    @Transactional
    public Optional<AccountOperation> reverseDebit(String accountNumber, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "reference is required");
        }
        Account account = lockAccount(accountNumber);

        Optional<AccountOperation> existingReversal =
                operationRepository.findByOperationKey(OperationType.REVERSAL.keyFor(reference));
        if (existingReversal.isPresent()) {
            log.info("Debit already reversed - reference: {}", reference);
            return existingReversal.filter(op -> op.getAmount().signum() > 0);
        }

        Optional<AccountOperation> debit =
                operationRepository.findByOperationKey(OperationType.DEBIT.keyFor(reference));
        if (debit.isEmpty()) {
            // Tombstone: records the cancellation so a delayed deduct is refused
            log.info("No debit to reverse - reference: {}", reference);
            recordOperation(account, OperationType.REVERSAL, reference, BigDecimal.ZERO, account.getBalance());
            return Optional.empty();
        }
        if (!debit.get().getAccountNumber().equals(accountNumber)) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "Reference " + reference + " belongs to a different account");
        }

        BigDecimal amount = debit.get().getAmount();
        BigDecimal before = account.getBalance();
        account.setBalance(before.add(amount));
        accountRepository.save(account);
        log.info("Debit reversed - reference: {} refunded: {} new balance: {}",
                reference, amount, account.getBalance());
        return Optional.of(recordOperation(account, OperationType.REVERSAL, reference, amount, before));
    }


    private Optional<AccountOperation> findReplay(OperationType type, String reference,
                                                  String accountNumber, BigDecimal amount) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        Optional<AccountOperation> existing = operationRepository.findByOperationKey(type.keyFor(reference));
        existing.ifPresent(op -> {
            if (!op.getAccountNumber().equals(accountNumber) || op.getAmount().compareTo(amount) != 0) {
                throw new AccountException(HttpStatus.CONFLICT,
                        "Reference " + reference + " was already used for a different " + type);
            }
        });
        return existing;
    }

    private AccountOperation recordOperation(Account account, OperationType type, String reference,
                                             BigDecimal amount, BigDecimal before) {
        String key = (reference == null || reference.isBlank())
                ? type.keyFor("untracked-" + UUID.randomUUID())
                : type.keyFor(reference);

        AccountOperation operation = new AccountOperation();
        operation.setOperationKey(key);
        operation.setAccountNumber(account.getAccountNumber());
        operation.setType(type);
        operation.setAmount(amount);
        operation.setBalanceBefore(before);
        operation.setBalanceAfter(account.getBalance());
        return operationRepository.save(operation);
    }

    private void requirePositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }
    }


    private String generateAccountNumber() {
        String accountNumber;

        do {
            long number = secureRandom.nextLong(1_000_000_000_000L);

            accountNumber = String.format("%012d", number);

        } while (accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }

    private Account findByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> AccountException.notFound(accountNumber));
    }

    private Account lockAccount(String accountNumber) {
        return accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> AccountException.notFound(accountNumber));
    }

    private AccountResponse mapToResponse(Account account) {
        AccountResponse response = new AccountResponse();
        response.setId(account.getId());
        response.setAccountNumber(account.getAccountNumber());
        response.setIban(account.getIban());
        response.setAccountHolderName(account.getAccountHolderName());
        response.setEmail(account.getEmail());
        response.setPhone(account.getPhone());
        response.setAccountType(account.getAccountType());
        response.setStatus(account.getStatus());
        response.setBalance(account.getBalance());
        response.setDailyTransactionLimit(account.getDailyTransactionLimit());
        response.setCreatedAt(account.getCreatedAt());
        response.setOwnerUserId(account.getOwnerUserId());
        return response;
    }
}
