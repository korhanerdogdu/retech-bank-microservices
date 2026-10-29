package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.dto.RiskEvaluationRequest;
import com.banking.frauddetectionservice.dto.RiskEvaluationResponse;
import com.banking.frauddetectionservice.model.RiskDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors the risk-core Rust tests (risk-engine/risk-core/src/engine.rs) so the
 * Java fallback and the Rust engine stay behaviourally identical.
 */
class LocalRiskEvaluatorTest {

    static final LocalRiskEvaluator EVALUATOR =
            new LocalRiskEvaluator(5, new BigDecimal("3.0"), new BigDecimal("0.90"), 2, 3);

    static RiskEvaluationRequest request(String amount, String balance, int velocity, String average) {
        return new RiskEvaluationRequest("tx-1", "000000000001", new BigDecimal(amount), new BigDecimal(balance),
                velocity, average == null ? null : new BigDecimal(average));
    }

    @Test
    void cleanTransactionIsAllowed() {
        RiskEvaluationResponse r = EVALUATOR.evaluate(request("100", "1000", 1, "100"));
        assertThat(r.getDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(r.getTriggeredRules()).isEmpty();
        assertThat(r.getReason()).isNull();
        assertThat(r.getUpdatedAverageAmount()).isEqualByComparingTo("100");
        assertThat(r.getEvaluatedBy()).isEqualTo("JAVA");
    }

    @Test
    void firstTransactionSeedsAverageWithoutAmountCheck() {
        RiskEvaluationResponse r = EVALUATOR.evaluate(request("500", "10000", 1, null));
        assertThat(r.getDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(r.getUpdatedAverageAmount()).isEqualByComparingTo("500");
    }

    @Test
    void singleRuleRequiresReview() {
        RiskEvaluationResponse r = EVALUATOR.evaluate(request("950", "1000", 1, "900"));
        assertThat(r.getDecision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(r.getTriggeredRules()).extracting(RiskEvaluationResponse.TriggeredRule::getCode)
                .containsExactly("BALANCE_PERCENTAGE");
        assertThat(r.getReason()).isEqualTo("Transaction exceeds 90% of account balance");
    }

    @Test
    void reasonFollowsRuleOrder() {
        RiskEvaluationResponse r = EVALUATOR.evaluate(request("950", "1000", 6, "100"));
        assertThat(r.getTriggeredRules()).extracting(RiskEvaluationResponse.TriggeredRule::getCode)
                .containsExactly("VELOCITY", "UNUSUAL_AMOUNT", "BALANCE_PERCENTAGE");
        assertThat(r.getReason()).isEqualTo("Too many transactions in 60 seconds - Velocity limit exceeded");
    }

    @Test
    void allRulesOrExtremeVelocityBlock() {
        assertThat(EVALUATOR.evaluate(request("950", "1000", 6, "100")).getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(EVALUATOR.evaluate(request("10", "1000", 11, "10")).getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(EVALUATOR.evaluate(request("10", "1000", 10, "10")).getDecision()).isEqualTo(RiskDecision.REVIEW);
    }

    @Test
    void thresholdsAreStrict() {
        assertThat(EVALUATOR.evaluate(request("300", "10000", 1, "100")).getDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(EVALUATOR.evaluate(request("300.01", "10000", 1, "100")).getReason())
                .isEqualTo("Unusual transaction amount - exceeds 3x your average");
        assertThat(EVALUATOR.evaluate(request("900", "1000", 1, null)).getDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(EVALUATOR.evaluate(request("50", "0", 1, null)).getDecision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    void runningAverageRoundsHalfUpAndFreezesOnVelocity() {
        assertThat(EVALUATOR.evaluate(request("100.01", "1000", 1, "100.00")).getUpdatedAverageAmount())
                .isEqualByComparingTo("100.01");
        assertThat(EVALUATOR.evaluate(request("100", "1000", 6, "50")).getUpdatedAverageAmount()).isNull();
    }
}
