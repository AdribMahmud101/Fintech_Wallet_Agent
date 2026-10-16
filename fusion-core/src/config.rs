//! Wallet-level configuration.
//!
//! `execute_intent` receives no configuration from the FFI caller — the Kotlin host has
//! no reason to be able to move RPC endpoints or raise the risk limits — so everything is
//! resolved from the process environment once, at startup, and validated eagerly.
//!
//! Parsing is split into [`WalletConfig::from_source`] (pure, fully testable) and
//! [`WalletConfig::from_env`] (thin `std::env` wrapper). The split exists because the
//! environment is process-global: testing `from_env` directly would make the suite race
//! against itself under `cargo test`'s parallel harness.

use alloy::primitives::Address;
use std::collections::HashSet;
use std::env;
use std::path::PathBuf;
use std::str::FromStr;
use thiserror::Error;
use url::Url;

/// Sentinel address standing for the chain's native asset (ETH).
pub const NATIVE_ASSET: Address = Address::ZERO;

pub const DEFAULT_CHAIN_ID: u64 = 1;
pub const DEFAULT_MAX_TX_USD: f64 = 100.0;
pub const DEFAULT_DAILY_LIMIT_USD: f64 = 500.0;

pub const MAINNET_USDC: &str = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
pub const MAINNET_WETH: &str = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2";

const USDC_ADDRESS: Address = Address::new([
    0xA0, 0xb8, 0x69, 0x91, 0xc6, 0x21, 0x8b, 0x36, 0xc1, 0xd1, 0x9d, 0x4a, 0x2e, 0x9e, 0xb0, 0xce,
    0x36, 0x06, 0xeb, 0x48,
]);
const WETH_ADDRESS: Address = Address::new([
    0xc0, 0x2a, 0xaa, 0x39, 0xb2, 0x23, 0xfe, 0x8d, 0x0a, 0x0e, 0x5c, 0x4f, 0x27, 0xea, 0xd9, 0x08,
    0x3c, 0x75, 0x6c, 0xc2,
]);

/// A token the engine knows how to name, price-check and encode.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Asset {
    pub symbol: &'static str,
    pub address: Address,
    pub decimals: u8,
}

/// Mainnet defaults. `FUSION_WHITELIST` can widen or narrow the *allowed* address set,
/// but it does not invent new symbols: a token addressed directly by `0x…` only needs
/// to appear in the whitelist to be spendable.
pub const BUILTIN_ASSETS: [Asset; 3] = [
    Asset {
        symbol: "ETH",
        address: NATIVE_ASSET,
        decimals: 18,
    },
    Asset {
        symbol: "USDC",
        address: USDC_ADDRESS,
        decimals: 6,
    },
    Asset {
        symbol: "WETH",
        address: WETH_ADDRESS,
        decimals: 18,
    },
];

/// The literal above must agree with the bytes above; the test suite asserts it.
pub const fn builtin_usdc_literal() -> &'static str {
    MAINNET_USDC
}

