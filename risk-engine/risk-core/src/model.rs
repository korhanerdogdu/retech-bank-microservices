use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use crate::error::RiskError;

/// Facts about a single transfer, collected by the caller before evaluation.
///
/// Monetary values are `Decimal` (never floating point) and are serialized as
/// JSON strings so no precision is lost between Java `BigDecimal` and Rust.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransactionContext {
    #[serde(default)]
    pub transaction_id: Option<String>,
    pub account_number: String,
    pub amount: Decimal,
    /// Sender balance *before* this transfer was deducted.
    pub balance: Decimal,
    /// Transactions seen for this account in the current 60s window,
    /// including this one.
    pub velocity_count: u32,
    /// Running average of previous transfers; `None` when there is no history.
    #[serde(default)]
    pub average_amount: Option<Decimal>,
}

impl TransactionContext {
    pub fn validate(&self) -> Result<(), RiskError> {
        if self.account_number.trim().is_empty() {
            return Err(RiskError::MissingAccountNumber);
        }
        if self.amount <= Decimal::ZERO {
            return Err(RiskError::NonPositiveAmount(self.amount.to_string()));
        }
        if self.balance < Decimal::ZERO {
            return Err(RiskError::NegativeBalance(self.balance.to_string()));
        }
        match self.average_amount {
            Some(avg) if avg < Decimal::ZERO => Err(RiskError::NegativeAverage(avg.to_string())),
            _ => Ok(()),
        }
    }
}

/// Rule thresholds. Defaults mirror `fraud.*` in the Java service's
/// `application.yml`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RiskConfig {
    pub max_transactions_per_minute: u32,
    pub suspicious_amount_multiplier: Decimal,
    pub max_balance_percentage: Decimal,
    /// Velocity above `max_transactions_per_minute * block_velocity_multiplier`
    /// is treated as automated abuse and escalates to `BLOCK`.
    pub block_velocity_multiplier: u32,
    /// Number of simultaneously triggered rules that escalates to `BLOCK`.
    pub block_min_triggered_rules: usize,
}

impl Default for RiskConfig {
    fn default() -> Self {
        Self {
            max_transactions_per_minute: 5,
            suspicious_amount_multiplier: Decimal::new(30, 1),
            max_balance_percentage: Decimal::new(90, 2),
            block_velocity_multiplier: 2,
            block_min_triggered_rules: 3,
        }
    }
}

impl RiskConfig {
    pub fn validate(&self) -> Result<(), RiskError> {
        if self.max_transactions_per_minute == 0 {
            return Err(RiskError::InvalidConfig("maxTransactionsPerMinute must be > 0".into()));
        }
        if self.suspicious_amount_multiplier <= Decimal::ONE {
            return Err(RiskError::InvalidConfig("suspiciousAmountMultiplier must be > 1".into()));
        }
        if self.max_balance_percentage <= Decimal::ZERO || self.max_balance_percentage > Decimal::ONE {
            return Err(RiskError::InvalidConfig("maxBalancePercentage must be in (0, 1]".into()));
        }
        if self.block_velocity_multiplier < 1 {
            return Err(RiskError::InvalidConfig("blockVelocityMultiplier must be >= 1".into()));
        }
        if self.block_min_triggered_rules < 1 {
            return Err(RiskError::InvalidConfig("blockMinTriggeredRules must be >= 1".into()));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Decision {
    /// No rule triggered — the transfer completes.
    Allow,
    /// Suspicious — step-up OTP verification is required.
    Review,
    /// Clear abuse — cancel, refund and block the account.
    Block,
}

/// A rule that fired. `code` and `title` borrow from the rule inside the
/// engine, so an assessment cannot outlive the engine that produced it.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TriggeredRule<'a> {
    pub code: &'a str,
    pub title: &'a str,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RiskAssessment<'a> {
    pub transaction_id: Option<String>,
    pub decision: Decision,
    /// Message of the first triggered rule (the one shown to the customer).
    pub reason: Option<String>,
    pub triggered_rules: Vec<TriggeredRule<'a>>,
    /// New running average to persist; `None` means "leave it unchanged".
    pub updated_average_amount: Option<Decimal>,
}
