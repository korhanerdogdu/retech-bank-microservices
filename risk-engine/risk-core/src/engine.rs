use rust_decimal::{Decimal, RoundingStrategy};

use crate::error::RiskError;
use crate::model::{Decision, RiskAssessment, RiskConfig, TransactionContext, TriggeredRule};
use crate::rules::{AmountRule, BalanceRule, RiskRule, RuleOutcome, VelocityRule};

/// Evaluates a transaction against an ordered set of rules.
///
/// Rule order matters: the first triggered rule provides the customer-facing
/// `reason`, exactly like the original Java implementation
/// (velocity → amount → balance).
pub struct RiskEngine {
    config: RiskConfig,
    rules: Vec<Box<dyn RiskRule>>,
}

impl RiskEngine {
    /// Engine with the three standard banking rules.
    pub fn new(config: RiskConfig) -> Result<Self, RiskError> {
        Self::with_rules(
            config,
            vec![Box::new(VelocityRule), Box::new(AmountRule), Box::new(BalanceRule)],
        )
    }

    pub fn with_rules(config: RiskConfig, rules: Vec<Box<dyn RiskRule>>) -> Result<Self, RiskError> {
        config.validate()?;
        Ok(Self { config, rules })
    }

    pub fn config(&self) -> &RiskConfig {
        &self.config
    }

    pub fn evaluate(&self, ctx: &TransactionContext) -> Result<RiskAssessment<'_>, RiskError> {
        ctx.validate()?;

        let triggered: Vec<TriggeredRule<'_>> = self
            .rules
            .iter()
            .filter_map(|rule| match rule.evaluate(ctx, &self.config) {
                RuleOutcome::Pass => None,
                RuleOutcome::Triggered { message } => Some(TriggeredRule {
                    code: rule.code(),
                    title: rule.title(),
                    message,
                }),
            })
            .collect();

        let decision = self.decide(ctx, &triggered);
        let reason = triggered.first().map(|rule| rule.message.clone());