pub const fn builtin_weth_literal() -> &'static str {
    MAINNET_WETH
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum ConfigError {
    #[error("required environment variable {0} is not set")]
    Missing(&'static str),
    #[error("environment variable {name} is invalid: {reason}")]
    Invalid { name: &'static str, reason: String },
}

#[derive(Debug, Clone, PartialEq)]
pub struct WalletConfig {
    pub rpc_url: Url,
    pub chain_id: u64,
    /// Contract addresses (plus [`NATIVE_ASSET`]) the engine may ever touch.
    pub whitelist: HashSet<Address>,
    /// Symbol → (address, decimals) table used to resolve `token_in`.
    pub assets: Vec<Asset>,
    pub max_tx_usd: f64,
    pub daily_limit_usd: f64,
    /// Optional on-disk velocity ledger. Unset ⇒ in-memory only.
    pub ledger_path: Option<PathBuf>,
}

impl WalletConfig {
    pub fn from_env() -> Result<Self, ConfigError> {
        Self::from_source(&|key| env::var(key).ok())
    }

    /// Parses configuration from an arbitrary source, for hosts and tests that supply
    /// settings without touching the process environment.
    pub fn from_source(get: &impl Fn(&str) -> Option<String>) -> Result<Self, ConfigError> {
        let rpc_url =
            parse_rpc_url(get("FUSION_RPC_URL").ok_or(ConfigError::Missing("FUSION_RPC_URL"))?)?;
        let chain_id = parse_chain_id(get("FUSION_CHAIN_ID"))?;
        let whitelist = parse_whitelist(get("FUSION_WHITELIST"))?;
        let max_tx_usd = parse_limit(
            "FUSION_MAX_TX_USD",
            get("FUSION_MAX_TX_USD"),
            DEFAULT_MAX_TX_USD,
        )?;
        let daily_limit_usd = parse_limit(
            "FUSION_DAILY_LIMIT_USD",
            get("FUSION_DAILY_LIMIT_USD"),
            DEFAULT_DAILY_LIMIT_USD,
        )?;
        let ledger_path = match get("FUSION_LEDGER_PATH") {
            Some(raw) if !raw.trim().is_empty() => Some(PathBuf::from(raw)),
            _ => None,
        };

        Ok(Self {
            rpc_url,
            chain_id,
            whitelist,
            assets: BUILTIN_ASSETS.to_vec(),
            max_tx_usd,
            daily_limit_usd,
            ledger_path,
        })
    }

    /// Resolves a `token_in` string, which may be a known symbol (`USDC`) or a literal
    /// `0x…` address. Symbols are matched case-insensitively.
    pub fn resolve_token(&self, token: &str) -> Result<Address, ConfigError> {
        let trimmed = token.trim();

        if let Ok(address) = Address::from_str(trimmed) {
            return Ok(address);
        }

        let upper = trimmed.to_ascii_uppercase();
        self.assets
            .iter()
            .find(|asset| asset.symbol == upper)
            .map(|asset| asset.address)
            .ok_or_else(|| ConfigError::Invalid {
                name: "token_in",
                reason: format!("unknown token symbol or malformed address: {token:?}"),
            })
    }

    pub fn decimals_for(&self, address: Address) -> Option<u8> {
        self.assets
            .iter()
            .find(|asset| asset.address == address)
            .map(|asset| asset.decimals)
    }

    pub fn symbol_for(&self, address: Address) -> &'static str {
        self.assets
            .iter()
            .find(|asset| asset.address == address)
            .map(|asset| asset.symbol)
            .unwrap_or("UNKNOWN")
    }
}

fn parse_rpc_url(raw: String) -> Result<Url, ConfigError> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(ConfigError::Invalid {
            name: "FUSION_RPC_URL",
            reason: "value is empty".to_string(),
        });
    }

    let url = Url::parse(trimmed).map_err(|err| ConfigError::Invalid {
        name: "FUSION_RPC_URL",
        reason: err.to_string(),
    })?;

    match url.scheme() {
        "http" | "https" => Ok(url),
        other => Err(ConfigError::Invalid {
            name: "FUSION_RPC_URL",
            reason: format!("scheme {other:?} is not supported; use http or https"),
        }),
    }
}

fn parse_chain_id(raw: Option<String>) -> Result<u64, ConfigError> {
    let Some(raw) = raw else {
        return Ok(DEFAULT_CHAIN_ID);
    };
    raw.trim()
        .parse::<u64>()
        .map_err(|err| ConfigError::Invalid {
            name: "FUSION_CHAIN_ID",
            reason: err.to_string(),
        })
}

