# Software Engineering Specification: Fusion Wallet Core (`fusion-core`)

## 1. Project Overview
**Target Ecosystem:** EVM (Ethereum / L2s like Base or Arbitrum).
**Language:** Rust (Edition 2021).
**Primary Function:** A deterministic, memory-safe execution engine that receives structured JSON intents from an edge AI model, evaluates them against strict economic guardrails, and signs/broadcasts EVM transactions locally.
**Key Libraries:** `alloy` (Web3), `uniffi` (FFI/Kotlin bindings), `serde` (serialization), `tokio` (async).

## 2. Feature Requirements

### Module A: Data Models & Deserialization (`models.rs`)
*   **Feature:** Define strict structs to deserialize the JSON intent from the Kotlin/MNN frontend.
*   **Required Structs:** 
    *   `AiIntent`: Fields for `action` (Enum: Transfer, Swap, Stake), `amount` (f64), `token_in` (String), `token_out` (Option<String>), `destination_address` (Option<String>).
    *   `TransactionResult`: Fields for `status` (Success, Failed, BlockedByGuardrail), `tx_hash` (Option<String>), `message` (String).

### Module B: The Economic Guardrails Engine (`guardrails.rs`)
*   **Feature:** A pure-logic rule engine executing Srishty's risk parameters *before* any transaction is signed.
*   **Rules to Implement:**
    1.  `check_velocity_limit`: Ensure total daily transaction volume does not exceed a hardcoded maximum (e.g., $500/day).
    2.  `check_tx_size`: Reject any single transaction over a specific threshold (e.g., $100).
    3.  `check_whitelisted_assets`: Only allow interactions with approved ERC-20 contract addresses (e.g., USDC, WETH).
*   **Output:** Returns `Result<(), GuardrailError>`.

### Module C: EVM Executor (`executor.rs`)
*   **Feature:** Wallet management and transaction construction using `alloy`.
*   **Capabilities:**
    *   Securely load a local private key (secp256k1) into an Alloy `Wallet`.
    *   Initialize an RPC provider (via HTTP).
    *   Build an EVM transaction (value transfers or basic ERC-20 `transfer`/`approve` calls).
    *   Sign and broadcast the transaction.

### Module D: The FFI Bridge (`lib.rs` & `router.rs`)
*   **Feature:** Expose the Rust API to the Jetpack Compose Android app via Mozilla UniFFI.
*   **Exposed Function:** `execute_intent(json_payload: String, private_key: String) -> String` (Returns a JSON stringified `TransactionResult`).
*   **Flow:** Parses JSON -> Runs Guardrails -> If Pass, routes to Executor -> Returns Result.

## 3. Strict Validation & Testing Protocol
The agent MUST implement a rigorous Test-Driven Development (TDD) approach. Every module must have a `#[cfg(test)]` submodule.

*   **Unit Tests (Guardrails):** Must test edge cases (e.g., exactly at the velocity limit, 1 wei over the limit, unregistered token).
*   **Unit Tests (Models):** Must verify accurate deserialization of malformed JSON, missing fields, and correct AI outputs.
*   **Mock Integration Tests:** Must mock the RPC provider to test the `executor.rs` transaction building logic without needing live network access during CI.

## 4. Implementation SDLC Plan for Coding Agent

*   **Step 1: Scaffolding.** Initialize `cargo new fusion-core --lib`. Setup `Cargo.toml` with `alloy`, `tokio`, `serde`, and `uniffi`.
*   **Step 2: Types & Mocks.** Implement `models.rs` and write deserialization tests.
*   **Step 3: Guardrails.** Implement `guardrails.rs` using pure functions. Achieve 100% test coverage on this module.
*   **Step 4: Execution.** Implement `executor.rs`.
*   **Step 5: FFI Layer.** Tie it together in `lib.rs` with UniFFI macros.
*   **Step 6: Report Generation.** Upon completion, generate the required `agent_dev_report.md` (format below).

---

## 5. Agent Output Template
*(Coding Agent: Upon completing the development cycle, you MUST output a file named `agent_dev_report.md` following this exact structure)*

### fusion-core Development Report
**1. Build Status:** [Success / Failed]
**2. Test Coverage Summary:**
   * Guardrails passing: [X/Y]
   * Executor passing: [X/Y]
   * Model parsing passing: [X/Y]
**3. Warnings/Errors Encountered (if any):**
**4. Alloy Provider Status:** [Briefly describe how the RPC provider was mocked or implemented]
**5. Code Snippet:** [Provide the exact implementation of the `execute_intent` function from `lib.rs`]