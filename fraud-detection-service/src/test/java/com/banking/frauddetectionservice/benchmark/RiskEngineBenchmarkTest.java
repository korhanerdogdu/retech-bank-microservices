package com.banking.frauddetectionservice.benchmark;

import com.banking.frauddetectionservice.dto.RiskEvaluationRequest;
import com.banking.frauddetectionservice.dto.RiskEvaluationResponse;
import com.banking.frauddetectionservice.service.LocalRiskEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

/**
 * Java vs Rust comparison. Opt-in, because timings are meaningless on shared CI:
 *
 *   mvn -pl fraud-detection-service test -Dtest=RiskEngineBenchmarkTest -Dbenchmark=true
 *       [-Drisk.engine.url=http://localhost:8086]
 *
 * Reports in-process Java rules, and — when the Rust engine is reachable —
 * the end-to-end HTTP latency the Fraud Detection Service actually pays.
 * Native in-process Rust: `cargo run --release -p risk-core --example bench`.
 * WebAssembly: the speed test in the Risk Simulation Dashboard.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class RiskEngineBenchmarkTest {

    private static final RiskEvaluationRequest REQUEST = new RiskEvaluationRequest(
            "bench", "123456789012", new BigDecimal("950.00"), new BigDecimal("1000.00"), 2, new BigDecimal("200.00"));

    @Test
    void javaInProcess() {
        LocalRiskEvaluator evaluator =
                new LocalRiskEvaluator(5, new BigDecimal("3.0"), new BigDecimal("0.90"), 2, 3);
        int iterations = 1_000_000;
        for (int i = 0; i < 50_000; i++) evaluator.evaluate(REQUEST); // JIT warm-up

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        long start = System.nanoTime();
        RiskEvaluationResponse last = null;
        for (int i = 0; i < iterations; i++) last = evaluator.evaluate(REQUEST);
        long elapsed = System.nanoTime() - start;
        long memAfter = rt.totalMemory() - rt.freeMemory();

        report("java in-process", iterations, elapsed);
        System.out.printf("  heap delta: %,d KB, last decision: %s%n", (memAfter - memBefore) / 1024, last.getDecision());
    }

    @Test
    void rustOverHttp() throws Exception {
        String url = System.getProperty("risk.engine.url", "http://localhost:8086") + "/api/v1/risk/evaluate";
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        String body = new ObjectMapper().writeValueAsString(REQUEST);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.out.println("rust over http: skipped, engine not reachable at " + url);
            return;
        }

        int iterations = 5_000;
        long[] latencies = new long[iterations];
        for (int i = 0; i < 500; i++) http.send(request, HttpResponse.BodyHandlers.ofString());
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t = System.nanoTime();
            http.send(request, HttpResponse.BodyHandlers.ofString());
            latencies[i] = System.nanoTime() - t;
        }
        long elapsed = System.nanoTime() - start;
        Arrays.sort(latencies);

        report("rust over http (sequential)", iterations, elapsed);
        System.out.printf("  p50 %.1f µs  p99 %.1f µs%n",
                latencies[iterations / 2] / 1e3, latencies[(int) (iterations * 0.99)] / 1e3);
    }

    private static void report(String label, int iterations, long elapsedNanos) {
        System.out.printf("%-30s %,10d evals  %10.1f ns/op  %,14.0f ops/s%n",
                label, iterations, (double) elapsedNanos / iterations, iterations / (elapsedNanos / 1e9));
    }
}
