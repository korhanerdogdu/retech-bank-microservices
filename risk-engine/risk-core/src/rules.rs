use rust_decimal::Decimal;

use crate::model::{RiskConfig, TransactionContext};

/// Result of running one rule against one transaction.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuleOutcome {
    Pass,
    Triggered { message: String },
}

/// A single fraud rule. Rules are stateless and `Send + Sync`, so one engine
/// can be shared across threads behind an `Arc`.
pub trait RiskRule: Send + Sync {
    /// Stable machine-readable identifier, e.g. `VELOCITY`.
    fn code(&self) -> &str;
    fn title(&self) -> &str;
    fn evaluate(&self, ctx: &TransactionContext, config: &RiskConfig) -> RuleOutcome;
}

/// Pattern 1 — more than N transfers within the 60 second window.
#[derive(Debug, Default)]
pub struct VelocityRule;

impl RiskRule for VelocityRule {
    fn code(&self) -> &str {
        "VELOCITY"
    }

    fn title(&self) -> &str {
        "Transaction velocity"
    }

    fn evaluate(&self, ctx: &TransactionContext, config: &RiskConfig) -> RuleOutcome {
        if ctx.velocity_count > config.max_transactions_per_minute {
            RuleOutcome::Triggered {
                message: "Too many transactions in 60 seconds - Velocity limit exceeded".into(),
            }
        } else {
            RuleOutcome::Pass
        }
    }
}

/// Pattern 2 — amount is more than `multiplier` times the running average.
#[derive(Debug, Default)]
pub struct AmountRule;

impl RiskRule for AmountRule {
    fn code(&self) -> &str {
        "UNUSUAL_AMOUNT"
    }

    fn title(&self) -> &str {
        "Unusual transaction amount"
    }

    fn evaluate(&self, ctx: &TransactionContext, config: &RiskConfig) -> RuleOutcome {
        // No history yet: the first transfer only seeds the average.
        let Some(average) = ctx.average_amount else {
            return RuleOutcome::Pass;
        };
        let threshold = average * config.suspicious_amount_multiplier;
        if ctx.amount > threshold {
            RuleOutcome::Triggered {
                message: format!(
                    "Unusual transaction amount - exceeds {}x your average",
                    config.suspicious_amount_multiplier.normalize()
                ),
            }
        } else {
            RuleOutcome::Pass
        }
    }
}

/// Pattern 3 — amount is more than `percentage` of the pre-transfer balance.
#[derive(Debug, Default)]
pub struct BalanceRule;

impl RiskRule for BalanceRule {
    fn code(&self) -> &str {
        "BALANCE_PERCENTAGE"
    }

    fn title(&self) -> &str {
        "Balance percentage"
    }

    fn evaluate(&self, ctx: &TransactionContext, config: &RiskConfig) -> RuleOutcome {
        if ctx.balance <= Decimal::ZERO {
            return RuleOutcome::Pass;
        }
        let max_allowed = ctx.balance * config.max_balance_percentage;
        if ctx.amount > max_allowed {
            RuleOutcome::Triggered {
                message: format!(
                    "Transaction exceeds {}% of account balance",
                    (config.max_balance_percentage * Decimal::ONE_HUNDRED).normalize()
                ),
            }
        } else {
            RuleOutcome::Pass
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal_macros::dec;

    fn ctx(amount: Decimal, balance: Decimal, velocity: u32, avg: Option<Decimal>) -> TransactionContext {
        TransactionContext {
            transaction_id: None,
            account_number: "000000000001".into(),
            amount,
            balance,
            velocity_count: velocity,
            average_amount: avg,
        }
    }

    #[test]
    fn velocity_triggers_only_above_limit() {
        let cfg = RiskConfig::default();
        assert_eq!(VelocityRule.evaluate(&ctx(dec!(10), dec!(1000), 5, None), &cfg), RuleOutcome::Pass);
        assert!(matches!(
            VelocityRule.evaluate(&ctx(dec!(10), dec!(1000), 6, None), &cfg),
            RuleOutcome::Triggered { .. }
        ));
    }

    #[test]
    fn amount_rule_needs_history_and_is_strictly_greater() {
        let cfg = RiskConfig::default();
        assert_eq!(AmountRule.evaluate(&ctx(dec!(5000), dec!(10000), 1, None), &cfg), RuleOutcome::Pass);
        assert_eq!(AmountRule.evaluate(&ctx(dec!(300), dec!(10000), 1, Some(dec!(100))), &cfg), RuleOutcome::Pass);
        assert_eq!(
            AmountRule.evaluate(&ctx(dec!(300.01), dec!(10000), 1, Some(dec!(100))), &cfg),
            RuleOutcome::Triggered { message: "Unusual transaction amount - exceeds 3x your average".into() }
        );
    }

    #[test]
    fn balance_rule_uses_percentage_and_ignores_empty_balance() {
        let cfg = RiskConfig::default();
        assert_eq!(BalanceRule.evaluate(&ctx(dec!(900), dec!(1000), 1, None), &cfg), RuleOutcome::Pass);
        assert_eq!(
            BalanceRule.evaluate(&ctx(dec!(900.01), dec!(1000), 1, None), &cfg),
            RuleOutcome::Triggered { message: "Transaction exceeds 90% of account balance".into() }
        );
        assert_eq!(BalanceRule.evaluate(&ctx(dec!(50), dec!(0), 1, None), &cfg), RuleOutcome::Pass);
    }
}
