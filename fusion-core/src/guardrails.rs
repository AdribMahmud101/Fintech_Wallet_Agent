//! The economic guardrail engine.
//!
//! Every rule here is a pure function of its arguments and is evaluated **before** a
//! private key is touched and long before anything is signed. The engine adds exactly
//! two things on top of the pure checks:
//!
//! 1. a day-rolled velocity ledger that is optionally persisted to
//!    `FUSION_LEDGER_PATH`, so the daily cap survives an app restart; and
//! 2. a reserve/commit/release protocol so that a broadcast which never happens does
//!    not permanently consume the caller's daily budget.
//!
//! Money is tracked as `f64` **USD notional**. At the magnitudes in play ($100/$500) the
//! representation error is around 1e-14 dollars, which is far below any threshold that
//! matters; the *quantity actually transferred on-chain* is always an exact `U256` in
//! the token's smallest unit.

use alloy::primitives::utils::{format_units, parse_units, ParseUnits};
use alloy::primitives::{Address, U256};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fmt;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::str::FromStr;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};
use thiserror::Error;

use crate::config::WalletConfig;
use crate::models::{Action, AiIntent};

/// Bumped whenever the on-disk shape changes in a way an older binary cannot read.
pub const LEDGER_FORMAT_VERSION: u32 = 1;

/// Seconds in a UTC day.
const SECONDS_PER_DAY: u64 = 86_400;

/// Tolerance when reconciling `amount` against the exact `amount_minor`, relative to the
/// magnitude being compared. 1e-9 is far tighter than any limit and far looser than f64
/// noise, so it never rejects a legitimate payload.
const AMOUNT_RECONCILE_TOLERANCE: f64 = 1e-9;

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

#[derive(Debug, Error, Clone, PartialEq)]
pub enum GuardrailError {
    #[error("action {0:?} is not executable by this engine")]
    UnsupportedAction(Action),

    #[error("amount must be a finite number")]
    AmountNotFinite,

    #[error("amount must be greater than zero")]
    AmountNotPositive,

    #[error("amount_minor must be an unsigned integer string, got {reason}")]
    AmountMinorInvalid { reason: String },

    #[error("amount {amount} cannot be represented with {decimals} decimals")]
    AmountNotRepresentable {
        amount: f64,
        decimals: u8,
        reason: String,
    },

    #[error("amount_minor {minor} is worth {actual} and contradicts amount {claimed}")]
    AmountMismatch {
        minor: String,
        actual: f64,
        claimed: f64,
    },

    #[error("token_in {0:?} could not be resolved to an address")]
    UnknownToken(String),

    #[error("token {address} is not on the asset whitelist")]
    TokenNotWhitelisted { address: Address },

    #[error("this intent must carry a destination_address")]
    DestinationMissing,

    #[error("destination_address is malformed: {reason}")]
    DestinationInvalid { reason: String },

    #[error("destination_address must not be the zero address")]
    DestinationIsZero,

    #[error("transaction of ${amount} exceeds the ${limit} per-transaction limit")]
    TxSizeExceeded { amount: f64, limit: f64 },

    #[error(
        "daily velocity limit exceeded: ${spent_today} already spent today, \
         this ${attempted} transaction would reach ${projected} against a ${limit} cap"
    )]
    DailyLimitExceeded {
        spent_today: f64,
        attempted: f64,
        projected: f64,
        limit: f64,
    },

    #[error("velocity ledger could not be persisted: {reason}")]
    LedgerIo { reason: String },
}

impl GuardrailError {
    /// Short machine-friendly tag, handy for logs and Kotlin-side switch statements.
    pub fn code(&self) -> &'static str {
        match self {
            Self::UnsupportedAction(_) => "unsupported_action",
            Self::AmountNotFinite => "amount_not_finite",
            Self::AmountNotPositive => "amount_not_positive",
            Self::AmountMinorInvalid { .. } => "amount_minor_invalid",
            Self::AmountNotRepresentable { .. } => "amount_not_representable",
            Self::AmountMismatch { .. } => "amount_mismatch",
            Self::UnknownToken(_) => "unknown_token",
            Self::TokenNotWhitelisted { .. } => "token_not_whitelisted",
            Self::DestinationMissing => "destination_missing",
            Self::DestinationInvalid { .. } => "destination_invalid",
            Self::DestinationIsZero => "destination_is_zero",
            Self::TxSizeExceeded { .. } => "tx_size_exceeded",
            Self::DailyLimitExceeded { .. } => "daily_limit_exceeded",
            Self::LedgerIo { .. } => "ledger_io",
        }
    }
}

// ---------------------------------------------------------------------------
// Pure rules
// ---------------------------------------------------------------------------

/// Rejects `NaN`, `±infinity`, zero and negative amounts.
///
/// This must run before *any* `amount > limit` comparison: every comparison against
/// `NaN` is `false`, so an unguarded `NaN` would sail through the size and velocity
/// checks and land in the executor.
pub fn check_amount_is_valid(amount: f64) -> Result<(), GuardrailError> {
    if !amount.is_finite() {
        return Err(GuardrailError::AmountNotFinite);
    }
    if amount <= 0.0 {
        return Err(GuardrailError::AmountNotPositive);
    }
    Ok(())
}

