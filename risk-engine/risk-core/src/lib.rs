//! `risk-core` — the fraud risk rules of the Digital Banking System.
//!
//! The crate is pure and I/O-free: the caller (the Java Fraud Detection
//! Service, via the native `risk-engine` HTTP service, or the browser via
//! `risk-wasm`) gathers the facts about a transaction — velocity count,
//! running average and pre-transfer balance — and the engine turns them into
//! an `ALLOW` / `REVIEW` / `BLOCK` decision with explanations.
//!
//! The same code is compiled natively and to WebAssembly, so the simulator
//! and the backend can never disagree about what a rule does.

mod engine;
mod error;
mod model;
mod rules;

pub use engine::RiskEngine;
pub use error::RiskError;
pub use model::{Decision, RiskAssessment, RiskConfig, TransactionContext, TriggeredRule};
pub use rules::{AmountRule, BalanceRule, RiskRule, RuleOutcome, VelocityRule};
