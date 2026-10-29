use std::sync::atomic::{AtomicU64, Ordering};

use risk_core::Decision;
use serde::Serialize;

/// Lock-free counters shared by every request handler.
#[derive(Debug, Default)]
pub struct Metrics {
    evaluations: AtomicU64,
    allow: AtomicU64,
    review: AtomicU64,
    block: AtomicU64,
    rejected: AtomicU64,
    total_eval_nanos: AtomicU64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MetricsSnapshot {
    pub evaluations: u64,
    pub allow: u64,
    pub review: u64,
    pub block: u64,
    pub rejected: u64,
    pub average_eval_nanos: u64,
}

impl Metrics {
    pub fn record(&self, decision: Decision, nanos: u64) {
        self.evaluations.fetch_add(1, Ordering::Relaxed);
        self.total_eval_nanos.fetch_add(nanos, Ordering::Relaxed);
        let counter = match decision {
            Decision::Allow => &self.allow,
            Decision::Review => &self.review,
            Decision::Block => &self.block,
        };
        counter.fetch_add(1, Ordering::Relaxed);
    }

    pub fn record_rejected(&self) {
        self.rejected.fetch_add(1, Ordering::Relaxed);
    }

    pub fn snapshot(&self) -> MetricsSnapshot {
        let evaluations = self.evaluations.load(Ordering::Relaxed);
        let total = self.total_eval_nanos.load(Ordering::Relaxed);
        MetricsSnapshot {
            evaluations,
            allow: self.allow.load(Ordering::Relaxed),
            review: self.review.load(Ordering::Relaxed),
            block: self.block.load(Ordering::Relaxed),
            rejected: self.rejected.load(Ordering::Relaxed),
            average_eval_nanos: total.checked_div(evaluations).unwrap_or(0),
        }
    }
}