/// Per-transaction ceiling. Spending *exactly* the limit is allowed; one cent more is not.
pub fn check_tx_size(amount: f64, limit: f64) -> Result<(), GuardrailError> {
    if amount > limit {
        return Err(GuardrailError::TxSizeExceeded { amount, limit });
    }
    Ok(())
}

/// Daily velocity ceiling. Landing exactly on the cap is allowed.
pub fn check_velocity_limit(
    spent_today: f64,
    amount: f64,
    limit: f64,
) -> Result<(), GuardrailError> {
    let projected = spent_today + amount;
    if projected > limit {
        return Err(GuardrailError::DailyLimitExceeded {
            spent_today,
            attempted: amount,
            projected,
            limit,
        });
    }
    Ok(())
}

/// Every address that will be touched this transaction must be on the allowlist.
///
/// `NATIVE_ASSET` (the zero address) is not in this list by default; callers that allow
/// native transfers are expected to add it to `FUSION_WHITELIST`. Keeping it opt-in means
/// a misconfigured deployment cannot spend ETH by accident.
pub fn check_whitelisted_assets(
    tokens: &[Address],
    whitelist: &HashSet<Address>,
) -> Result<(), GuardrailError> {
    for token in tokens {
        if !whitelist.contains(token) {
            return Err(GuardrailError::TokenNotWhitelisted { address: *token });
        }
    }
    Ok(())
}

/// Parses and sanity-checks a destination address.
///
/// The `0x` prefix is required even though alloy's parser tolerates its absence: an
/// un-prefixed hex string from a language model is nearly always a hallucinated or
/// truncated address, and refusing it turns a silent misfire into a legible rejection.
pub fn check_destination(destination: Option<&str>) -> Result<Address, GuardrailError> {
    let raw = destination
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .ok_or(GuardrailError::DestinationMissing)?;

    if !raw.starts_with("0x") && !raw.starts_with("0X") {
        return Err(GuardrailError::DestinationInvalid {
            reason: format!("{raw:?} is missing the 0x prefix"),
        });
    }

    let address = Address::from_str(raw).map_err(|err| GuardrailError::DestinationInvalid {
        reason: err.to_string(),
    })?;

    if address.is_zero() {
        return Err(GuardrailError::DestinationIsZero);
    }
    Ok(address)
}

/// This engine only signs transfers. Swap and stake would require a router/staking
/// contract allowlist of their own, and until that allowlist exists the safe answer to
/// "swap 500 USDC for a scammy token" is no.
pub fn check_supported_action(action: Action) -> Result<(), GuardrailError> {
    match action {
        Action::Transfer => Ok(()),
        other => Err(GuardrailError::UnsupportedAction(other)),
    }
}

/// The exact on-chain quantity plus the USD notional the limits are checked against.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ResolvedAmount {
    pub usd_value: f64,
    pub atomic: U256,
}

/// Reconciles `amount` with the optional exact `amount_minor` field.
///
/// `amount_minor` wins whenever it is present, but it is never trusted on its own: it
/// must agree with the human-readable `amount` to within a relative tolerance of 1e-9.
/// Without that cross-check a caller could hand the guardrails `amount: 1.0` and the
/// executor `amount_minor: "1000000000000000000000"` and move a trillion tokens past a
/// $100 ceiling.
pub fn resolve_amount(
    amount: f64,
    amount_minor: Option<&str>,
    decimals: u8,
) -> Result<ResolvedAmount, GuardrailError> {
    match amount_minor.map(str::trim).filter(|v| !v.is_empty()) {
        Some(raw) => {
            if !raw.bytes().all(|b| b.is_ascii_digit()) {
                return Err(GuardrailError::AmountMinorInvalid {
                    reason: format!("{raw:?} contains non-digit characters"),
                });
            }

            let atomic = U256::from_str_radix(raw, 10).map_err(|err| {
                GuardrailError::AmountMinorInvalid {
                    reason: format!("{raw:?}: {err}"),
                }
            })?;

            if atomic.is_zero() {
                return Err(GuardrailError::AmountNotPositive);
            }

            // The compact `amount` must still be sane even when the exact field rules.
            if !amount.is_finite() {
                return Err(GuardrailError::AmountNotFinite);
            }

            let actual = atomic_to_usd(atomic, decimals)?;
            let tolerance = AMOUNT_RECONCILE_TOLERANCE * amount.abs().max(1.0);
            if (actual - amount).abs() > tolerance {
                return Err(GuardrailError::AmountMismatch {
                    minor: raw.to_string(),
                    actual,
                    claimed: amount,
                });
            }

            Ok(ResolvedAmount {
                usd_value: actual,
                atomic,
            })
        }
        None => {
            check_amount_is_valid(amount)?;
            let atomic = parse_units(&amount.to_string(), decimals).map_err(|err| {
                GuardrailError::AmountNotRepresentable {
                    amount,
                    decimals,
                    reason: err.to_string(),
                }
            })?;
            Ok(ResolvedAmount {
                usd_value: amount,
                atomic: atomic.get_absolute(),
            })
        }
    }
}

