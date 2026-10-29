package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.dto.RiskEvaluationRequest;
import com.banking.frauddetectionservice.dto.RiskEvaluationResponse;
import com.banking.frauddetectionservice.model.RiskDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Collects the facts about a transfer (velocity and running average from
 * Redis, pre-transfer balance from the event or Account Service), asks the
 * Risk Engine for a decision and routes it into the SAGA:
 *
 *   ALLOW  → fraud.check.clean      (complete)
 *   REVIEW → verification.required  (OTP step-up)
 *   BLOCK  → fraud.check.blocked    (cancel, refund, block account)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FraudDetectionService {

    private final AccountServiceClient accountServiceClient;
    private final RiskEvaluationService riskEvaluationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    static final String VERIFICATION_REQUIRED_TOPIC = "verification.required";
    static final String FRAUD_CHECK_CLEAN_RESULT_TOPIC = "fraud.check.clean";
    static final String FRAUD_CHECK_BLOCKED_TOPIC = "fraud.check.blocked";

    private static final long VELOCITY_WINDOW_SECONDS = 60;
    private static final long PROCESSED_TTL_HOURS = 24;
    private static final long PUBLISH_TIMEOUT_SECONDS = 10;

    static String velocityKey(String accountNumber) {
        return "fraud:velocity:" + accountNumber;
    }

    static String averageKey(String accountNumber) {
        return "fraud:avg_amount:" + accountNumber;
    }

    static String processedKey(String transactionId) {
        return "fraud:processed:" + transactionId;
    }

    public void checkTransaction(Map<String,Object> payload){
        String transactionId = (String)payload.get("transactionId");
        String accountNumber = (String)payload.get("senderAccountNumber");
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());

        // Duplicate delivery: replay the stored verdict instead of counting the
        // transfer twice in velocity and the running average
        String processed = redisTemplate.opsForValue().get(processedKey(transactionId));
        if (processed != null) {
            log.info("Transaction {} already checked - re-publishing stored verdict", transactionId);
            publish(readVerdict(processed));
            return;
        }

        long velocityCount = incrementVelocity(accountNumber);
        BigDecimal averageAmount = readAverage(accountNumber);
        BigDecimal balanceBefore = resolveBalanceBefore(payload, accountNumber, amount);

        log.info("Checking transaction: {} account: {} amount: {} balance before: {} velocity: {} average: {}",
                transactionId, accountNumber, amount, balanceBefore, velocityCount, averageAmount);

        RiskEvaluationResponse result = riskEvaluationService.evaluate(new RiskEvaluationRequest(
                transactionId, accountNumber, amount, balanceBefore,
                (int) Math.min(velocityCount, Integer.MAX_VALUE), averageAmount));

        if (result.getUpdatedAverageAmount() != null) {
            redisTemplate.opsForValue().set(averageKey(accountNumber),
                    result.getUpdatedAverageAmount().toPlainString());
        }

        log.info("Risk decision for {}: {} by {} - rules: {} reason: {}",
                transactionId, result.getDecision(), result.getEvaluatedBy(),
                result.getTriggeredRules().stream().map(RiskEvaluationResponse.TriggeredRule::getCode).toList(),
                result.getReason());

        Verdict verdict = toVerdict(result, accountNumber, amount);
        redisTemplate.opsForValue().set(processedKey(transactionId), writeVerdict(verdict),
                PROCESSED_TTL_HOURS, TimeUnit.HOURS);
        publish(verdict);
    }

    private Verdict toVerdict(RiskEvaluationResponse result, String accountNumber, BigDecimal amount) {
        String transactionId = result.getTransactionId();
        Map<String, Object> event = new HashMap<>();
        event.put("transactionId", transactionId);
        event.put("decision", result.getDecision().name());
        event.put("reason", result.getReason());

        switch (result.getDecision()) {
            case ALLOW -> {
                // Transaction is clean
                event.put("isFraud", false);
                return new Verdict(FRAUD_CHECK_CLEAN_RESULT_TOPIC, transactionId, event);
            }
            case REVIEW, BLOCK -> {
                event.put("accountNumber", accountNumber);
                event.put("amount", amount);
                event.put("triggeredRules", result.getTriggeredRules().stream()
                        .map(RiskEvaluationResponse.TriggeredRule::getCode).toList());
                String topic = result.getDecision() == RiskDecision.BLOCK
                        ? FRAUD_CHECK_BLOCKED_TOPIC
                        : VERIFICATION_REQUIRED_TOPIC;
                return new Verdict(topic, transactionId, event);
            }
            default -> throw new IllegalStateException("Unknown decision " + result.getDecision());
        }
    }

    // Synchronous so a broker failure surfaces here and the Kafka listener retries (replaying the verdict)
    private void publish(Verdict verdict) {
        try {
            kafkaTemplate.send(verdict.topic(), verdict.key(), verdict.event())
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing fraud verdict", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not publish fraud verdict to " + verdict.topic(), e);
        }
        log.info("Fraud verdict published to {} for {}", verdict.topic(), verdict.key());
    }

    private long incrementVelocity(String accountNumber){
        String key = velocityKey(accountNumber);
        Long count = redisTemplate.opsForValue().increment(key);

        if(count != null && count == 1){
            redisTemplate.expire(key, VELOCITY_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        return count == null ? 1 : count;
    }

    private BigDecimal readAverage(String accountNumber) {
        String avgStr = redisTemplate.opsForValue().get(averageKey(accountNumber));
        return avgStr == null ? null : new BigDecimal(avgStr);
    }

    /**
     * The balance rule must use the balance BEFORE this transfer. The event
     * carries it; for events from older producers (published after the
     * debit) the amount is added back to the current balance.
     */
    private BigDecimal resolveBalanceBefore(Map<String, Object> payload, String accountNumber, BigDecimal amount) {
        Object before = payload.get("senderBalanceBefore");
        if (before != null) {
            return new BigDecimal(before.toString());
        }
        return accountServiceClient.getBalance(accountNumber).add(amount);
    }

    private String writeVerdict(Verdict verdict) {
        try {
            return objectMapper.writeValueAsString(verdict);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize fraud verdict", e);
        }
    }

    private Verdict readVerdict(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored fraud verdict", e);
        }
    }

    /** A fraud decision ready to publish; stored so redeliveries publish the same verdict. */
    record Verdict(String topic, String key, Map<String, Object> event) {
    }
}
