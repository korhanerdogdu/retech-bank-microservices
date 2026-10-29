package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.dto.RiskEvaluationRequest;
import com.banking.frauddetectionservice.dto.RiskEvaluationResponse;
import com.banking.frauddetectionservice.dto.RiskEvaluationResponse.TriggeredRule;
import com.banking.frauddetectionservice.model.RiskDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Java implementation of the risk-core rules. Used when the Rust engine is
 * unreachable (so fraud checks never stop) and as the Java baseline in
 * benchmarks. Must stay behaviourally identical to risk-core — see
 * LocalRiskEvaluatorTest, which mirrors the Rust test cases.
 */
@Component
public class LocalRiskEvaluator {

    private final int maxTransactionsPerMinute;
    private final BigDecimal suspiciousAmountMultiplier;
    private final BigDecimal maxBalancePercentage;
    private final int blockVelocityMultiplier;
    private final int blockMinTriggeredRules;

    public LocalRiskEvaluator(
            @Value("${fraud.max-transactions-per-minute}") int maxTransactionsPerMinute,
            @Value("${fraud.suspicious-amount-multiplier}") BigDecimal suspiciousAmountMultiplier,
            @Value("${fraud.max-balance-percentage}") BigDecimal maxBalancePercentage,
            @Value("${fraud.block-velocity-multiplier:2}") int blockVelocityMultiplier,
            @Value("${fraud.block-min-triggered-rules:3}") int blockMinTriggeredRules) {
        this.maxTransactionsPerMinute = maxTransactionsPerMinute;
        this.suspiciousAmountMultiplier = suspiciousAmountMultiplier;
        this.maxBalancePercentage = maxBalancePercentage;
        this.blockVelocityMultiplier = blockVelocityMultiplier;
        this.blockMinTriggeredRules = blockMinTriggeredRules;
    }

    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        BigDecimal amount = request.getAmount();
        BigDecimal balance = request.getBalance();
        BigDecimal average = request.getAverageAmount();
        List<TriggeredRule> triggered = new ArrayList<>();

        // Pattern 1: Velocity Check
        if (request.getVelocityCount() > maxTransactionsPerMinute) {
            triggered.add(new TriggeredRule("VELOCITY", "Transaction velocity",
                    "Too many transactions in 60 seconds - Velocity limit exceeded"));
        }

        // Pattern 2: amount > multiplier x running average (only with history)
        if (average != null && amount.compareTo(average.multiply(suspiciousAmountMultiplier)) > 0) {
            triggered.add(new TriggeredRule("UNUSUAL_AMOUNT", "Unusual transaction amount",
                    "Unusual transaction amount - exceeds "
                            + suspiciousAmountMultiplier.stripTrailingZeros().toPlainString()
                            + "x your average"));
        }

        // Pattern 3: amount > percentage of the pre-transfer balance
        if (balance.compareTo(BigDecimal.ZERO) > 0
                && amount.compareTo(balance.multiply(maxBalancePercentage)) > 0) {
            triggered.add(new TriggeredRule("BALANCE_PERCENTAGE", "Balance percentage",
                    "Transaction exceeds "
                            + maxBalancePercentage.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
                            + "% of account balance"));
        }

        RiskEvaluationResponse response = new RiskEvaluationResponse();
        response.setTransactionId(request.getTransactionId());
        response.setDecision(decide(request.getVelocityCount(), triggered));
        response.setReason(triggered.isEmpty() ? null : triggered.get(0).getMessage());
        response.setTriggeredRules(triggered);
        response.setUpdatedAverageAmount(nextAverage(request));
        response.setEvaluatedBy("JAVA");
        return response;
    }

    private RiskDecision decide(int velocityCount, List<TriggeredRule> triggered) {
        if (triggered.isEmpty()) {
            return RiskDecision.ALLOW;
        }
        if (velocityCount > maxTransactionsPerMinute * blockVelocityMultiplier
                || triggered.size() >= blockMinTriggeredRules) {
            return RiskDecision.BLOCK;
        }
        return RiskDecision.REVIEW;
    }

    // Running average is not updated when velocity fires (original short-circuit behaviour)
    private BigDecimal nextAverage(RiskEvaluationRequest request) {
        if (request.getVelocityCount() > maxTransactionsPerMinute) {
            return null;
        }
        if (request.getAverageAmount() == null) {
            return request.getAmount();
        }
        return request.getAverageAmount().add(request.getAmount())
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }
}
