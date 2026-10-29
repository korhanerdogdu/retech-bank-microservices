use std::fmt::Display;
use std::net::SocketAddr;
use std::str::FromStr;

use risk_core::RiskConfig;
use thiserror::Error;

#[derive(Debug, Error)]
#[error("environment variable {key}: {message}")]
pub struct ConfigError {
    key: &'static str,
    message: String,
}

/// Service settings, read from environment variables with defaults that
/// match the Java Fraud Detection Service's `fraud.*` properties.
#[derive(Debug, Clone)]
pub struct Settings {
    pub addr: SocketAddr,
    pub batch_threads: usize,
    pub max_batch_size: usize,
    pub risk: RiskConfig,
}

impl Settings {
    pub fn from_env() -> Result<Self, ConfigError> {
        let defaults = RiskConfig::default();
        let cpus = std::thread::available_parallelism().map(usize::from).unwrap_or(2);
        let port: u16 = env_or("PORT", 8086)?;

        Ok(Self {
            addr: SocketAddr::from(([0, 0, 0, 0], port)),
            batch_threads: env_or("RISK_BATCH_THREADS", cpus)?,
            max_batch_size: env_or("RISK_MAX_BATCH_SIZE", 1000)?,
            risk: RiskConfig {
                max_transactions_per_minute: env_or(
                    "RISK_MAX_TRANSACTIONS_PER_MINUTE",
                    defaults.max_transactions_per_minute,
                )?,
                suspicious_amount_multiplier: env_or(
                    "RISK_SUSPICIOUS_AMOUNT_MULTIPLIER",
                    defaults.suspicious_amount_multiplier,
                )?,
                max_balance_percentage: env_or("RISK_MAX_BALANCE_PERCENTAGE", defaults.max_balance_percentage)?,
                block_velocity_multiplier: env_or(
                    "RISK_BLOCK_VELOCITY_MULTIPLIER",
                    defaults.block_velocity_multiplier,
                )?,
                block_min_triggered_rules: env_or(
                    "RISK_BLOCK_MIN_TRIGGERED_RULES",
                    defaults.block_min_triggered_rules,
                )?,
            },
        })
    }
}

fn env_or<T>(key: &'static str, default: T) -> Result<T, ConfigError>
where
    T: FromStr,
    T::Err: Display,
{
    match std::env::var(key) {
        Ok(raw) if !raw.trim().is_empty() => raw.trim().parse().map_err(|e: T::Err| ConfigError {
            key,
            message: format!("cannot parse {raw:?}: {e}"),
        }),
        _ => Ok(default),
    }
}
