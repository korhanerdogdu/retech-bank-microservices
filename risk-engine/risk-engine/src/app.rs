use std::sync::Arc;
use std::time::Instant;

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use risk_core::{RiskEngine, RiskError, TransactionContext};
use serde_json::json;
use tower_http::trace::TraceLayer;

use crate::metrics::Metrics;

/// Shared, immutable application state. The engine is never mutated after
/// start-up, so an `Arc` is all that is needed to share it across Tokio
/// worker threads — no locks.
#[derive(Clone)]
pub struct AppState {
    inner: Arc<Inner>,
}

struct Inner {
    engine: RiskEngine,
    metrics: Metrics,
    batch_threads: usize,
    max_batch_size: usize,
}

impl AppState {
    pub fn new(engine: RiskEngine, batch_threads: usize, max_batch_size: usize) -> Self {
        Self {
            inner: Arc::new(Inner { engine, metrics: Metrics::default(), batch_threads, max_batch_size }),
        }
    }
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/metrics", get(metrics))
        .route("/api/v1/risk/config", get(config))
        .route("/api/v1/risk/evaluate", post(evaluate))
        .route("/api/v1/risk/evaluate/batch", post(evaluate_batch))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

enum ApiError {
    Invalid(RiskError),
    BatchTooLarge { size: usize, max: usize },
    Internal(String),
}

impl From<RiskError> for ApiError {
    fn from(error: RiskError) -> Self {
        ApiError::Invalid(error)
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            ApiError::Invalid(error) => (StatusCode::BAD_REQUEST, error.to_string()),
            ApiError::BatchTooLarge { size, max } => (
                StatusCode::PAYLOAD_TOO_LARGE,
                format!("batch of {size} exceeds the maximum of {max}"),
            ),
            ApiError::Internal(message) => (StatusCode::INTERNAL_SERVER_ERROR, message),
        };
        (status, Json(json!({ "error": message }))).into_response()
    }
}

async fn health() -> Json<serde_json::Value> {
    Json(json!({ "status": "UP" }))
}

async fn metrics(State(state): State<AppState>) -> impl IntoResponse {
    Json(state.inner.metrics.snapshot())
}

async fn config(State(state): State<AppState>) -> impl IntoResponse {
    Json(state.inner.engine.config().clone())
}

async fn evaluate(
    State(state): State<AppState>,
    Json(ctx): Json<TransactionContext>,
) -> Result<Response, ApiError> {
    let inner = &state.inner;
    let started = Instant::now();

    let assessment = inner.engine.evaluate(&ctx).inspect_err(|_| inner.metrics.record_rejected())?;
    inner.metrics.record(assessment.decision, elapsed_nanos(started));

    tracing::info!(
        transaction_id = ?assessment.transaction_id,
        decision = ?assessment.decision,
        triggered = assessment.triggered_rules.len(),
        "risk evaluated"
    );
    // The assessment borrows from the engine; serialize it while the borrow is alive.
    Ok(Json(assessment).into_response())
}

/// CPU-bound batch evaluation runs on the blocking pool and fans out over
/// scoped OS threads, keeping the async runtime responsive.
async fn evaluate_batch(
    State(state): State<AppState>,
    Json(contexts): Json<Vec<TransactionContext>>,
) -> Result<Response, ApiError> {
    let max = state.inner.max_batch_size;
    if contexts.len() > max {
        return Err(ApiError::BatchTooLarge { size: contexts.len(), max });
    }

    let body = tokio::task::spawn_blocking(move || {
        let inner = &state.inner;
        let started = Instant::now();
        let results = inner.engine.evaluate_batch(&contexts, inner.batch_threads);
        let per_item = elapsed_nanos(started) / results.len().max(1) as u64;

        let items: Vec<serde_json::Value> = results
            .iter()
            .map(|result| match result {
                Ok(assessment) => {
                    inner.metrics.record(assessment.decision, per_item);
                    json!({ "ok": true, "assessment": assessment })
                }
                Err(error) => {
                    inner.metrics.record_rejected();
                    json!({ "ok": false, "error": error.to_string() })
                }
            })
            .collect();
        json!({ "count": items.len(), "results": items })
    })
    .await
    .map_err(|e| ApiError::Internal(format!("batch evaluation failed: {e}")))?;

    Ok(Json(body).into_response())
}

fn elapsed_nanos(started: Instant) -> u64 {
    u64::try_from(started.elapsed().as_nanos()).unwrap_or(u64::MAX)
}
