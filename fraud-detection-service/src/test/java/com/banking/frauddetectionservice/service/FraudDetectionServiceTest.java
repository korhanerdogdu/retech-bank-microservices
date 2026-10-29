package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.dto.RiskEvaluationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
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
class FraudDetectionServiceTest {

    private static final String ACCOUNT = "123456789012";
    private static final String TX = "tx-1";

    private final AccountServiceClient accountClient = mock(AccountServiceClient.class);
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> store = new HashMap<>();
    private RiskEvaluationService riskEvaluationService;
    private FraudDetectionService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        when(values.increment(anyString())).thenAnswer(i ->
                Long.parseLong(store.merge(i.getArgument(0), "1", (a, b) -> String.valueOf(Long.parseLong(a) + 1))));
        doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1))).when(values).set(anyString(), anyString());
        doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1)))
                .when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(anyString(), anyString(), any());

        // Real Java rules behind a spy, so tests exercise the actual decision logic
        riskEvaluationService = spy(new RiskEvaluationService(null, LocalRiskEvaluatorTest.EVALUATOR));
        ReflectionTestUtils.setField(riskEvaluationService, "engineEnabled", false);
        service = new FraudDetectionService(accountClient, riskEvaluationService, kafka, redis, new ObjectMapper());
    }

    @Test
    void usesPreTransferBalanceFromTheEvent() {
        // Balance after the debit is 50, before it was 1000: 100 is only 10% of the real balance
        service.checkTransaction(event("100", "1000"));

        ArgumentCaptor<RiskEvaluationRequest> request = ArgumentCaptor.forClass(RiskEvaluationRequest.class);
        verify(riskEvaluationService).evaluate(request.capture());
        assertThat(request.getValue().getBalance()).isEqualByComparingTo("1000");
        verifyNoInteractions(accountClient);
        verify(kafka).send(eq(FraudDetectionService.FRAUD_CHECK_CLEAN_RESULT_TOPIC), eq(TX), any());
    }

    @Test
    void legacyEventsAddTheAmountBackToTheCurrentBalance() {
        when(accountClient.getBalance(ACCOUNT)).thenReturn(new BigDecimal("900"));

        service.checkTransaction(event("100", null));

        ArgumentCaptor<RiskEvaluationRequest> request = ArgumentCaptor.forClass(RiskEvaluationRequest.class);
        verify(riskEvaluationService).evaluate(request.capture());
        assertThat(request.getValue().getBalance()).isEqualByComparingTo("1000");
    }

    @Test
    void reviewRoutesToOtpVerification() {
        service.checkTransaction(event("950", "1000"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(FraudDetectionService.VERIFICATION_REQUIRED_TOPIC), eq(TX), payload.capture());
        Map<String, Object> event = (Map<String, Object>) payload.getValue();
        assertThat(event).containsEntry("decision", "REVIEW")
                .containsEntry("accountNumber", ACCOUNT)
                .containsEntry("reason", "Transaction exceeds 90% of account balance");
    }

    @Test
    void blockRoutesToBlockedTopic() {
        store.put(FraudDetectionService.velocityKey(ACCOUNT), "10"); // this transfer is the 11th
        service.checkTransaction(event("10", "1000"));

        verify(kafka).send(eq(FraudDetectionService.FRAUD_CHECK_BLOCKED_TOPIC), eq(TX), any());
    }

    @Test
    void redisKeysUseSeparators() {
        service.checkTransaction(event("100", "1000"));

        assertThat(store).containsKeys("fraud:velocity:" + ACCOUNT, "fraud:avg_amount:" + ACCOUNT, "fraud:processed:" + TX);
        verify(redis).expire("fraud:velocity:" + ACCOUNT, 60, TimeUnit.SECONDS);
    }

    @Test
    void duplicateEventReplaysVerdictWithoutRecounting() {
        service.checkTransaction(event("100", "1000"));
        service.checkTransaction(event("100", "1000"));

        assertThat(store.get(FraudDetectionService.velocityKey(ACCOUNT))).isEqualTo("1");
        assertThat(new BigDecimal(store.get(FraudDetectionService.averageKey(ACCOUNT)))).isEqualByComparingTo("100");
        verify(riskEvaluationService, times(1)).evaluate(any());
        verify(kafka, times(2)).send(eq(FraudDetectionService.FRAUD_CHECK_CLEAN_RESULT_TOPIC), eq(TX), any());
    }

    @Test
    void publishFailureIsRethrownForKafkaRetry() {
        doReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .when(kafka).send(anyString(), anyString(), any());

        assertThatThrownBy(() -> service.checkTransaction(event("100", "1000")))
                .isInstanceOf(IllegalStateException.class);
        // the verdict is stored, so the retry replays it instead of re-evaluating
        assertThat(store).containsKey(FraudDetectionService.processedKey(TX));
    }

    private static Map<String, Object> event(String amount, String balanceBefore) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", TX);
        payload.put("senderAccountNumber", ACCOUNT);
        payload.put("receiverAccountNumber", "999999999999");
        payload.put("amount", Double.valueOf(amount));
        if (balanceBefore != null) {
            payload.put("senderBalanceBefore", Double.valueOf(balanceBefore));
        }
        return payload;
    }
}