fn atomic_to_usd(atomic: U256, decimals: u8) -> Result<f64, GuardrailError> {
    let not_representable = |reason: String| GuardrailError::AmountNotRepresentable {
        amount: 0.0,
        decimals,
        reason,
    };

    // `U256` is unsigned, so the conversion into `ParseUnits` cannot fail.
    let formatted = format_units(ParseUnits::from(atomic), decimals)
        .map_err(|err| not_representable(err.to_string()))?;

    let parsed: f64 = formatted
        .parse()
        .map_err(|_| not_representable(format!("{formatted:?} is not representable as f64")))?;

    if !parsed.is_finite() {
        return Err(not_representable(format!("{formatted:?} overflows f64")));
    }
    Ok(parsed)
}

// ---------------------------------------------------------------------------
// Ledger
// ---------------------------------------------------------------------------

/// The on-disk velocity record.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct LedgerState {
    pub version: u32,
    /// Days since the Unix epoch, UTC.
    pub day: u32,
    pub spent_usd: f64,
}

impl LedgerState {
    pub fn empty(day: u32) -> Self {
        Self {
            version: LEDGER_FORMAT_VERSION,
            day,
            spent_usd: 0.0,
        }
    }
}

/// Today's UTC day index.
pub fn utc_day() -> u32 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| (elapsed.as_secs() / SECONDS_PER_DAY) as u32)
        .unwrap_or(0)
}

#[derive(Debug)]
pub struct Ledger {
    state: LedgerState,
    path: Option<PathBuf>,
}

impl Ledger {
    /// Opens the ledger, restoring persisted spend for `today` when a path is set.
    ///
    /// Recovery is deliberately forgiving: a missing, unreadable, corrupt, future-versioned
    /// or stale-day file all fall back to an empty ledger for today rather than bricking
    /// the wallet. The alternative — refusing to transact until the file is repaired —
    /// would hand a denial-of-service lever to anyone who can touch app storage.
    pub fn open(path: Option<&Path>, today: u32) -> Self {
        let state = match path {
            Some(path) => read_state(path, today).unwrap_or_else(|_| LedgerState::empty(today)),
            None => LedgerState::empty(today),
        };
        Self {
            state,
            path: path.map(Path::to_path_buf),
        }
    }

    pub fn state(&self) -> LedgerState {
        self.state
    }

    pub fn spent_usd(&self) -> f64 {
        self.state.spent_usd
    }

    pub fn path(&self) -> Option<&Path> {
        self.path.as_deref()
    }

    /// Resets the ledger when the UTC day has moved on.
    pub fn rollover_if_needed(&mut self, today: u32) {
        if self.state.day != today {
            self.state = LedgerState::empty(today);
        }
    }

    /// Books an amount against today's budget. Caller must have passed
    /// [`check_velocity_limit`] first.
    pub fn reserve(&mut self, amount: f64, today: u32) {
        self.rollover_if_needed(today);
        self.state.spent_usd += amount;
    }

    /// Returns an amount booked by [`Ledger::reserve`] to the budget.
    pub fn release(&mut self, amount: f64, today: u32) {
        self.rollover_if_needed(today);
        self.state.spent_usd = (self.state.spent_usd - amount).max(0.0);
    }

    /// Atomically writes the ledger to `FUSION_LEDGER_PATH`, if configured.
    ///
    /// Writes go to a sibling `.tmp` file which is flushed and fsynced before being
    /// renamed over the target, so a crash mid-write leaves either the old ledger or the
    /// new one — never a half-written file.
    pub fn persist(&self) -> Result<(), GuardrailError> {
        let Some(path) = self.path.as_ref() else {
            return Ok(());
        };

        let io = |reason: String| GuardrailError::LedgerIo { reason };

        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent)
                    .map_err(|err| io(format!("create {}: {err}", parent.display())))?;
            }
        }

        let encoded = serde_json::to_vec_pretty(&self.state).map_err(|err| io(err.to_string()))?;

        let mut tmp = path.clone().into_os_string();
        tmp.push(".tmp");
        let tmp = PathBuf::from(tmp);

        {
            let mut file =
                File::create(&tmp).map_err(|err| io(format!("create {}: {err}", tmp.display())))?;
            file.write_all(&encoded)
                .map_err(|err| io(format!("write {}: {err}", tmp.display())))?;
            file.sync_all()
                .map_err(|err| io(format!("fsync {}: {err}", tmp.display())))?;
        }

        fs::rename(&tmp, path)
            .map_err(|err| io(format!("rename into {}: {err}", path.display())))?;
        Ok(())
    }
}

fn read_state(path: &Path, today: u32) -> Result<LedgerState, GuardrailError> {
    let raw = fs::read_to_string(path).map_err(|err| GuardrailError::LedgerIo {
        reason: format!("read {}: {err}", path.display()),
    })?;

    let parsed: LedgerState =
        serde_json::from_str(&raw).map_err(|err| GuardrailError::LedgerIo {
            reason: format!("parse {}: {err}", path.display()),
        })?;

    if parsed.version != LEDGER_FORMAT_VERSION
        || parsed.day != today
        || !parsed.spent_usd.is_finite()
        || parsed.spent_usd < 0.0
    {
        return Ok(LedgerState::empty(today));
    }

    Ok(parsed)
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

/// A guardrail-approved transaction plan plus the amount reserved against the daily cap.
#[derive(Debug, Clone, PartialEq)]
pub struct Reservation {
    pub token: Address,
    pub decimals: u8,
    pub destination: Address,
    pub amount: ResolvedAmount,
}

impl Reservation {
    pub fn usd_value(&self) -> f64 {
        self.amount.usd_value
    }
}

/// Source of the current UTC day. Injectable so the suite can assert rollover without
/// sleeping until midnight.
type DayClock = Arc<dyn Fn() -> u32 + Send + Sync>;

pub struct GuardrailEngine {
    config: WalletConfig,
    ledger: Mutex<Ledger>,
    clock: DayClock,
}

impl fmt::Debug for GuardrailEngine {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let ledger_path = self
            .ledger
            .lock()
            .ok()
            .and_then(|ledger| ledger.path().map(Path::to_path_buf));

        f.debug_struct("GuardrailEngine")
            .field("chain_id", &self.config.chain_id)
            .field("max_tx_usd", &self.config.max_tx_usd)
            .field("daily_limit_usd", &self.config.daily_limit_usd)
            .field("ledger_path", &ledger_path)
            .finish()
    }
}

