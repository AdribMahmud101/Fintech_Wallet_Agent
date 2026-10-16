### Complete Jetpack Compose Architecture & UI/UX Specification

To maximize development speed and eliminate visual friction, your coding agent will use **official Google Material 3 components (`androidx.compose.material3`)** combined with **Compose Foundation/Animation**. This avoids fragile third-party UI hacks while delivering a high-density, institutional dark aesthetic.

---

### 1. Library Dependencies (`build.gradle.kts`)

Instruct the agent to include these standardized, production-ready dependencies:

```kotlin
dependencies {
    // Official Material 3 Components (Scaffold, Sheets, Navigation)
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material3:material3-window-size-class:1.3.1")
    
    // Material Vector Icons (Extended set for system status & crypto actions)
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    
    // Navigation & State Management
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    
    // Image Loading & System Assets
    implementation("io.coil-kt:coil-compose:2.7.0")
    
    // UniFFI JNA Bridge
    implementation("net.java.dev.jna:jna:5.10.0@aar")
}

```

---

### 2. Full Application Structure & Navigation State Machine

The app is built as a single-activity layout wrapped in a `ModalNavigationDrawer` and `Scaffold`.

```
+-------------------------------------------------------------------+
|  ModalNavigationDrawer (Settings, Multi-Sig Keys, Network Config) |
|  +-------------------------------------------------------------+  |
|  | Scaffold TopAppBar (Wallet Health Header & Engine Chips)    |  |
|  +-------------------------------------------------------------+  |
|  | App Body (NavHost State: Chat / Tokens / Guardrail Monitor)  |  |
|  |                                                             |  |
|  |   - ChatFeed View (User Prompts + Pre-Flight Cards)         |  |
|  |   - Token Portfolio View (Balances & Allowance Controls)    |  |
|  |   - Guardrail Ledger View (Daily Spend & Whitelist Rules)   |  |
|  +-------------------------------------------------------------+  |
|  | Scaffold BottomBar (Input Field + Voice Mic Button)          |  |
|  +-------------------------------------------------------------+  |
|  | ModalBottomSheet (Expandable Rust FFI & MNN System Terminal)|  |
+-------------------------------------------------------------------+

```

---

### 3. Core Screen Views & UI Components

#### View 1: Main Chat & Intent Execution Stream

* **Component:** `ChatFeed.kt`
* **States:**
* `Idle`: Displays prompt chips (*"Transfer 10 USDC to 0x71C..."*, *"Check Daily Limit"*).
* `Parsing`: MNN local model processing; displays pulsing Violet status chip (`⚡ Parsing Intent...`).
* `Evaluating`: Intent generated; displays the **Pre-Flight Guardrail Card**.
* `Executed`: Transaction broadcasted; displays Emerald check badge with linked transaction hash.
* `Blocked`: Guardrail rejected action; displays Crimson error banner with specific violation code (e.g., `[EXCEEDS_DAILY_VELOCITY]`).



#### View 2: "Obsidian Institutional" Pre-Flight Guardrail Card

* **Component:** `PreFlightGuardrailCard.kt`
* **Visual Spec:** Dark container (`#101217`), `1.dp` border (`#232733`), 12dp rounded corners.
* **Content:**
* Header: `🛡️ RUST GUARDRAIL PRE-FLIGHT CHECK` in `JetBrains Mono` 12sp.
* Checklist rows using `ListItem` composables:
* **Whitelist Target:** `0x71C8...8A9` $\rightarrow$ `[ PASS ]` (Emerald)
* **Tx Size Cap:** `$10.00 / $100.00` $\rightarrow$ `[ PASS ]` (Emerald)
* **Daily Velocity:** `$60.00 / $500.00` $\rightarrow$ `[ WARN: 80% ]` (Amber)
* **Atomic Precision:** `10000000 Wei Match` $\rightarrow$ `[ PASS ]` (Emerald)


