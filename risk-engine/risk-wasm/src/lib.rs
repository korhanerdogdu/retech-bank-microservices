//! WebAssembly bindings for the Risk Simulation Dashboard.
//!
//! This module runs in the browser for **simulation and testing only**. It
//! has no access to accounts, Redis or Kafka, and nothing it returns is ever
//! trusted by the backend — real decisions are made by the native
//! `risk-engine` service.

use risk_core::{RiskConfig, RiskEngine, TransactionContext};
use serde::Serialize;
use wasm_bindgen::prelude::*;

#[wasm_bindgen]
pub struct WasmRiskEngine {
    inner: RiskEngine,
}

#[wasm_bindgen]
impl WasmRiskEngine {
    /// Creates an engine from a (partial) config object; missing fields take
    /// the backend defaults.
    #[wasm_bindgen(constructor)]
    pub fn new(config: JsValue) -> Result<WasmRiskEngine, JsError> {
        let config: RiskConfig = if config.is_undefined() || config.is_null() {
            RiskConfig::default()
        } else {
            serde_wasm_bindgen::from_value(config).map_err(to_js_error)?
        };
        let inner = RiskEngine::new(config).map_err(to_js_error)?;
        Ok(Self { inner })
    }

    /// Evaluates one simulated transaction and returns the full assessment
    /// (decision, reason, triggered rules, updated average).
    pub fn evaluate(&self, context: JsValue) -> Result<JsValue, JsError> {
        let context: TransactionContext = serde_wasm_bindgen::from_value(context).map_err(to_js_error)?;
        let assessment = self.inner.evaluate(&context).map_err(to_js_error)?;
        to_js(&assessment)
    }

    /// Runs `iterations` evaluations entirely inside WebAssembly and returns
    /// how many produced a non-ALLOW decision. JavaScript times the call to
    /// measure WASM throughput without per-call boundary overhead.
    pub fn benchmark(&self, context: JsValue, iterations: u32) -> Result<u32, JsError> {
        let context: TransactionContext = serde_wasm_bindgen::from_value(context).map_err(to_js_error)?;
        let mut flagged = 0;
        for _ in 0..iterations {
            let assessment = self.inner.evaluate(&context).map_err(to_js_error)?;
            if assessment.decision != risk_core::Decision::Allow {
                flagged += 1;
            }
        }
        Ok(flagged)
    }

    pub fn config(&self) -> Result<JsValue, JsError> {
        to_js(self.inner.config())
    }
}

#[wasm_bindgen(js_name = defaultConfig)]
pub fn default_config() -> Result<JsValue, JsError> {
    to_js(&RiskConfig::default())
}

fn to_js<T: Serialize + ?Sized>(value: &T) -> Result<JsValue, JsError> {
    value
        .serialize(&serde_wasm_bindgen::Serializer::json_compatible())
        .map_err(to_js_error)
}

fn to_js_error(error: impl std::fmt::Display) -> JsError {
    JsError::new(&error.to_string())
}
