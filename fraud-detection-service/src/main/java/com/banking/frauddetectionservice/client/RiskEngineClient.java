package com.banking.frauddetectionservice.client;

import com.banking.frauddetectionservice.dto.RiskEvaluationRequest;
import com.banking.frauddetectionservice.dto.RiskEvaluationResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Rust Fraud Risk Engine (Axum + Tokio). */
@FeignClient(name = "risk-engine", url = "${risk.engine.url}")
public interface RiskEngineClient {

    @PostMapping("/api/v1/risk/evaluate")
    RiskEvaluationResponse evaluate(@RequestBody RiskEvaluationRequest request);
}
