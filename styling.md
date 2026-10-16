Got it—strictly styling tokens, theme definitions, and visual variables without layout structures.

Here is the exact **"Obsidian Institutional" Design System & Styling Specification** for your coding agent to use in Jetpack Compose:

---

### 1. Color Palette (Hex Tokens)

#### Backgrounds & Surfaces

```kotlin
val CanvasBackground = Color(0xFF08090C)      // Main screen background (Deep Obsidian)
val SurfaceLevel1 = Color(0xFF101217)         // Primary cards, dialogs, bottom sheets
val SurfaceLevel2 = Color(0xFF181B22)         // Input fields, chip backgrounds, nested containers
val SurfaceLevel3 = Color(0xFF232733)         // Hover/pressed states, secondary borders

```

#### Borders & Dividers

```kotlin
val BorderSubtle = Color(0xFF232733)          // 1px standard card outline (100% opaque)
val BorderFocused = Color(0xFF374151)         // Focused input border or active state outline

```

#### Typography Colors

```kotlin
val TextPrimary = Color(0xFFF9FAFB)           // Primary headers, main body text
val TextSecondary = Color(0xFF9CA3AF)         // Secondary labels, subtitles, timestamps
val TextMuted = Color(0xFF6B7280)             // Code comments, disabled states, nonces

```

#### Status & Indicator Accents

```kotlin
val AccentPrimary = Color(0xFF6366F1)         // Primary brand / action buttons (Indigo)
val AccentAiViolet = Color(0xFF8B5CF6)        // Edge AI / MNN execution badge indicator
val StatusSuccess = Color(0xFF10B981)         // Verified guardrails, success checks (Emerald)
val StatusWarning = Color(0xFFF59E0B)         // Nearing daily velocity cap, high slippage (Amber)
val StatusError = Color(0xFFEF4444)           // Blocked transactions, guardrail violations (Crimson)

```

---

### 2. Typography Rules

#### Font Families

* **Primary Sans-Serif:** `Inter` (Fallback: `Roboto` / System Default)
* **Monospace Engine:** `JetBrains Mono` (Fallback: `Fira Code` / System Monospace)

#### Text Styles & Usage

* **Display / Title:** `Inter SemiBold`, 16sp, Line Height: 22sp, Color: `TextPrimary`
* **Body Main:** `Inter Regular`, 14sp, Line Height: 20sp, Color: `TextPrimary`
* **Subtext / Labels:** `Inter Medium`, 12sp, Line Height: 16sp, Color: `TextSecondary`
* **Code / Technical Data:** `JetBrains Mono Regular`, 12sp, Line Height: 16sp, Color: `TextPrimary` (Used for hashes, addresses, JSON, FFI logs)
* **Micro Badge Label:** `JetBrains Mono Medium`, 10sp / 11sp, Letter Spacing: 0.5sp, Uppercase

---

### 3. Component Styling Tokens

#### Shapes & Radii

```kotlin
val RadiusSmall = 6.dp                       // Chips, badges, status dots
val RadiusMedium = 12.dp                     // Standard cards, input fields, containers
val RadiusLarge = 16.dp                      // Dialogs, bottom sheets

```

#### Outlines & Elevation

* **Card Elevation:** Zero shadows. Flat surfaces only.
* **Card Outlines:** Every container uses a `1.dp` solid border with `BorderSubtle` (`#232733`) against `CanvasBackground` (`#08090C`).
* **Status Pill Styling:** Flat `#181B22` background, `1.dp` border of `#232733`, containing a `6.dp` solid status dot (`StatusSuccess`, `StatusWarning`, `StatusError`, or `AccentAiViolet`).

---

### 4. Jetpack Compose Theme Setup Code

You can paste this code snippet directly to your coding agent to define the theme file (`Theme.kt`):

```kotlin
package app.fusion.wallet.ui.theme

import androidx.compose.material3.DarkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ObsidianColorScheme = DarkColorScheme(
    primary = Color(0xFF6366F1),
    onPrimary = Color(0xFFF9FAFB),
    background = Color(0xFF08090C),
    onBackground = Color(0xFFF9FAFB),
    surface = Color(0xFF101217),
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = Color(0xFF181B22),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF232733),
    error = Color(0xFFEF4444),
    onError = Color(0xFFF9FAFB)
)

@Composable
fun FusionWalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObsidianColorScheme,
        content = content
    )
}

```