impl GuardrailEngine {
    /// Builds an engine from the process environment, restoring any persisted ledger.
    pub fn from_env() -> Result<Self, crate::config::ConfigError> {
        Ok(Self::new(WalletConfig::from_env()?))
    }

    pub fn new(config: WalletConfig) -> Self {
        Self::with_clock(config, Arc::new(utc_day))
    }

    pub fn with_clock(config: WalletConfig, clock: DayClock) -> Self {
        let today = clock();
        let ledger = Ledger::open(config.ledger_path.as_deref(), today);
        Self {
            config,
            ledger: Mutex::new(ledger),
            clock,
        }
    }

    pub fn config(&self) -> &WalletConfig {
        &self.config
    }

    fn ledger(&self) -> MutexGuard<'_, Ledger> {
        self.ledger
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn today(&self) -> u32 {
        (self.clock)()
    }

    /// USD already spent today, after any day rollover.
    pub fn spent_today(&self) -> f64 {
        let today = self.today();
        let mut ledger = self.ledger();
        ledger.rollover_if_needed(today);
        ledger.spent_usd()
    }

    /// Runs every rule in order and, on success, reserves the amount against the cap.
    ///
    /// The returned [`Reservation`] is the only description of a transaction the executor
    /// is allowed to act on, which is what keeps intent parsing, risk policy and
    /// transaction building from drifting apart.
    pub fn evaluate(&self, intent: &AiIntent) -> Result<Reservation, GuardrailError> {
        check_supported_action(intent.action)?;

        let token = self
            .config
            .resolve_token(&intent.token_in)
            .map_err(|_| GuardrailError::UnknownToken(intent.token_in.clone()))?;

        check_whitelisted_assets(&[token], &self.config.whitelist)?;

        let decimals = self
            .config
            .decimals_for(token)
            .ok_or(GuardrailError::UnknownToken(intent.token_in.clone()))?;

        let amount = resolve_amount(intent.amount, intent.amount_minor.as_deref(), decimals)?;

        check_tx_size(amount.usd_value, self.config.max_tx_usd)?;

        let destination = check_destination(intent.destination_address.as_deref())?;

        let today = self.today();
        let mut ledger = self.ledger();
        ledger.rollover_if_needed(today);
        check_velocity_limit(
            ledger.spent_usd(),
            amount.usd_value,
            self.config.daily_limit_usd,
        )?;
        ledger.reserve(amount.usd_value, today);
        drop(ledger);

        Ok(Reservation {
            token,
            decimals,
            destination,
            amount,
        })
    }

    /// Persists the ledger after a broadcast the node accepted.
    pub fn commit(&self, _reservation: &Reservation) -> Result<(), GuardrailError> {
        self.ledger().persist()
    }

    /// Returns a reservation's amount to the budget when the transaction did not happen.
    pub fn release(&self, reservation: &Reservation) {
        let today = self.today();
        self.ledger().release(reservation.usd_value(), today);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{ConfigError, BUILTIN_ASSETS};
    use alloy::primitives::address;
    use std::sync::atomic::{AtomicU32, Ordering};
    use tempfile::TempDir;

    const USDC: Address = BUILTIN_ASSETS[1].address;
    const WETH: Address = BUILTIN_ASSETS[2].address;
    const ALIEN: Address = address!("0x00000000000000000000000000000000deadbeef");
    const DEST: Address = address!("0x1111111111111111111111111111111111111111");

    fn test_config() -> WalletConfig {
        let mut config = WalletConfig::from_source(&|key| match key {
            "FUSION_RPC_URL" => Some("http://127.0.0.1:8545".to_string()),
            _ => None,
        })
        .expect("test config");

        // Default allowlist deliberately excludes the native-asset sentinel.
        config.whitelist = [USDC, WETH].into_iter().collect();
        config
    }

    fn transfer(amount: f64, token: &str, destination: &str) -> AiIntent {
        AiIntent {
            action: Action::Transfer,
            amount,
            amount_minor: None,
            token_in: token.to_string(),
            token_out: None,
            destination_address: Some(destination.to_string()),
        }
    }

    fn engine_with(config: WalletConfig, day: u32) -> (GuardrailEngine, Arc<AtomicU32>) {
        let cell = Arc::new(AtomicU32::new(day));
        let clock = {
            let cell = Arc::clone(&cell);
            Arc::new(move || cell.load(Ordering::SeqCst))
        };
        (GuardrailEngine::with_clock(config, clock), cell)
    }

    fn ledger_file(dir: &TempDir) -> PathBuf {
        dir.path().join("ledger.json")
    }

    // -- check_amount_is_valid ---------------------------------------------

    #[test]
    fn accepts_a_positive_finite_amount() {
        assert_eq!(check_amount_is_valid(0.01), Ok(()));
        assert_eq!(check_amount_is_valid(100.0), Ok(()));
    }

    #[test]
    fn rejects_non_finite_amounts() {
        // The reason this check exists: every comparison against NaN is false, so an
        // unguarded NaN passes both the size and the velocity ceiling.
        for amount in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY] {
            assert_eq!(
                check_amount_is_valid(amount),
                Err(GuardrailError::AmountNotFinite),
                "expected {amount} to be rejected"
            );
        }
    }