* Action Row: Dual buttons (`Confirm & Execute` in Indigo `#6366F1` vs `Cancel` outline).



#### View 3: Expandable System Log Terminal Sheet

* **Component:** `SystemLogTerminalSheet.kt`
* **Implementation:** `ModalBottomSheet` anchored to bottom.
* **Visual Spec:** Deep `#08090C` background, full monospaced text (`JetBrains Mono` 11sp in `#10B981` / `#9CA3AF`).
* **Content Stream:**
```text
[MNN_INFERENCE_ENGINE] Prompt parsed in 118ms (Qwen-1.5B INT4)
[JNI_BRIDGE_CALL] execute_intent(payload_json, ***key_redacted***)
[RUST_GUARDRAIL] Ledger checked: $50.00 spent today. Reservation granted.
[ALLOY_EXECUTOR] Transaction signed EIP-1559 -> Hash: 0x8f3b...a109

```



#### View 4: Side Settings & Configuration Drawer

* **Component:** `SettingsDrawer.kt`
* **Implementation:** `ModalDrawerSheet` inside `ModalNavigationDrawer`.
* **Config Controls:**
* **RPC URL Node:** Field to override EVM L2 RPC (`[https://mainnet.base.org](https://mainnet.base.org)`).
* **Daily Velocity Threshold:** Slider to adjust daily dollar cap ($100 to $1,000).
* **Active Token Whitelist:** Toggle list (USDC, WETH, ETH).
* **MNN Inference Threads:** Selector (2, 4, 8 CPU threads).



---

### 4. Implementation Prompt for Coding Agent

Copy and paste this instruction directly to your coding agent:

---

#### 📋 Coding Agent Task Directive: UI/UX Implementation Phase

"Construct the complete user interface for **Fusion Wallet** using **Jetpack Compose and Material 3 (`androidx.compose.material3`)**. Strictly follow the **'Obsidian Institutional' Design System tokens** previously defined (Dark Canvas `#08090C`, Surface `#101217`, Borders `#232733`, Indigo Accent `#6366F1`, Emerald `#10B981`, Crimson `#EF4444`).

**Required UI Module Hierarchy:**

1. **`MainActivity.kt` & Theme Wrapper:**
* Apply `FusionWalletTheme` globally with dark color scheme defaults.


2. **`TopHealthAppBar.kt`:**
* Implement `TopAppBar` displaying the active wallet address (`JetBrains Mono`), daily velocity mini-progress bar, and two status pills: `⚡ MNN 1.5B` (Violet) and `🛡️ RUST OK` (Emerald).


3. **`ChatScreen.kt` & `PreFlightGuardrailCard.kt`:**
* Build the main chat message stream using `LazyColumn`.
* Render user prompts, MNN parsing states, and structured guardrail evaluation cards with individual status badges for Whitelist, Transaction Size, Daily Velocity, and Precision checks.


4. **`SystemLogTerminalSheet.kt`:**
* Implement an expandable `ModalBottomSheet` containing a scrolling monospaced terminal (`JetBrains Mono`) for real-time JNI/FFI logs and execution hashes.


5. **`SettingsDrawer.kt`:**
* Wrap the main UI inside a `ModalNavigationDrawer` with a `ModalDrawerSheet` containing controls for RPC URL selection, daily limit sliders, and token whitelist toggles.


6. **State Management (`MainViewModel.kt`):**
* Manage UI states using Kotlin `StateFlow`:
* `WalletState` (Address, Daily Spent, Daily Cap)
* `ChatMessages` (List of user and AI execution messages)
* `GuardrailEvaluation` (Pre-flight check breakdown data)
* `SystemLogs` (String feed for the terminal sheet)





Assemble all views with mock data first to verify layout smoothness, typography contrast, and animation transitions before connecting the underlying JNI native bindings. Run `./gradlew assembleDebug` and confirm a clean compile."
