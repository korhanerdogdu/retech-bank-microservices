//! Native throughput benchmark for `risk-core`.
//!
//!     cargo run --release -p risk-core --example bench [iterations]
//!
//! Compare with the WASM benchmark in the Risk Simulation Dashboard and the
//! Java `LocalRiskEvaluatorBenchmark` in fraud-detection-service.

use std::hint::black_box;
use std::time::Instant;

use risk_core::{RiskConfig, RiskEngine, TransactionContext};
use rust_decimal::Decimal;

fn main() {
    let iterations: usize = std::env::args().nth(1).and_then(|s| s.parse().ok()).unwrap_or(1_000_000);
    let engine = RiskEngine::new(RiskConfig::default()).expect("valid default config");

    let review = TransactionContext {
        transaction_id: Some("bench".into()),
        account_number: "123456789012".into(),
        amount: Decimal::new(95_000, 2),
        balance: Decimal::new(100_000, 2),
        velocity_count: 2,
        average_amount: Some(Decimal::new(20_000, 2)),
    };

    // Warm-up.
    for _ in 0..10_000 {
        black_box(engine.evaluate(black_box(&review)).unwrap());
    }

    let started = Instant::now();
    for _ in 0..iterations {
        black_box(engine.evaluate(black_box(&review)).unwrap());
    }
    report("native single-thread", iterations, started.elapsed());

    let threads = std::thread::available_parallelism().map(usize::from).unwrap_or(4);
    let batch = vec![review; iterations];
    let started = Instant::now();
    black_box(engine.evaluate_batch(&batch, threads));
    report(&format!("native batch ({threads} threads)"), iterations, started.elapsed());
}

fn report(label: &str, iterations: usize, elapsed: std::time::Duration) {
    let ns_per_op = elapsed.as_nanos() as f64 / iterations as f64;
    let ops_per_sec = iterations as f64 / elapsed.as_secs_f64();
    println!("{label:<32} {iterations:>9} evals  {ns_per_op:>9.1} ns/op  {ops_per_sec:>14.0} ops/s");
}
