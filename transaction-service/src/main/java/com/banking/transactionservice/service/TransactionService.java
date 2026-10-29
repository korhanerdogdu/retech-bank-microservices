package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.event.TransactionCompletedEvent;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.exception.ForbiddenException;
import com.banking.transactionservice.exception.TransactionNotFoundException;
import com.banking.transactionservice.exception.TransferFailedException;
import com.banking.transactionservice.exception.TransferRejectedException;
import com.banking.transactionservice.model.Transaction;
import com.banking.transactionservice.model.TransactionStatus;
import com.banking.transactionservice.model.TransactionType;
import com.banking.transactionservice.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Orchestrates the transfer SAGA.
 *
 * Every state change is saved (with optimistic locking) BEFORE its side
 * effects run, and every side effect is idempotent, so a crash, retry or
 * duplicate event at any point can be resumed by {@link TransactionRecoveryScheduler}
 * without moving money twice:
 *
 *   PENDING ─deduct─▶ PROCESSING ─clean─▶ COMPLETED
 *      │                  ├─review─▶ PENDING_VERIFICATION ─otp ok─▶ COMPLETED
 *      ▼                  │                     └─expired/wrong─▶ COMPENSATING ─refund─▶ FLAGGED
 *    FAILED               └─block──────────────────────────────▶ COMPENSATING ─refund─▶ FLAGGED
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final OtpGenerator otpGenerator;
    private final ObjectMapper objectMapper;

    @Value("${transaction.otp-expiry-minutes:5}")
    private long otpExpiryMinutes = 5;

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";
    private static final String TRANSACTION_OTP_GENERATED_TOPIC = "transaction.otp.generated";
    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";
    private static final long PUBLISH_TIMEOUT_SECONDS = 10;

    public static String otpKey(String transactionId) {
        return "verification:otp:" + transactionId;
    }


    public TransactionResponse transfer(TransferRequest request){
        return transfer(request, null);
    }

    /**
     * @param userId logged-in user from the API Gateway (X-User-Id), or null for internal calls.
     *               When set, the sender account must belong to this user.
     */
    public TransactionResponse transfer(TransferRequest request, String userId){

        if (userId != null) {
            ensureOwnsAccount(request.getSenderAccountNumber(), userId,
                    "You can only send money from your own account");
        }

        // Customers send to an IBAN; the SAGA itself keeps working with account numbers
        String receiverIban = null;
        if (hasText(request.getReceiverIban())) {
            AccountServiceClient.Recipient recipient = resolveIban(request.getReceiverIban());
            request.setReceiverAccountNumber(recipient.accountNumber());
            receiverIban = recipient.iban();
        } else if (hasText(request.getReceiverAccountNumber())) {
            // Fail fast before any money moves — a credit to a missing account could never succeed
            ensureAccountExists(request.getReceiverAccountNumber(), "Receiver");
        } else {
            throw new TransferRejectedException("Enter the recipient's IBAN");
        }

        log.info("SAGA START - Transfer: {} -> {} amount: {}",
                request.getSenderAccountNumber(),
                request.getReceiverAccountNumber(),
                request.getAmount());

        if (request.getSenderAccountNumber().equals(request.getReceiverAccountNumber())) {
            throw new TransferRejectedException("Choose a different account to send money to");
        }

        // Record the transfer FIRST so a debit can never exist without a transaction row
        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setReceiverIban(receiverIban);
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());
        transaction.setInitiatedByUserId(userId);

        transaction = transactionRepository.save(transaction);
        log.info("Transaction saved as PENDING: {}", transaction.getId());

        // SAGA STEP 1: Deduct from sender (idempotent — the transaction id is the reference)
        BigDecimal balanceBefore;
        try {
            ResponseEntity<String> response = accountServiceClient.deductBalance(
                    transaction.getSenderAccountNumber(),
                    transaction.getAmount(),
                    transaction.getId());
            balanceBefore = parseBalanceBefore(response);
        } catch (FeignException e) {
            throw deductFailure(transaction, e);
        }

        transaction.setSenderBalanceBefore(balanceBefore);
        transaction.setStatus(TransactionStatus.PROCESSING);
        try {
            transaction = transactionRepository.save(transaction);
        } catch (RuntimeException e) {
            // Money left the sender but the state could not be stored — give it back
            log.error("Could not persist PROCESSING for {} - reversing debit", transaction.getId(), e);
            tryReverse(transaction);
            throw new TransferFailedException(
                    "Transfer could not be recorded - any deducted amount is being returned", e);
        }
        log.info("Transaction PROCESSING: {} balance before: {}", transaction.getId(), balanceBefore);

        // SAGA STEP - 2: Publish for fraud check
        publishInitiated(transaction);

        return mapToResponse(transaction);
    }

    public TransactionResponse getTransaction(String transactionId){
        return getTransaction(transactionId, null);
    }

    public TransactionResponse getTransaction(String transactionId, String userId){
        return mapToResponse(findTransaction(transactionId, userId));
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber){
        return getTransactionHistory(accountNumber, null);
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber, String userId){
        if (userId != null) {
            ensureOwnsAccount(accountNumber, userId, "You can only see transfers from your own account");
        }

        return transactionRepository
                .findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public TransactionResponse verifyOTP(String transactionID, String otp){
        return verifyOTP(transactionID, otp, null);
    }

    public TransactionResponse verifyOTP(String transactionID, String otp, String userId){
        log.info("OTP verification for the transaction: {}", transactionID);

        Transaction transaction = findTransaction(transactionID, userId);

        // Only a transfer that is waiting for an OTP can be completed or refunded here.
        // Without this guard a repeated call could refund an already completed transfer.
        if (transaction.getStatus() != TransactionStatus.PENDING_VERIFICATION) {
            log.warn("Transaction {} is {} - not awaiting verification",
                    transactionID, transaction.getStatus());
            return mapToResponse(transaction);
        }

        String otpKey = otpKey(transactionID);
        String storedOtp = redisTemplate.opsForValue().get(otpKey);

        if(storedOtp == null || isVerificationExpired(transaction)){
            // OTP EXPIRED
            log.warn("OTP expired for transaction: {}", transactionID);
            redisTemplate.delete(otpKey);
            return mapToResponse(compensateTransaction(transaction,
                    "OTP expired - transaction cancelled and amount refunded"));
        }

        if(!constantTimeEquals(storedOtp, otp)){
            // BLOCK ACCOUNT AND REFUND
            log.warn("Wrong OTP - blocking account and refunding: {}", transactionID);
            redisTemplate.delete(otpKey);
            return mapToResponse(blockAccountAndCompensate(transaction,
                    "Wrong OTP entered - transaction cancelled, "+
                            "account blocked for security"));
        }

        // OTP correct - complete transaction
        log.info("OTP verified - completing transaction: {}", transactionID);
        redisTemplate.delete(otpKey);
        return mapToResponse(completeTransaction(transaction));
    }

    /**
     * "This wasn't me" — the customer rejects a transfer that is waiting for an OTP.
     * Treated like a wrong OTP: cancel, refund and block the account.
     */
    public TransactionResponse reportFraud(String transactionID) {
        return reportFraud(transactionID, null);
    }

    public TransactionResponse reportFraud(String transactionID, String userId) {
        log.warn("Customer reported transaction {} as fraud", transactionID);

        Transaction transaction = findTransaction(transactionID, userId);

        if (transaction.getStatus() != TransactionStatus.PENDING_VERIFICATION) {
            log.warn("Transaction {} is {} - not awaiting verification",
                    transactionID, transaction.getStatus());
            return mapToResponse(transaction);
        }

        redisTemplate.delete(otpKey(transactionID));
        return mapToResponse(blockAccountAndCompensate(transaction,
                "Reported by the customer as not authorised - transaction cancelled, "+
                        "account blocked for security"));
    }

    /** fraud returned REVIEW — step-up authentication with an OTP. */
    public void requireVerification(String transactionId, String reason, Object amount) {
        Transaction transaction = findTransaction(transactionId);

        if(transaction.getStatus() != TransactionStatus.PROCESSING){
            log.warn("Transaction {} not PROCESSING - skipping", transactionId);
            return;
        }

        // Claim the state first: a duplicate event fails the version check and never
        // overwrites the OTP the customer already received
        transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
        transaction.setVerificationExpiresAt(LocalDateTime.now().plusMinutes(otpExpiryMinutes));
        transaction = transactionRepository.save(transaction);

        String otp = otpGenerator.generate();
        redisTemplate.opsForValue().set(otpKey(transactionId), otp, otpExpiryMinutes, TimeUnit.MINUTES);

        log.info("OTP generated for transaction: {} expires in {} min",
                transactionId, otpExpiryMinutes);

        // Notify user
        Map<String, Object> otpEvent = new HashMap<>();
        otpEvent.put("transactionId", transactionId);
        otpEvent.put("accountNumber", transaction.getSenderAccountNumber());
        otpEvent.put("reason", reason);
        otpEvent.put("otp", otp);
        otpEvent.put("amount", amount != null ? amount : transaction.getAmount());

        kafkaTemplate.send(TRANSACTION_OTP_GENERATED_TOPIC, transactionId, otpEvent);
    }

    public void processCleanResult(String transactionID){

        Transaction transaction = findTransaction(transactionID);

        if(transaction.getStatus() != TransactionStatus.PROCESSING){
            log.warn("Transaction {} not PROCESSING - skipping", transactionID);
            return;
        }

        completeTransaction(transaction);
    }

    /** fraud returned BLOCK — cancel, refund and block the sender, as for a wrong OTP. */
    public void processBlockedResult(String transactionID, String reason){

        Transaction transaction = findTransaction(transactionID);

        if(transaction.getStatus() != TransactionStatus.PROCESSING){
            log.warn("Transaction {} not PROCESSING - skipping", transactionID);
            return;
        }

        blockAccountAndCompensate(transaction,
                "Blocked by fraud detection: " + reason +
                        " - transaction cancelled, account blocked for security");
    }

    // ---------- SAGA recovery entry points (see TransactionRecoveryScheduler) ----------

    /** PENDING for too long: the debit outcome is unknown, so reverse (no-op if nothing was taken). */
    public void recoverPending(Transaction transaction) {
        log.warn("Recovering PENDING transaction {}", transaction.getId());
        accountServiceClient.reverseDebit(transaction.getSenderAccountNumber(), transaction.getId());
        markFailed(transaction, "Transfer could not be confirmed - cancelled, any deducted amount was returned");
    }

    /** The customer never submitted an OTP before the deadline. */
    public void expireVerification(Transaction transaction) {
        log.warn("OTP verification window passed for {}", transaction.getId());
        redisTemplate.delete(otpKey(transaction.getId()));
        compensateTransaction(transaction, "OTP expired - transaction cancelled and amount refunded");
    }

    /** The fraud verdict never arrived; re-publish (fraud detection replays its stored decision). */
    public void republishInitiated(Transaction transaction) {
        log.warn("Re-publishing transaction.initiated for {}", transaction.getId());
        publishInitiated(transaction);
    }

    /** Refund for a COMPENSATING transaction. Idempotent: the reversal happens at most once. */
    public Transaction finishCompensation(Transaction transaction) {
        log.warn("SAGA COMPENSATION - refunding: {} amount: {}",
                transaction.getSenderAccountNumber(),
                transaction.getAmount());

        accountServiceClient.reverseDebit(
                transaction.getSenderAccountNumber(),
                transaction.getId());

        transaction.setStatus(TransactionStatus.FLAGGED);
        transaction.setFailureReason(transaction.getFailureReason() +
                " - SAGA Compensation executed, amount refunded at "+ LocalDateTime.now());
        transaction = transactionRepository.save(transaction);

        // PUBLISH refund event - Notification service will alert user
        Map<String, Object> refundEvent = new HashMap<>();
        refundEvent.put("transactionId", transaction.getId());
        refundEvent.put("senderAccountNumber", transaction.getSenderAccountNumber());
        refundEvent.put("amount", transaction.getAmount());
        refundEvent.put("reason", transaction.getFailureReason());

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId(), refundEvent);

        log.info("SAGA COMPENSATION COMPLETE - {} refunded to  {}",
                transaction.getAmount(), transaction.getSenderAccountNumber());
        return transaction;
    }

    /** Publishes transaction.completed and records the acknowledgement. */
    public Transaction publishCompletion(Transaction transaction) {
        TransactionCompletedEvent completedEvent = new TransactionCompletedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription()
        );

        try {
            kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC, transaction.getId(), completedEvent)
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted publishing completion of {} - recovery will retry", transaction.getId());
            return transaction;
        } catch (Exception e) {
            log.error("Could not publish completion of {} - recovery will retry: {}",
                    transaction.getId(), e.getMessage());
            return transaction;
        }

        transaction.setCompletionEventPublished(true);
        transaction = transactionRepository.save(transaction);
        log.info("SAGA COMPLETE - Transaction {} completed",
                transaction.getId());
        return transaction;
    }

    // ---------- internals ----------

    private Transaction compensateTransaction(Transaction transaction, String reason) {
        transaction = claimCompensation(transaction, reason);
        return finishCompensationSafely(transaction);
    }

    private Transaction blockAccountAndCompensate(Transaction transaction, String reason){
        transaction = claimCompensation(transaction, reason);

        // Publish fraud.detected -> Account Service will block account
        Map<String, Object> fraudEvent = new HashMap<>();
        fraudEvent.put("transactionId", transaction.getId());
        fraudEvent.put("accountNumber", transaction.getSenderAccountNumber());
        fraudEvent.put("reason", reason);

        kafkaTemplate.send(FRAUD_DETECTED_TOPIC, transaction.getSenderAccountNumber(), fraudEvent);
        log.warn("fraud.detected published - account: {} will be blocked, Kindly contact to the bank",
                transaction.getSenderAccountNumber());

        // SAGA COMPENSATION - refund Sender
        return finishCompensationSafely(transaction);
    }

    // Saves COMPENSATING before refunding; the version check makes this the single winner
    private Transaction claimCompensation(Transaction transaction, String reason) {
        transaction.setStatus(TransactionStatus.COMPENSATING);
        transaction.setFailureReason(reason);
        return transactionRepository.save(transaction);
    }

    private Transaction finishCompensationSafely(Transaction transaction) {
        try {
            return finishCompensation(transaction);
        } catch (FeignException e) {
            log.error("Refund for {} failed - stays COMPENSATING, recovery will retry: {}",
                    transaction.getId(), e.getMessage());
            return transaction;
        }
    }

    private Transaction completeTransaction(Transaction transaction){
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transaction.setCompletionEventPublished(false);
        transaction = transactionRepository.save(transaction);
        return publishCompletion(transaction);
    }

    private void publishInitiated(Transaction transaction) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription(),
                transaction.getSenderBalanceBefore()
        );

        String transactionId = transaction.getId();
        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, transactionId, event)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("Could not publish transaction.initiated for {} - recovery will retry: {}",
                                transactionId, error.getMessage());
                    }
                });
        log.info("SAGA STEP 2 - TransactionInitiatedEvent published: {}", transactionId);
    }

    private RuntimeException deductFailure(Transaction transaction, FeignException e) {
        String message = errorMessage(e);

        if (e.status() >= 400 && e.status() < 500) {
            // Account Service refused the debit (e.g. insufficient balance) — nothing moved
            markFailed(transaction, message);
            return new TransferRejectedException(message);
        }

        // Timeout or 5xx: the debit may or may not have happened. Reversing is safe either way.
        log.error("Deduct outcome unknown for {} (status {}) - reversing", transaction.getId(), e.status());
        if (tryReverse(transaction)) {
            markFailed(transaction, "Account service unavailable - transfer cancelled, no money was moved");
        }
        return new TransferFailedException("Transfer could not be completed - please try again", e);
    }

    private boolean tryReverse(Transaction transaction) {
        try {
            accountServiceClient.reverseDebit(transaction.getSenderAccountNumber(), transaction.getId());
            return true;
        } catch (RuntimeException e) {
            log.error("Reversal for {} failed - recovery will retry: {}", transaction.getId(), e.getMessage());
            return false;
        }
    }

    private void markFailed(Transaction transaction, String reason) {
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureReason(reason);
        transactionRepository.save(transaction);
    }

    private void ensureAccountExists(String accountNumber, String role) {
        try {
            accountServiceClient.getBalance(accountNumber);
        } catch (FeignException.NotFound e) {
            throw new TransferRejectedException(role + " account not found: " + accountNumber);
        } catch (FeignException e) {
            throw new TransferFailedException("Account service unavailable - please try again", e);
        }
    }

    private BigDecimal parseBalanceBefore(ResponseEntity<String> response) {
        String header = response == null ? null
                : response.getHeaders().getFirst(AccountServiceClient.BALANCE_BEFORE_HEADER);
        try {
            return header == null ? null : new BigDecimal(header);
        } catch (NumberFormatException e) {
            log.warn("Ignoring malformed {} header: {}", AccountServiceClient.BALANCE_BEFORE_HEADER, header);
            return null;
        }
    }

    private String errorMessage(FeignException e) {
        try {
            JsonNode body = objectMapper.readTree(e.contentUTF8());
            if (body != null && body.hasNonNull("error")) {
                return body.get("error").asText();
            }
        } catch (Exception ignored) {
            // fall through to the generic message
        }
        return "Account service rejected the transfer (HTTP " + e.status() + ")";
    }

    private boolean isVerificationExpired(Transaction transaction) {
        return transaction.getVerificationExpiresAt() != null
                && LocalDateTime.now().isAfter(transaction.getVerificationExpiresAt());
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private Transaction findTransaction(String transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    // Another user's transfer looks exactly like a missing one
    private Transaction findTransaction(String transactionId, String userId) {
        Transaction transaction = findTransaction(transactionId);
        if (userId != null && !userId.equals(transaction.getInitiatedByUserId())) {
            throw new TransactionNotFoundException(transactionId);
        }
        return transaction;
    }

    // IBAN → account via Account Service, which also rejects typos and other banks' IBANs
    private AccountServiceClient.Recipient resolveIban(String iban) {
        AccountServiceClient.Recipient recipient;
        try {
            recipient = accountServiceClient.lookupIban(iban.replaceAll("\\s+", ""));
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                throw new TransferRejectedException(errorMessage(e));
            }
            throw new TransferFailedException("Account service unavailable - please try again", e);
        }
        if (recipient == null || !recipient.canReceive()) {
            throw new TransferRejectedException("That account can't receive money");
        }
        return recipient;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void ensureOwnsAccount(String accountNumber, String userId, String message) {
        AccountServiceClient.AccountOwner account;
        try {
            account = accountServiceClient.getAccount(accountNumber);
        } catch (FeignException.NotFound e) {
            throw new ForbiddenException(message);
        } catch (FeignException e) {
            throw new TransferFailedException("Account service unavailable - please try again", e);
        }
        if (account == null || !userId.equals(account.ownerUserId())) {
            throw new ForbiddenException(message);
        }
    }


    private TransactionResponse mapToResponse(Transaction transaction){

        TransactionResponse response = new TransactionResponse();
        response.setId(transaction.getId());
        response.setSenderAccountNumber(
                transaction.getSenderAccountNumber());
        response.setReceiverAccountNumber(
                transaction.getReceiverAccountNumber());
        response.setReceiverIban(transaction.getReceiverIban());
        response.setAmount(transaction.getAmount());
        response.setType(transaction.getType());
        response.setStatus(transaction.getStatus());
        response.setDescription(transaction.getDescription());
        response.setReferenceNumber(transaction.getReferenceNumber());
        response.setFailureReason(transaction.getFailureReason());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setCompletedAt(transaction.getCompletedAt());
        response.setVerificationExpiresAt(transaction.getVerificationExpiresAt());
        if (transaction.getStatus() == TransactionStatus.PENDING_VERIFICATION
                && transaction.getVerificationExpiresAt() != null) {
            response.setVerificationSecondsRemaining(Math.max(0, Duration.between(
                    LocalDateTime.now(), transaction.getVerificationExpiresAt()).toSeconds()));
        }

        return response;
    }
}