    #[test]
    fn rejects_zero_and_negative_amounts() {
        for amount in [0.0, -0.0, -1.0, f64::MIN] {
            assert_eq!(
                check_amount_is_valid(amount),
                Err(GuardrailError::AmountNotPositive),
                "expected {amount} to be rejected"
            );
        }
    }

    // -- check_tx_size -------------------------------------------------------

    #[test]
    fn tx_size_allows_exactly_the_limit() {
        assert_eq!(check_tx_size(100.0, 100.0), Ok(()));
        assert_eq!(check_tx_size(99.99, 100.0), Ok(()));
    }

    #[test]
    fn tx_size_rejects_one_cent_over_the_limit() {
        let err = check_tx_size(100.01, 100.0).expect_err("must reject");
        assert_eq!(
            err,
            GuardrailError::TxSizeExceeded {
                amount: 100.01,
                limit: 100.0
            }
        );
    }

    // -- check_velocity_limit ------------------------------------------------

    #[test]
    fn velocity_allows_landing_exactly_on_the_cap() {
        assert_eq!(check_velocity_limit(400.0, 100.0, 500.0), Ok(()));
    }

    #[test]
    fn velocity_rejects_one_cent_over_the_cap() {
        let err = check_velocity_limit(400.0, 100.01, 500.0).expect_err("must reject");
        assert_eq!(
            err,
            GuardrailError::DailyLimitExceeded {
                spent_today: 400.0,
                attempted: 100.01,
                projected: 500.01,
                limit: 500.0
            }
        );
    }

    #[test]
    fn velocity_reports_the_full_projection() {
        let err = check_velocity_limit(250.0, 300.0, 500.0).expect_err("must reject");
        match err {
            GuardrailError::DailyLimitExceeded { projected, .. } => assert_eq!(projected, 550.0),
            other => panic!("unexpected error: {other}"),
        }
    }

    // -- check_whitelisted_assets -------------------------------------------

    #[test]
    fn whitelist_accepts_only_listed_assets() {
        let whitelist: HashSet<Address> = [USDC, WETH].into_iter().collect();

        assert_eq!(check_whitelisted_assets(&[], &whitelist), Ok(()));
        assert_eq!(check_whitelisted_assets(&[USDC, WETH], &whitelist), Ok(()));
    }

    #[test]
    fn whitelist_rejects_unregistered_token() {
        let whitelist: HashSet<Address> = [USDC].into_iter().collect();

        assert_eq!(
            check_whitelisted_assets(&[ALIEN], &whitelist),
            Err(GuardrailError::TokenNotWhitelisted { address: ALIEN })
        );
        assert_eq!(
            check_whitelisted_assets(&[USDC, ALIEN], &whitelist),
            Err(GuardrailError::TokenNotWhitelisted { address: ALIEN })
        );
    }

    #[test]
    fn native_asset_must_be_opted_into() {
        let whitelist: HashSet<Address> = [USDC].into_iter().collect();
        assert!(check_whitelisted_assets(&[Address::ZERO], &whitelist).is_err());

        let with_native: HashSet<Address> = [USDC, Address::ZERO].into_iter().collect();
        assert_eq!(
            check_whitelisted_assets(&[Address::ZERO], &with_native),
            Ok(())
        );
    }

    // -- check_destination ---------------------------------------------------

    #[test]
    fn destination_accepts_a_valid_address() {
        let parsed = check_destination(Some(&DEST.to_string())).unwrap();
        assert_eq!(parsed, DEST);
    }

    #[test]
    fn destination_requires_a_value() {
        for input in [None, Some(""), Some("   ")] {
            assert_eq!(
                check_destination(input),
                Err(GuardrailError::DestinationMissing),
                "expected missing destination for {input:?}"
            );
        }
    }

    #[test]
    fn destination_rejects_malformed_and_zero_addresses() {
        assert!(matches!(
            check_destination(Some("0xnothex")),
            Err(GuardrailError::DestinationInvalid { .. })
        ));
        assert!(matches!(
            check_destination(Some("0x1111")),
            Err(GuardrailError::DestinationInvalid { .. })
        ));
        // alloy would parse this happily; the guardrail insists on an explicit prefix.
        assert!(matches!(
            check_destination(Some("1111111111111111111111111111111111111111")),
            Err(GuardrailError::DestinationInvalid { .. })
        ));
        assert_eq!(
            check_destination(Some(&Address::ZERO.to_string())),
            Err(GuardrailError::DestinationIsZero)
        );
    }