fn parse_limit(name: &'static str, raw: Option<String>, default: f64) -> Result<f64, ConfigError> {
    let Some(raw) = raw else {
        return Ok(default);
    };
    let parsed = raw
        .trim()
        .parse::<f64>()
        .map_err(|err| ConfigError::Invalid {
            name,
            reason: err.to_string(),
        })?;
    if !parsed.is_finite() || parsed <= 0.0 {
        return Err(ConfigError::Invalid {
            name,
            reason: format!("limit must be a finite positive number, got {raw:?}"),
        });
    }
    Ok(parsed)
}

fn parse_whitelist(raw: Option<String>) -> Result<HashSet<Address>, ConfigError> {
    let Some(raw) = raw else {
        return Ok(BUILTIN_ASSETS
            .iter()
            .map(|asset| asset.address)
            .collect::<HashSet<_>>());
    };

    let mut whitelist = HashSet::new();
    for entry in raw.split(',').map(str::trim).filter(|s| !s.is_empty()) {
        let address = Address::from_str(entry).map_err(|err| ConfigError::Invalid {
            name: "FUSION_WHITELIST",
            reason: format!("{entry:?}: {err}"),
        })?;
        whitelist.insert(address);
    }

    if whitelist.is_empty() {
        return Err(ConfigError::Invalid {
            name: "FUSION_WHITELIST",
            reason: "resolved to an empty allowlist".to_string(),
        });
    }

    Ok(whitelist)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg(vars: &[(&str, &str)]) -> Result<WalletConfig, ConfigError> {
        WalletConfig::from_source(&|key| {
            vars.iter()
                .find(|(k, _)| *k == key)
                .map(|(_, v)| (*v).to_string())
        })
    }

    #[test]
    fn builtin_asset_literals_agree_with_their_bytes() {
        assert_eq!(
            BUILTIN_ASSETS[0].address, NATIVE_ASSET,
            "ETH sentinel is the zero address"
        );
        assert_eq!(
            builtin_usdc_literal().parse::<Address>().unwrap(),
            BUILTIN_ASSETS[1].address,
            "USDC literal drifted from its byte array"
        );
        assert_eq!(
            builtin_weth_literal().parse::<Address>().unwrap(),
            BUILTIN_ASSETS[2].address,
            "WETH literal drifted from its byte array"
        );
    }

    #[test]
    fn requires_rpc_url() {
        let err = cfg(&[]).expect_err("rpc url is mandatory");
        assert_eq!(err, ConfigError::Missing("FUSION_RPC_URL"));
    }

    #[test]
    fn applies_defaults_when_only_rpc_url_is_set() {
        let config = cfg(&[("FUSION_RPC_URL", "https://rpc.example/x")]).unwrap();

        assert_eq!(config.chain_id, DEFAULT_CHAIN_ID);
        assert_eq!(config.max_tx_usd, DEFAULT_MAX_TX_USD);
        assert_eq!(config.daily_limit_usd, DEFAULT_DAILY_LIMIT_USD);
        assert_eq!(config.ledger_path, None);
        assert_eq!(config.whitelist.len(), BUILTIN_ASSETS.len());
    }

    #[test]
    fn rejects_non_http_rpc_urls() {
        for url in ["ws://rpc.example", "ftp://rpc.example", "not a url", ""] {
            let err = cfg(&[("FUSION_RPC_URL", url)]).expect_err("must reject {url}");
            assert!(
                matches!(
                    err,
                    ConfigError::Invalid {
                        name: "FUSION_RPC_URL",
                        ..
                    }
                ),
                "unexpected error for {url:?}: {err}"
            );
        }
    }

    #[test]
    fn parses_overrides() {
        let config = cfg(&[
            ("FUSION_RPC_URL", "http://127.0.0.1:8545"),
            ("FUSION_CHAIN_ID", "8453"),
            ("FUSION_MAX_TX_USD", "250.5"),
            ("FUSION_DAILY_LIMIT_USD", "1000"),
            ("FUSION_LEDGER_PATH", "/data/fusion/ledger.json"),
            (
                "FUSION_WHITELIST",
                "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
            ),
        ])
        .unwrap();

        assert_eq!(config.chain_id, 8453);
        assert_eq!(config.max_tx_usd, 250.5);
        assert_eq!(config.daily_limit_usd, 1000.0);
        assert_eq!(
            config.ledger_path,
            Some(PathBuf::from("/data/fusion/ledger.json"))
        );
        assert_eq!(config.whitelist.len(), 1);
        assert!(config.whitelist.contains(&BUILTIN_ASSETS[1].address));
    }

    #[test]
    fn rejects_malformed_overrides() {
        let cases: [(&[(&str, &str)], &str); 3] = [
            (
                &[
                    ("FUSION_RPC_URL", "http://x"),
                    ("FUSION_CHAIN_ID", "mainnet"),
                ],
                "FUSION_CHAIN_ID",
            ),
            (
                &[("FUSION_RPC_URL", "http://x"), ("FUSION_MAX_TX_USD", "-5")],
                "FUSION_MAX_TX_USD",
            ),
            (
                &[
                    ("FUSION_RPC_URL", "http://x"),
                    ("FUSION_WHITELIST", "0xdeadbeef"),
                ],
                "FUSION_WHITELIST",
            ),
        ];

        for (vars, expected_name) in cases {
            let err = cfg(vars).expect_err("must reject malformed override");
            match err {
                ConfigError::Invalid { name, .. } => assert_eq!(name, expected_name),
                other => panic!("unexpected error: {other}"),
            }
        }
    }

    #[test]
    fn rejects_non_positive_limits() {
        for value in ["0", "-1", "NaN", "inf"] {
            let err = cfg(&[
                ("FUSION_RPC_URL", "http://x"),
                ("FUSION_DAILY_LIMIT_USD", value),
            ])
            .expect_err("must reject {value}");
            assert!(matches!(err, ConfigError::Invalid { .. }));
        }
    }

    #[test]
    fn blank_ledger_path_is_treated_as_unset() {
        let config = cfg(&[
            ("FUSION_RPC_URL", "http://x"),
            ("FUSION_LEDGER_PATH", "   "),
        ])
        .unwrap();
        assert_eq!(config.ledger_path, None);
    }

    #[test]
    fn resolves_tokens_by_symbol_case_insensitively_and_by_address() {
        let config = cfg(&[("FUSION_RPC_URL", "http://x")]).unwrap();

        assert_eq!(
            config.resolve_token("USDC").unwrap(),
            BUILTIN_ASSETS[1].address
        );
        assert_eq!(
            config.resolve_token("usdc").unwrap(),
            BUILTIN_ASSETS[1].address
        );
        assert_eq!(config.resolve_token(" ETH ").unwrap(), NATIVE_ASSET);
        assert_eq!(
            config.resolve_token(MAINNET_USDC).unwrap(),
            BUILTIN_ASSETS[1].address
        );
        assert_eq!(
            config
                .resolve_token("0x0000000000000000000000000000000000000001")
                .unwrap(),
            Address::with_last_byte(1)
        );
    }

    #[test]
    fn rejects_unknown_token_symbols() {
        let config = cfg(&[("FUSION_RPC_URL", "http://x")]).unwrap();
        let err = config
            .resolve_token("DOGE")
            .expect_err("unknown symbol must fail");
        assert!(matches!(err, ConfigError::Invalid { .. }));
    }

    #[test]
    fn reports_decimals_and_symbols() {
        let config = cfg(&[("FUSION_RPC_URL", "http://x")]).unwrap();

        assert_eq!(config.decimals_for(NATIVE_ASSET), Some(18));
        assert_eq!(config.decimals_for(BUILTIN_ASSETS[1].address), Some(6));
        assert_eq!(config.decimals_for(Address::repeat_byte(0xAB)), None);
        assert_eq!(config.symbol_for(BUILTIN_ASSETS[2].address), "WETH");
        assert_eq!(config.symbol_for(Address::repeat_byte(0xAB)), "UNKNOWN");
    }
}
