Here is the complete list of graphics and visual assets required to give **Fusion Wallet** an institutional-grade, professional finish.

---

### 1. Brand Identity & Application Icons

* **App Icon (Android Launcher):**
* **Adaptive Icon Set:** Monochromatic abstract "Fusion" node or shield mark inside an adaptive vector container (`ic_launcher_foreground.xml` + `ic_launcher_background.xml`).
* **Splash Screen Logo:** Minimalist vector logomark + "FUSION WALLET" typography vector for the Android 12+ SplashScreen API.


* **Navigation & Header Logo:** Horizontal brand mark (16dp icon + 14sp monospaced text) for the top navigation bar.

---

### 2. Status & Engine Identity Badges

* **MNN Edge AI Vector Badge:** A small 14x14dp lightning or neural node SVG icon in violet (`#8B5CF6`) representing local inference.
* **Rust Guardrail Vector Badge:** A 14x14dp lock or shield SVG icon in emerald green (`#10B981`) representing compiled Rust safety.
* **Cloud Routing / n8n Badge:** A 14x14dp cloud-network node icon representing off-chain analysis.

---

### 3. Cryptocurrency & Network Tokens (Monochrome / Standard SVGs)

* **Chain Badges:** 16x16dp network logos for EVM L2s:
* Base (Blue Circle)
* Arbitrum (Blue Diamond)
* Ethereum (Minimalist Diamond)


* **Asset Tokens:** 24x24dp vector icons for supported assets:
* USDC (USD Coin circle)
* WETH (Wrapped Ether)
* Native ETH



---

### 4. UI System & Action Icons (Vector Line Icons)

* **Transaction Actions:**
* `ic_send.xml` (Paper plane or diagonal arrow pointing top-right)
* `ic_receive.xml` (Diagonal arrow pointing bottom-left)
* `ic_swap.xml` (Horizontal double arrows)


* **Chat & Input Controls:**
* `ic_mic.xml` (Microphone vector for voice input)
* `ic_terminal.xml` (`>_` prompt icon for expandable Rust FFI log drawer)
* `ic_copy.xml` (Duplicate square icon for copying wallet addresses)
* `ic_chevron_down.xml` / `ic_chevron_up.xml` (Drawer toggles)


* **Guardrail Status Indicators:**
* `ic_check_circle.xml` (Pass / Green checkmark)
* `ic_warning_triangle.xml` (Amber warning for daily cap / high slippage)
* `ic_block_shield.xml` (Red shield for Rust guardrail rejection)



---

### 5. Empty States & Placeholder Graphics

* **Empty Chat/History Illustration:** Minimal, dark-mode vector graphic (e.g., a subtle geometric vault or shield line art in `#232733`) shown when no transactions or queries have been executed yet.
* **QR Code Frame Container:** Clean 1px rounded vector frame for receiving funds/displaying wallet addresses.

---

### Recommended Implementation Strategy for Coding Agent

Instruct your coding agent to use **Android Vector Drawables (`.xml`)** or **Material Icon vectors (`androidx.compose.material.icons`)** for all system icons, and load token SVGs locally or via a standard token list library (like Uniswap's token asset CDN) to keep the app binary lean and rendering crisp on high-DPI Android displays.
