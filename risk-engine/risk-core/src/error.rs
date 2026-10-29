use thiserror::Error;

/// Everything that can make an evaluation request invalid.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum RiskError {
    #[error("accountNumber must not be blank")]
    MissingAccountNumber,

    #[error("amount must be greater than zero, got {0}")]
    NonPositiveAmount(String),

    #[error("balance must not be negative, got {0}")]
    NegativeBalance(String),

    #[error("averageAmount must not be negative, got {0}")]
    NegativeAverage(String),

    #[error("invalid risk configuration: {0}")]
    InvalidConfig(String),
}