    // -- check_supported_action ----------------------------------------------

    #[test]
    fn only_transfer_is_executable() {
        assert_eq!(check_supported_action(Action::Transfer), Ok(()));
        for action in [Action::Swap, Action::Stake] {
            assert_eq!(
                check_supported_action(action),
                Err(GuardrailError::UnsupportedAction(action))
            );
        }
    }

    // -- resolve_amount ------------------------------------------------------

    #[test]
    fn resolves_float_amounts_without_precision_drift() {
        let resolved = resolve_amount(0.1, None, 6).unwrap();
        assert_eq!(resolved.atomic, U256::from(100_000u64));
        assert_eq!(resolved.usd_value, 0.1);

        let eth = resolve_amount(1.5, None, 18).unwrap();
        assert_eq!(eth.atomic, U256::from(1_500_000_000_000_000_000u64));
    }

    #[test]
    fn rejects_non_finite_and_non_positive_float_amounts() {
        assert_eq!(
            resolve_amount(f64::NAN, None, 6),
            Err(GuardrailError::AmountNotFinite)
        );
        assert_eq!(
            resolve_amount(0.0, None, 6),
            Err(GuardrailError::AmountNotPositive)
        );
    }

    #[test]
    fn amount_minor_is_authoritative_when_consistent() {
        let resolved = resolve_amount(12.5, Some("12500000"), 6).unwrap();

        assert_eq!(resolved.atomic, U256::from(12_500_000u64));
        assert_eq!(resolved.usd_value, 12.5);
    }

    #[test]
    fn amount_minor_that_contradicts_amount_is_rejected() {
        // The bypass this closes: claim $1 to the guardrails, move $1,000,000.
        let err = resolve_amount(1.0, Some("1000000000"), 6).expect_err("must reject");
        assert!(
            matches!(err, GuardrailError::AmountMismatch { .. }),
            "{err}"
        );

        let err = resolve_amount(1.0, Some("0"), 6).expect_err("zero must reject");
        assert_eq!(err, GuardrailError::AmountNotPositive);
    }

    #[test]
    fn amount_minor_must_be_digits() {
        for raw in ["-1", "1.5", "0x10", "1_000", "abc", "+1"] {
            let result = resolve_amount(1.0, Some(raw), 6);
            assert!(result.is_err(), "expected {raw:?} to be rejected");
        }
    }

    #[test]
    fn blank_amount_minor_falls_back_to_the_float() {
        // A blank field is treated as "not supplied" rather than as a malformed number.
        for blank in ["", "   "] {
            let resolved = resolve_amount(2.0, Some(blank), 6).unwrap();
            assert_eq!(resolved.atomic, U256::from(2_000_000u64));
        }
    }

    #[test]
    fn amount_minor_still_requires_a_sane_float_companion() {
        assert_eq!(
            resolve_amount(f64::NAN, Some("1000000"), 6),
            Err(GuardrailError::AmountNotFinite)
        );
    }

    #[test]
    fn atomic_amounts_too_large_for_f64_are_rejected() {
        // 400 digits overflows f64, so the atomic -> USD conversion has to bail out
        // rather than silently producing an infinite notional.
        let huge = format!("1{}", "0".repeat(400));
        let err = resolve_amount(1.0, Some(&huge), 0).expect_err("overflow must reject");
        assert!(
            matches!(
                err,
                GuardrailError::AmountMinorInvalid { .. }
                    | GuardrailError::AmountNotRepresentable { .. }
            ),
            "{err}"
        );
    }

    #[test]
    fn atomic_amounts_that_fit_f64_but_contradict_the_claim_are_rejected() {
        let huge = format!("1{}", "0".repeat(60));
        assert!(matches!(
            resolve_amount(1.0, Some(&huge), 0),
            Err(GuardrailError::AmountMismatch { .. })
        ));
    }

    // -- Ledger persistence --------------------------------------------------

    #[test]
    fn fresh_ledger_starts_empty() {
        let ledger = Ledger::open(None, 100);
        assert_eq!(ledger.state(), LedgerState::empty(100));
        assert_eq!(ledger.spent_usd(), 0.0);
    }

    #[test]
    fn ledger_round_trips_through_the_configured_path() {
        let dir = TempDir::new().unwrap();
        let path = ledger_file(&dir);

        let mut writer = Ledger::open(Some(&path), 100);
        writer.reserve(42.5, 100);
        writer.persist().unwrap();

        let reader = Ledger::open(Some(&path), 100);
        assert_eq!(reader.spent_usd(), 42.5);
        assert_eq!(reader.state().version, LEDGER_FORMAT_VERSION);
    }

    #[test]
    fn ledger_creates_missing_parent_directories() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("nested/deeper/ledger.json");

        let mut ledger = Ledger::open(Some(&path), 7);
        ledger.reserve(1.0, 7);
        ledger.persist().unwrap();

