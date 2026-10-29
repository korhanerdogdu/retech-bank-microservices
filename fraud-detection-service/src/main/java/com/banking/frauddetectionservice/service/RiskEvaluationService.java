package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.RiskEngineClient;
import com.banking.frauddetectionservice.dto.RiskEvaluationRequest;
import com.banking.frauddetectionservice.dto.RiskEvaluationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Asks the Rust Risk Engine for a decision. If it is disabled or
 * unreachable, the Java rules decide instead, so transfers are never left
 * without a fraud check.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RiskEvaluationService {

    private final RiskEngineClient riskEngineClient;
    private final LocalRiskEvaluator localRiskEvaluator;

    @Value("${risk.engine.enabled:true}")
    private boolean engineEnabled = true;

    @Value("${risk.engine.fallback-to-local:true}")
    private boolean fallbackToLocal = true;

    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        if (engineEnabled) {
            try {
                RiskEvaluationResponse response = riskEngineClient.evaluate(request);
                if (response == null || response.getDecision() == null) {
                    throw new IllegalStateException("Risk engine returned no decision");
                }
                response.setEvaluatedBy("RUST");
                return response;
            } catch (RuntimeException e) {
                if (!fallbackToLocal) {
                    throw e;
                }
                log.warn("Rust risk engine unavailable for {} - using Java rules: {}",
                        request.getTransactionId(), e.getMessage());
            }
        }
        return localRiskEvaluator.evaluate(request);
    }
}
