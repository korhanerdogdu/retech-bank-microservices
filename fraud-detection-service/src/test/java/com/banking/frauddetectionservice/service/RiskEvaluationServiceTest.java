package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.RiskEngineClient;
import com.banking.frauddetectionservice.dto.RiskEvaluationRequest;
import com.banking.frauddetectionservice.dto.RiskEvaluationResponse;
import com.banking.frauddetectionservice.model.RiskDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskEvaluationServiceTest {

    private final RiskEngineClient client = mock(RiskEngineClient.class);
    private final RiskEvaluationService service = new RiskEvaluationService(client, LocalRiskEvaluatorTest.EVALUATOR);
    private final RiskEvaluationRequest request = LocalRiskEvaluatorTest.request("950", "1000", 1, "900");

    @Test
    void rustEngineDecidesWhenAvailable() {
        when(client.evaluate(any())).thenReturn(new RiskEvaluationResponse(
                "tx-1", RiskDecision.REVIEW, "reason", List.of(), null, null));

        RiskEvaluationResponse response = service.evaluate(request);

        assertThat(response.getEvaluatedBy()).isEqualTo("RUST");
        assertThat(response.getDecision()).isEqualTo(RiskDecision.REVIEW);
    }

    @Test
    void javaRulesTakeOverWhenRustIsDown() {
        when(client.evaluate(any())).thenThrow(connectionRefused());

        RiskEvaluationResponse response = service.evaluate(request);

        assertThat(response.getEvaluatedBy()).isEqualTo("JAVA");
        assertThat(response.getDecision()).isEqualTo(RiskDecision.REVIEW);
    }

    @Test
    void failsWhenFallbackIsDisabled() {
        ReflectionTestUtils.setField(service, "fallbackToLocal", false);
        when(client.evaluate(any())).thenThrow(connectionRefused());

        assertThatThrownBy(() -> service.evaluate(request)).hasMessage("Connection refused");
    }

    @Test
    void wireFormatMatchesTheRustContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // Money goes out as strings, exactly as risk-core expects
        JsonNode json = mapper.valueToTree(request);
        assertThat(json.get("amount").isTextual()).isTrue();
        assertThat(json.get("amount").asText()).isEqualTo("950");
        assertThat(json.get("velocityCount").asInt()).isEqualTo(1);

        // A real response body from the Rust engine
        String rust = """
                {"transactionId":"tx-42","decision":"REVIEW","reason":"Transaction exceeds 90% of account balance",
                 "triggeredRules":[{"code":"BALANCE_PERCENTAGE","title":"Balance percentage",
                 "message":"Transaction exceeds 90% of account balance"}],"updatedAverageAmount":"925.00"}
                """;
        RiskEvaluationResponse parsed = mapper.readValue(rust, RiskEvaluationResponse.class);
        assertThat(parsed.getDecision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(parsed.getUpdatedAverageAmount()).isEqualByComparingTo("925.00");
        assertThat(parsed.getTriggeredRules()).extracting(RiskEvaluationResponse.TriggeredRule::getCode)
                .containsExactly("BALANCE_PERCENTAGE");
    }

    private static RuntimeException connectionRefused() {
        return new IllegalStateException("Connection refused");
    }
}
