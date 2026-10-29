package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.exception.TransferFailedException;
import com.banking.transactionservice.exception.TransferRejectedException;
import com.banking.transactionservice.model.Transaction;
import com.banking.transactionservice.model.TransactionStatus;
import com.banking.transactionservice.model.TransactionType;
import com.banking.transactionservice.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class TransactionServiceTest {

    private static final String SENDER = "111111111111";
    private static final String RECEIVER = "222222222222";
    private static final String TX = "tx-1";

    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final AccountServiceClient accountClient = mock(AccountServiceClient.class);
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private final ValueOperations<String, String> redisValues = mock(ValueOperations.class);
    private final OtpGenerator otpGenerator = mock(OtpGenerator.class);

    private final List<TransactionStatus> savedStatuses = new ArrayList<>();
    private TransactionService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(redisValues);
        when(repository.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            if (t.getId() == null) t.setId(TX);
            savedStatuses.add(t.getStatus());
            return t;
        });
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(anyString(), anyString(), any());
        when(otpGenerator.generate()).thenReturn("123456");
        service = new TransactionService(repository, accountClient, kafka, redis, otpGenerator, new ObjectMapper());
    }

    // ---------- transfer ----------

    @Test
    void transferSavesBeforeDeductingAndPublishesPreTransferBalance() {
        when(accountClient.deductBalance(SENDER, new BigDecimal("100.00"), TX))
                .thenReturn(ResponseEntity.ok().header("X-Balance-Before", "1000.00").body("ok"));

        TransactionResponse response = service.transfer(request("100.00"));

        InOrder order = inOrder(repository, accountClient, kafka);
        order.verify(repository).save(any());
        order.verify(accountClient).deductBalance(SENDER, new BigDecimal("100.00"), TX);
        order.verify(repository).save(any());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        order.verify(kafka).send(eq("transaction.initiated"), eq(TX), event.capture());

        assertThat(savedStatuses).containsExactly(TransactionStatus.PENDING, TransactionStatus.PROCESSING);
        assertThat(((TransactionInitiatedEvent) event.getValue()).getSenderBalanceBefore())
                .isEqualByComparingTo("1000.00");
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
    }

    @Test
    void rejectedDeductMarksFailedWithoutRefund() {
        when(accountClient.deductBalance(anyString(), any(), anyString()))
                .thenThrow(feignError(422, "{\"error\":\"Insufficient balance\"}"));

        assertThatThrownBy(() -> service.transfer(request("5000")))
                .isInstanceOf(TransferRejectedException.class)
                .hasMessage("Insufficient balance");

        assertThat(savedStatuses).containsExactly(TransactionStatus.PENDING, TransactionStatus.FAILED);
        verify(accountClient, never()).reverseDebit(anyString(), anyString());
        verifyNoInteractions(kafka);
    }

    @Test
    void unknownDeductOutcomeIsReversed() {
        when(accountClient.deductBalance(anyString(), any(), anyString()))
                .thenThrow(feignError(503, "unavailable"));

        assertThatThrownBy(() -> service.transfer(request("100")))
                .isInstanceOf(TransferFailedException.class);

        verify(accountClient).reverseDebit(SENDER, TX);
        assertThat(savedStatuses).containsExactly(TransactionStatus.PENDING, TransactionStatus.FAILED);
    }

    @Test
    void unknownDeductOutcomeStaysPendingWhenReversalAlsoFails() {
        when(accountClient.deductBalance(anyString(), any(), anyString()))
                .thenThrow(feignError(503, "unavailable"));
        when(accountClient.reverseDebit(anyString(), anyString())).thenThrow(feignError(503, "unavailable"));

        assertThatThrownBy(() -> service.transfer(request("100")))
                .isInstanceOf(TransferFailedException.class);

        // left PENDING so TransactionRecoveryScheduler retries the reversal
        assertThat(savedStatuses).containsExactly(TransactionStatus.PENDING);
    }

    @Test
    void persistenceFailureAfterDebitReversesTheDebit() {
        when(accountClient.deductBalance(anyString(), any(), anyString()))
                .thenReturn(ResponseEntity.ok("ok"));
        when(repository.save(any(Transaction.class)))
                .thenAnswer(i -> { Transaction t = i.getArgument(0); t.setId(TX); return t; })
                .thenThrow(new DataAccessResourceFailureException("PostgreSQL down"));

        assertThatThrownBy(() -> service.transfer(request("100")))
                .isInstanceOf(TransferFailedException.class);

        verify(accountClient).reverseDebit(SENDER, TX);
        verifyNoInteractions(kafka);
    }

    @Test
    void missingReceiverIsRejectedBeforeAnyMoneyMoves() {
        when(accountClient.getBalance(RECEIVER)).thenThrow(feignError(404, "{\"error\":\"Account not found\"}"));

        assertThatThrownBy(() -> service.transfer(request("100")))
                .isInstanceOf(TransferRejectedException.class)
                .hasMessageContaining("Receiver account not found");

        verify(repository, never()).save(any());
        verify(accountClient, never()).deductBalance(anyString(), any(), anyString());
    }

    // ---------- ownership (requests through the gateway carry a user id) ----------

    @Test
    void usersCanOnlySendFromTheirOwnAccount() {
        when(accountClient.getAccount(SENDER)).thenReturn(new AccountServiceClient.AccountOwner(SENDER, "owner"));

        assertThatThrownBy(() -> service.transfer(request("100"), "intruder"))
                .isInstanceOf(com.banking.transactionservice.exception.ForbiddenException.class);
        verify(repository, never()).save(any());
        verify(accountClient, never()).deductBalance(anyString(), any(), anyString());
    }

    @Test
    void ownerTransferRecordsWhoStartedIt() {
        when(accountClient.getAccount(SENDER)).thenReturn(new AccountServiceClient.AccountOwner(SENDER, "owner"));
        when(accountClient.deductBalance(anyString(), any(), anyString())).thenReturn(ResponseEntity.ok("ok"));
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);

        service.transfer(request("100"), "owner");

        verify(repository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getInitiatedByUserId()).isEqualTo("owner");
    }

    @Test
    void otherUsersCannotSeeVerifyOrReportATransfer() {
        Transaction tx = pendingVerification();
        tx.setInitiatedByUserId("owner");
        stored(tx);

        assertThatThrownBy(() -> service.getTransaction(TX, "intruder"))
                .isInstanceOf(com.banking.transactionservice.exception.TransactionNotFoundException.class);
        assertThatThrownBy(() -> service.verifyOTP(TX, "123456", "intruder"))
                .isInstanceOf(com.banking.transactionservice.exception.TransactionNotFoundException.class);
        assertThatThrownBy(() -> service.reportFraud(TX, "intruder"))
                .isInstanceOf(com.banking.transactionservice.exception.TransactionNotFoundException.class);
        verify(accountClient, never()).reverseDebit(anyString(), anyString());
    }

    @Test
    void sendingToYourselfIsRejected() {
        assertThatThrownBy(() -> service.transfer(new TransferRequest(SENDER, SENDER, BigDecimal.TEN, null)))
                .isInstanceOf(TransferRejectedException.class);
    }

    // ---------- IBAN ----------

    // TR + check digits + bank code 00999 + 0 + account number zero-padded to 16 (validity is Account Service's job)
    private static final String RECEIVER_IBAN = "TR000099900000222222222222";

    @Test
    void transferByIbanResolvesTheReceiverAccount() {
        when(accountClient.lookupIban(RECEIVER_IBAN))
                .thenReturn(new AccountServiceClient.Recipient(RECEIVER_IBAN, RECEIVER, "Bob K.", true));
        when(accountClient.deductBalance(anyString(), any(), anyString())).thenReturn(ResponseEntity.ok("ok"));

        TransactionResponse response = service.transfer(
                new TransferRequest(SENDER, null, new BigDecimal("100"), "rent",
                        RECEIVER_IBAN.replaceAll("(.{4})(?!$)", "$1 "))); // as typed, with spaces

        assertThat(response.getReceiverAccountNumber()).isEqualTo(RECEIVER);
        assertThat(response.getReceiverIban()).isEqualTo(RECEIVER_IBAN);
        verify(accountClient, never()).getBalance(RECEIVER); // the IBAN lookup already proved it exists
    }

    @Test
    void invalidIbanIsRejectedWithTheReasonBeforeAnyMoneyMoves() {
        when(accountClient.lookupIban(anyString()))
                .thenThrow(feignError(400, "{\"error\":\"That isn't a valid IBAN. Check it for typos.\"}"));

        assertThatThrownBy(() -> service.transfer(
                new TransferRequest(SENDER, null, BigDecimal.TEN, null, "TR000000000000000000000000")))
                .isInstanceOf(TransferRejectedException.class)
                .hasMessage("That isn't a valid IBAN. Check it for typos.");
        verify(repository, never()).save(any());
        verify(accountClient, never()).deductBalance(anyString(), any(), anyString());
    }

    @Test
    void ibanOfYourOwnAccountIsRejected() {
        when(accountClient.lookupIban(RECEIVER_IBAN))
                .thenReturn(new AccountServiceClient.Recipient(RECEIVER_IBAN, SENDER, "Me", true));

        assertThatThrownBy(() -> service.transfer(
                new TransferRequest(SENDER, null, BigDecimal.TEN, null, RECEIVER_IBAN)))
                .isInstanceOf(TransferRejectedException.class);
    }

    @Test
    void aRecipientIsRequired() {
        assertThatThrownBy(() -> service.transfer(new TransferRequest(SENDER, null, BigDecimal.TEN, null, null)))
                .isInstanceOf(TransferRejectedException.class)
                .hasMessage("Enter the recipient's IBAN");
    }

    // ---------- fraud verdicts ----------

    @Test
    void cleanResultCompletesAndRecordsPublication() {
        stored(transaction(TransactionStatus.PROCESSING));

        service.processCleanResult(TX);

        verify(kafka).send(eq("transaction.completed"), eq(TX), any());
        assertThat(savedStatuses).containsExactly(TransactionStatus.COMPLETED, TransactionStatus.COMPLETED);
    }

    @Test
    void duplicateCleanResultIsIgnored() {
        stored(transaction(TransactionStatus.COMPLETED));

        service.processCleanResult(TX);

        verifyNoInteractions(kafka);
        verify(repository, never()).save(any());
    }

    @Test
    void completionPublishFailureIsLeftForRecovery() {
        Transaction tx = stored(transaction(TransactionStatus.PROCESSING));
        doReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .when(kafka).send(eq("transaction.completed"), anyString(), any());

        service.processCleanResult(TX);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getCompletionEventPublished()).isFalse();
    }

    @Test
    void reviewStoresOtpWithDeadlineAfterClaimingState() {
        Transaction tx = stored(transaction(TransactionStatus.PROCESSING));

        service.requireVerification(TX, "Unusual amount", 100);

        InOrder order = inOrder(repository, redisValues, kafka);
        order.verify(repository).save(tx);
        order.verify(redisValues).set("verification:otp:" + TX, "123456", 5, TimeUnit.MINUTES);
        order.verify(kafka).send(eq("transaction.otp.generated"), eq(TX), any());
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING_VERIFICATION);
        assertThat(tx.getVerificationExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(4));
    }

    @Test
    void duplicateReviewDoesNotReplaceTheOtp() {
        stored(transaction(TransactionStatus.PENDING_VERIFICATION));

        service.requireVerification(TX, "Unusual amount", 100);

        verify(redisValues, never()).set(anyString(), anyString(), anyLong(), any());
        verifyNoInteractions(kafka);
    }

    @Test
    void blockedResultBlocksAccountAndRefunds() {
        stored(transaction(TransactionStatus.PROCESSING));

        service.processBlockedResult(TX, "Too many transactions");

        verify(kafka).send(eq("fraud.detected"), eq(SENDER), any());
        verify(accountClient).reverseDebit(SENDER, TX);
        verify(kafka).send(eq("transaction.refunded"), eq(TX), any());
        assertThat(savedStatuses).containsExactly(TransactionStatus.COMPENSATING, TransactionStatus.FLAGGED);
    }

    // ---------- OTP verification ----------

    @Test
    void correctOtpCompletesTransfer() {
        stored(pendingVerification());
        when(redisValues.get("verification:otp:" + TX)).thenReturn("123456");

        TransactionResponse response = service.verifyOTP(TX, "123456");

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        verify(redis).delete("verification:otp:" + TX);
        verify(accountClient, never()).reverseDebit(anyString(), anyString());
    }

    @Test
    void wrongOtpBlocksAccountAndRefundsOnce() {
        stored(pendingVerification());
        when(redisValues.get("verification:otp:" + TX)).thenReturn("123456");

        TransactionResponse response = service.verifyOTP(TX, "000000");

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.FLAGGED);
        verify(kafka).send(eq("fraud.detected"), eq(SENDER), any());
        verify(accountClient, times(1)).reverseDebit(SENDER, TX);
    }

    @Test
    void expiredOtpRefundsWithoutBlocking() {
        stored(pendingVerification());
        when(redisValues.get(anyString())).thenReturn(null);

        TransactionResponse response = service.verifyOTP(TX, "123456");

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.FLAGGED);
        assertThat(response.getFailureReason()).startsWith("OTP expired");
        verify(accountClient).reverseDebit(SENDER, TX);
        verify(kafka, never()).send(eq("fraud.detected"), anyString(), any());
    }

    @Test
    void otpPastDeadlineIsExpiredEvenIfStillInRedis() {
        Transaction tx = pendingVerification();
        tx.setVerificationExpiresAt(LocalDateTime.now().minusSeconds(1));
        stored(tx);
        when(redisValues.get(anyString())).thenReturn("123456");

        assertThat(service.verifyOTP(TX, "123456").getStatus()).isEqualTo(TransactionStatus.FLAGGED);
    }

    @Test
    void verifyingAFinishedTransferNeverRefundsAgain() {
        // the original bug: a second call found no OTP and refunded a completed transfer
        stored(transaction(TransactionStatus.COMPLETED));

        TransactionResponse response = service.verifyOTP(TX, "123456");

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        verify(accountClient, never()).reverseDebit(anyString(), anyString());
        verify(accountClient, never()).creditBalance(anyString(), any());
        verifyNoInteractions(kafka);
    }

    @Test
    void failedRefundStaysCompensatingForRecovery() {
        stored(pendingVerification());
        when(redisValues.get(anyString())).thenReturn(null);
        when(accountClient.reverseDebit(anyString(), anyString())).thenThrow(feignError(503, "down"));

        TransactionResponse response = service.verifyOTP(TX, "123456");

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.COMPENSATING);
        verify(kafka, never()).send(eq("transaction.refunded"), anyString(), any());
    }

    @Test
    void reportedFraudCancelsRefundsAndBlocks() {
        stored(pendingVerification());

        TransactionResponse response = service.reportFraud(TX);

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.FLAGGED);
        verify(redis).delete("verification:otp:" + TX);
        verify(kafka).send(eq("fraud.detected"), eq(SENDER), any());
        verify(accountClient).reverseDebit(SENDER, TX);
    }

    @Test
    void reportingAFinishedTransferChangesNothing() {
        stored(transaction(TransactionStatus.COMPLETED));

        assertThat(service.reportFraud(TX).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        verifyNoInteractions(kafka);
        verify(accountClient, never()).reverseDebit(anyString(), anyString());
    }

    // ---------- recovery ----------

    @Test
    void recoverPendingReversesAndFails() {
        Transaction tx = transaction(TransactionStatus.PENDING);

        service.recoverPending(tx);

        verify(accountClient).reverseDebit(SENDER, TX);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void finishCompensationIsSafeToRepeat() {
        Transaction tx = transaction(TransactionStatus.COMPENSATING);
        tx.setFailureReason("OTP expired");

        service.finishCompensation(tx);

        verify(accountClient).reverseDebit(SENDER, TX);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FLAGGED);
    }

    // ---------- helpers ----------

    private TransferRequest request(String amount) {
        return new TransferRequest(SENDER, RECEIVER, new BigDecimal(amount), "rent");
    }

    private Transaction transaction(TransactionStatus status) {
        Transaction tx = new Transaction();
        tx.setId(TX);
        tx.setSenderAccountNumber(SENDER);
        tx.setReceiverAccountNumber(RECEIVER);
        tx.setAmount(new BigDecimal("100.00"));
        tx.setType(TransactionType.TRANSFER);
        tx.setStatus(status);
        tx.setCreatedAt(LocalDateTime.now());
        return tx;
    }

    private Transaction pendingVerification() {
        Transaction tx = transaction(TransactionStatus.PENDING_VERIFICATION);
        tx.setVerificationExpiresAt(LocalDateTime.now().plusMinutes(5));
        return tx;
    }

    private Transaction stored(Transaction tx) {
        when(repository.findById(TX)).thenReturn(Optional.of(tx));
        return tx;
    }

    private static FeignException feignError(int status, String body) {
        Request request = Request.create(Request.HttpMethod.PUT, "/api/v1/accounts", Map.of(),
                null, StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .reason("error")
                .request(request)
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("AccountServiceClient", response);
    }
}