        Ok(RiskAssessment {
            transaction_id: ctx.transaction_id.clone(),
            decision,
            reason,
            updated_average_amount: self.next_average(ctx),
            triggered_rules: triggered,
        })
    }

    /// Evaluates many transactions in parallel on scoped OS threads. The
    /// borrowed slice and engine are shared safely across threads because
    /// `std::thread::scope` guarantees every thread joins before returning.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn evaluate_batch(
        &self,
        contexts: &[TransactionContext],
        max_threads: usize,
    ) -> Vec<Result<RiskAssessment<'_>, RiskError>> {
        if contexts.is_empty() {
            return Vec::new();
        }
        let threads = max_threads.clamp(1, contexts.len());
        let chunk_size = contexts.len().div_ceil(threads);

        std::thread::scope(|scope| {
            let handles: Vec<_> = contexts
                .chunks(chunk_size)
                .map(|chunk| scope.spawn(move || chunk.iter().map(|ctx| self.evaluate(ctx)).collect::<Vec<_>>()))
                .collect();

            handles
                .into_iter()
                .flat_map(|handle| handle.join().expect("risk evaluation thread panicked"))
                .collect()
        })
    }

    fn decide(&self, ctx: &TransactionContext, triggered: &[TriggeredRule<'_>]) -> Decision {
        if triggered.is_empty() {
            return Decision::Allow;
        }
        let block_velocity = self
            .config
            .max_transactions_per_minute
            .saturating_mul(self.config.block_velocity_multiplier);

        if ctx.velocity_count > block_velocity || triggered.len() >= self.config.block_min_triggered_rules {
            Decision::Block
        } else {
            Decision::Review
        }
    }

    /// Running average as maintained by the original Java service:
    /// seeded with the first amount, then `(avg + amount) / 2` rounded
    /// HALF_UP to 2 decimals. It is left untouched when the velocity rule
    /// fires, because the original implementation short-circuited there.
    fn next_average(&self, ctx: &TransactionContext) -> Option<Decimal> {
        if ctx.velocity_count > self.config.max_transactions_per_minute {
            return None;
        }
        Some(match ctx.average_amount {
            None => ctx.amount,
            Some(avg) => ((avg + ctx.amount) / Decimal::TWO)
                .round_dp_with_strategy(2, RoundingStrategy::MidpointAwayFromZero),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal_macros::dec;

    fn engine() -> RiskEngine {
        RiskEngine::new(RiskConfig::default()).unwrap()
    }

    fn ctx(amount: Decimal, balance: Decimal, velocity: u32, avg: Option<Decimal>) -> TransactionContext {
        TransactionContext {
            transaction_id: Some("tx-1".into()),
            account_number: "000000000001".into(),
            amount,
            balance,
            velocity_count: velocity,
            average_amount: avg,
        }
    }

    #[test]
    fn clean_transaction_is_allowed() {
        let engine = engine();
        let result = engine.evaluate(&ctx(dec!(100), dec!(1000), 1, Some(dec!(100)))).unwrap();
        assert_eq!(result.decision, Decision::Allow);
        assert!(result.triggered_rules.is_empty());
        assert_eq!(result.reason, None);
        assert_eq!(result.updated_average_amount, Some(dec!(100)));
    }

    #[test]
    fn first_transaction_seeds_average_without_amount_check() {
        let engine = engine();
        let result = engine.evaluate(&ctx(dec!(500), dec!(10000), 1, None)).unwrap();
        assert_eq!(result.decision, Decision::Allow);
        assert_eq!(result.updated_average_amount, Some(dec!(500)));
    }

    #[test]
    fn single_rule_requires_review_with_reason_of_first_rule() {
        let engine = engine();
        let result = engine.evaluate(&ctx(dec!(950), dec!(1000), 1, Some(dec!(900)))).unwrap();
        assert_eq!(result.decision, Decision::Review);
        assert_eq!(result.triggered_rules.len(), 1);
        assert_eq!(result.triggered_rules[0].code, "BALANCE_PERCENTAGE");
        assert_eq!(result.reason.as_deref(), Some("Transaction exceeds 90% of account balance"));
    }

    #[test]
    fn reason_follows_rule_order() {
        let engine = engine();
        let result = engine.evaluate(&ctx(dec!(950), dec!(1000), 6, Some(dec!(100)))).unwrap();
        assert_eq!(result.triggered_rules.len(), 3);
        assert_eq!(result.triggered_rules[0].code, "VELOCITY");
        assert_eq!(
            result.reason.as_deref(),
            Some("Too many transactions in 60 seconds - Velocity limit exceeded")
        );
    }

    #[test]
    fn all_rules_or_extreme_velocity_block() {
        let engine = engine();
        assert_eq!(
            engine.evaluate(&ctx(dec!(950), dec!(1000), 6, Some(dec!(100)))).unwrap().decision,
            Decision::Block
        );
        assert_eq!(engine.evaluate(&ctx(dec!(10), dec!(1000), 11, Some(dec!(10)))).unwrap().decision, Decision::Block);
        assert_eq!(engine.evaluate(&ctx(dec!(10), dec!(1000), 10, Some(dec!(10)))).unwrap().decision, Decision::Review);
    }

    #[test]
    fn running_average_rounds_half_up_and_freezes_on_velocity() {
        let engine = engine();
        let result = engine.evaluate(&ctx(dec!(100.01), dec!(1000), 1, Some(dec!(100.00)))).unwrap();
        assert_eq!(result.updated_average_amount, Some(dec!(100.01))); // 100.005 -> 100.01
        let result = engine.evaluate(&ctx(dec!(100), dec!(1000), 6, Some(dec!(50)))).unwrap();
        assert_eq!(result.updated_average_amount, None);
    }

    #[test]
    fn invalid_input_is_rejected() {
        let engine = engine();
        assert!(matches!(
            engine.evaluate(&ctx(dec!(0), dec!(1000), 1, None)),
            Err(RiskError::NonPositiveAmount(_))
        ));
        assert!(matches!(
            engine.evaluate(&ctx(dec!(1), dec!(-1), 1, None)),
            Err(RiskError::NegativeBalance(_))
        ));
        let mut blank = ctx(dec!(1), dec!(1), 1, None);
        blank.account_number = "  ".into();
        assert_eq!(engine.evaluate(&blank), Err(RiskError::MissingAccountNumber));
    }

    #[test]
    fn invalid_config_is_rejected() {
        let config = RiskConfig { max_balance_percentage: dec!(1.5), ..RiskConfig::default() };
        assert!(RiskEngine::new(config).is_err());
    }

    #[test]
    fn batch_matches_sequential_and_preserves_order() {
        let engine = engine();
        let contexts: Vec<_> = (1..=200u32)
            .map(|i| ctx(Decimal::from(i * 10), dec!(1000), i % 12, Some(dec!(100))))
            .collect();
        let sequential: Vec<_> = contexts.iter().map(|c| engine.evaluate(c)).collect();
        assert_eq!(engine.evaluate_batch(&contexts, 8), sequential);
        assert!(engine.evaluate_batch(&[], 8).is_empty());
    }

    #[test]
    fn json_contract_uses_camel_case_and_string_money() {
        let input = r#"{"transactionId":"t","accountNumber":"1","amount":"10.50","balance":100,"velocityCount":1}"#;
        let context: TransactionContext = serde_json::from_str(input).unwrap();
        let engine = engine();
        let json = serde_json::to_value(engine.evaluate(&context).unwrap()).unwrap();
        assert_eq!(json["decision"], "ALLOW");
        assert_eq!(json["updatedAverageAmount"], "10.50");
        assert!(json["triggeredRules"].as_array().unwrap().is_empty());
    }
}
