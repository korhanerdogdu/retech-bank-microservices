package com.banking.frauddetectionservice.dto;

import com.banking.frauddetectionservice.model.RiskDecision;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RiskEvaluationResponse {
    private String transactionId;
    private RiskDecision decision;
    // Message of the first triggered rule — shown to the customer
    private String reason;
    private List<TriggeredRule> triggeredRules = new ArrayList<>();
    // New running average to store; null = leave unchanged
    private BigDecimal updatedAverageAmount;
    // Which implementation decided: RUST or JAVA (fallback). Not part of the engine's JSON.
    private String evaluatedBy;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TriggeredRule {
        private String code;
        private String title;
        private String message;
    }
}