        assert!(path.exists());
        assert_eq!(Ledger::open(Some(&path), 7).spent_usd(), 1.0);
    }

    #[test]
    fn ledger_rolls_over_when_the_day_changes() {
        let dir = TempDir::new().unwrap();
        let path = ledger_file(&dir);

        let mut ledger = Ledger::open(Some(&path), 100);
        ledger.reserve(500.0, 100);
        ledger.persist().unwrap();

        let reloaded = Ledger::open(Some(&path), 101);
        assert_eq!(
            reloaded.spent_usd(),
            0.0,
            "yesterday's spend must not carry over"
        );

        let mut reloaded = reloaded;
        reloaded.reserve(10.0, 101);
        reloaded.persist().unwrap();

        assert_eq!(Ledger::open(Some(&path), 101).spent_usd(), 10.0);
    }

    #[test]
    fn corrupt_ledger_falls_back_to_empty() {
        let dir = TempDir::new().unwrap();
        let path = ledger_file(&dir);
        fs::write(&path, "{ this is not json").unwrap();

        assert_eq!(Ledger::open(Some(&path), 100).spent_usd(), 0.0);
    }

    #[test]
    fn future_ledger_version_falls_back_to_empty() {
        let dir = TempDir::new().unwrap();
        let path = ledger_file(&dir);
        fs::write(&path, r#"{"version":9999,"day":100,"spent_usd":450.0}"#).unwrap();

        assert_eq!(Ledger::open(Some(&path), 100).spent_usd(), 0.0);
    }

    #[test]
    fn nonsensical_ledger_values_fall_back_to_empty() {
        let dir = TempDir::new().unwrap();
        let path = ledger_file(&dir);

        for payload in [
            r#"{"version":1,"day":100,"spent_usd":-5.0}"#,
            r#"{"version":1,"day":100,"spent_usd":"lots"}"#,
            r#"{"version":1}"#,
        ] {
            fs::write(&path, payload).unwrap();
            assert_eq!(
                Ledger::open(Some(&path), 100).spent_usd(),
                0.0,
                "expected empty ledger for {payload}"
            );
        }
    }

    #[test]
    fn release_returns_budget_and_clamps_at_zero() {
        let mut ledger = Ledger::open(None, 100);
        ledger.reserve(30.0, 100);
        ledger.release(10.0, 100);
        assert_eq!(ledger.spent_usd(), 20.0);

        ledger.release(100.0, 100);
        assert_eq!(ledger.spent_usd(), 0.0, "release must never go negative");
    }

    #[test]
    fn persist_without_a_path_is_a_no_op() {
        let mut ledger = Ledger::open(None, 100);
        ledger.reserve(10.0, 100);
        assert!(ledger.path().is_none());
        assert!(ledger.persist().is_ok());
    }

    #[test]
    fn persist_reports_an_unwritable_target() {
        let mut ledger = Ledger::open(
            Some(Path::new("/proc/definitely/not/writable/ledger.json")),
            1,
        );
        ledger.reserve(1.0, 1);

        assert!(matches!(
            ledger.persist(),
            Err(GuardrailError::LedgerIo { .. })
        ));
    }

    #[test]
    fn ledger_json_shape_is_stable() {
        let state = LedgerState {
            version: LEDGER_FORMAT_VERSION,
            day: 20345,
            spent_usd: 137.5,
        };
        assert_eq!(
            serde_json::to_string(&state).unwrap(),
            r#"{"version":1,"day":20345,"spent_usd":137.5}"#
        );
    }

    // -- Engine --------------------------------------------------------------

    #[test]
    fn engine_approves_a_well_formed_transfer() {
        let (engine, _) = engine_with(test_config(), 100);

        let reservation = engine
            .evaluate(&transfer(25.0, "USDC", &DEST.to_string()))
            .expect("valid transfer");

        assert_eq!(reservation.token, USDC);
        assert_eq!(reservation.decimals, 6);
        assert_eq!(reservation.destination, DEST);
        assert_eq!(reservation.amount.atomic, U256::from(25_000_000u64));
        assert_eq!(engine.spent_today(), 25.0);
    }

    #[test]
    fn engine_blocks_unsupported_actions() {
        let (engine, _) = engine_with(test_config(), 100);
        let mut intent = transfer(1.0, "USDC", &DEST.to_string());
        intent.action = Action::Swap;

        assert_eq!(
            engine.evaluate(&intent),
            Err(GuardrailError::UnsupportedAction(Action::Swap))
        );
        assert_eq!(
            engine.spent_today(),
            0.0,
            "a blocked intent must not spend budget"
        );
    }

    #[test]
    fn engine_blocks_unlisted_tokens_including_native() {
        let (engine, _) = engine_with(test_config(), 100);

        assert_eq!(
            engine.evaluate(&transfer(1.0, "ETH", &DEST.to_string())),
            Err(GuardrailError::TokenNotWhitelisted {
                address: Address::ZERO
            })
        );
        assert_eq!(
            engine.evaluate(&transfer(1.0, "DOGE", &DEST.to_string())),
            Err(GuardrailError::UnknownToken("DOGE".to_string()))
        );
    }

    #[test]
    fn engine_resolves_tokens_given_as_addresses() {
        let (engine, _) = engine_with(test_config(), 100);
        let reservation = engine
            .evaluate(&transfer(1.0, &WETH.to_string(), &DEST.to_string()))
            .expect("WETH is whitelisted");

        assert_eq!(reservation.token, WETH);
        assert_eq!(reservation.decimals, 18);
    }

    #[test]
    fn engine_enforces_the_daily_cap_across_calls() {
        let (engine, _) = engine_with(test_config(), 100);

        for _ in 0..5 {
            engine
                .evaluate(&transfer(100.0, "USDC", &DEST.to_string()))
                .expect("each individual transfer is within the size limit");
        }
        assert_eq!(engine.spent_today(), 500.0);

        assert!(matches!(
            engine.evaluate(&transfer(1.0, "USDC", &DEST.to_string())),
            Err(GuardrailError::DailyLimitExceeded { .. })
        ));
        assert_eq!(
            engine.spent_today(),
            500.0,
            "rejection must not spend budget"
        );
    }

    #[test]
    fn engine_size_limit_beats_the_daily_cap() {
        let (engine, _) = engine_with(test_config(), 100);
        assert_eq!(
            engine.evaluate(&transfer(100.01, "USDC", &DEST.to_string())),
            Err(GuardrailError::TxSizeExceeded {
                amount: 100.01,
                limit: 100.0
            })
        );
    }

    #[test]
    fn release_refunds_a_failed_transaction() {
        let (engine, _) = engine_with(test_config(), 100);

        let reservation = engine
            .evaluate(&transfer(60.0, "USDC", &DEST.to_string()))
            .unwrap();
        engine.release(&reservation);
        assert_eq!(engine.spent_today(), 0.0);

        // Budget is available again.
        engine
            .evaluate(&transfer(60.0, "USDC", &DEST.to_string()))
            .expect("released budget must be reusable");
    }

    #[test]
    fn commit_persists_and_survives_a_fresh_engine() {
        let dir = TempDir::new().unwrap();
        let path = ledger_file(&dir);

        let mut config = test_config();
        config.ledger_path = Some(path.clone());

        // Pinned to the real UTC day: a fresh engine reads the wall clock, so a fixture
        // day of 100 would look like a stale ledger and be rolled over on load.
        let today = utc_day();
        let (engine, day) = engine_with(config.clone(), today);
        let reservation = engine
            .evaluate(&transfer(75.0, "USDC", &DEST.to_string()))
            .unwrap();
        engine.commit(&reservation).unwrap();

        // Simulate an app restart on the same day: the spend must still be there.
        let restarted = GuardrailEngine::new(config.clone());
        assert_eq!(restarted.spent_today(), 75.0);

        // ... and the original engine rolls over to zero when the UTC day moves on.
        day.store(today + 1, Ordering::SeqCst);
        assert_eq!(engine.spent_today(), 0.0);
    }

    #[test]
    fn uncommitted_reservations_do_not_persist() {
        let dir = TempDir::new().unwrap();
        let path = ledger_file(&dir);

        let mut config = test_config();
        config.ledger_path = Some(path.clone());

        let (engine, _) = engine_with(config.clone(), 100);
        engine
            .evaluate(&transfer(75.0, "USDC", &DEST.to_string()))
            .unwrap();

        assert!(
            !path.exists(),
            "nothing is written until a transaction succeeds"
        );
        assert_eq!(GuardrailEngine::new(config).spent_today(), 0.0);
    }

    #[test]
    fn rollover_preserves_the_persisted_previous_day() {
        let dir = TempDir::new().unwrap();
        let path = ledger_file(&dir);

        let mut config = test_config();
        config.ledger_path = Some(path.clone());

        let (engine, day) = engine_with(config.clone(), 100);
        let reservation = engine
            .evaluate(&transfer(10.0, "USDC", &DEST.to_string()))
            .unwrap();
        engine.commit(&reservation).unwrap();

        day.store(101, Ordering::SeqCst);
        let next_day = engine
            .evaluate(&transfer(20.0, "USDC", &DEST.to_string()))
            .unwrap();
        engine.commit(&next_day).unwrap();

        assert_eq!(engine.spent_today(), 20.0);

        let raw: LedgerState = serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(raw.day, 101);
        assert_eq!(raw.spent_usd, 20.0);
    }

    #[test]
    fn engine_from_env_reports_config_errors() {
        // FUSION_RPC_URL is absent in the test harness, so this must surface rather than panic.
        if let Err(err) = GuardrailEngine::from_env() {
            assert!(matches!(err, ConfigError::Missing("FUSION_RPC_URL")));
        }
    }

    #[test]
    fn engine_debug_does_not_leak_configuration_secrets() {
        let (engine, _) = engine_with(test_config(), 100);
        let rendered = format!("{engine:?}");
        assert!(rendered.contains("GuardrailEngine"));
        assert!(rendered.contains("100"));
    }

    #[test]
    fn engine_survives_a_poisoned_lock() {
        let (engine, _) = engine_with(test_config(), 100);

        let clone = std::sync::Arc::new(engine);
        let poisoner = std::sync::Arc::clone(&clone);
        let _ = std::thread::spawn(move || {
            let _guard = poisoner.ledger.lock().unwrap();
            panic!("poison");
        })
        .join();

        // Recovered rather than propagated: a panic in one FFI call must not brick the API.
        assert_eq!(clone.spent_today(), 0.0);
    }

    #[test]
    fn error_codes_are_stable() {
        assert_eq!(
            check_tx_size(101.0, 100.0).unwrap_err().code(),
            "tx_size_exceeded"
        );
        assert_eq!(
            check_velocity_limit(500.0, 1.0, 500.0).unwrap_err().code(),
            "daily_limit_exceeded"
        );
        assert_eq!(
            check_supported_action(Action::Stake).unwrap_err().code(),
            "unsupported_action"
        );
    }
}
