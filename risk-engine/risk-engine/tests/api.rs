use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use risk_core::{RiskConfig, RiskEngine};
use risk_engine::app::{router, AppState};
use serde_json::{json, Value};
use tower::ServiceExt;

fn app() -> axum::Router {
    router(AppState::new(RiskEngine::new(RiskConfig::default()).unwrap(), 4, 10))
}

async fn send(method: &str, uri: &str, body: Option<Value>) -> (StatusCode, Value) {
    let request = Request::builder()
        .method(method)
        .uri(uri)
        .header("content-type", "application/json")
        .body(body.map_or_else(Body::empty, |b| Body::from(b.to_string())))
        .unwrap();
    let response = app().oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}

#[tokio::test]
async fn health_is_up() {
    let (status, body) = send("GET", "/health", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "UP");
}

#[tokio::test]
async fn evaluate_returns_review_with_explanations() {
    let (status, body) = send(
        "POST",
        "/api/v1/risk/evaluate",
        Some(json!({
            "transactionId": "tx-42",
            "accountNumber": "123456789012",
            "amount": "950.00",
            "balance": "1000.00",
            "velocityCount": 1,
            "averageAmount": "900.00"
        })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["transactionId"], "tx-42");
    assert_eq!(body["decision"], "REVIEW");
    assert_eq!(body["triggeredRules"][0]["code"], "BALANCE_PERCENTAGE");
    assert_eq!(body["updatedAverageAmount"], "925.00");
}

#[tokio::test]
async fn invalid_amount_is_bad_request() {
    let (status, body) = send(
        "POST",
        "/api/v1/risk/evaluate",
        Some(json!({ "accountNumber": "1", "amount": "-5", "balance": "10", "velocityCount": 1 })),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert!(body["error"].as_str().unwrap().contains("amount"));
}

#[tokio::test]
async fn batch_evaluates_in_order_and_reports_item_errors() {
    let item = |amount: &str| json!({ "accountNumber": "1", "amount": amount, "balance": "1000", "velocityCount": 1 });
    let (status, body) = send("POST", "/api/v1/risk/evaluate/batch", Some(json!([item("10"), item("0"), item("990")]))).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["count"], 3);
    assert_eq!(body["results"][0]["assessment"]["decision"], "ALLOW");
    assert_eq!(body["results"][1]["ok"], false);
    assert_eq!(body["results"][2]["assessment"]["decision"], "REVIEW");
}

#[tokio::test]
async fn oversized_batch_is_rejected() {
    let items: Vec<Value> =
        (0..11).map(|_| json!({ "accountNumber": "1", "amount": "1", "balance": "1", "velocityCount": 1 })).collect();
    let (status, _) = send("POST", "/api/v1/risk/evaluate/batch", Some(Value::Array(items))).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE);
}
