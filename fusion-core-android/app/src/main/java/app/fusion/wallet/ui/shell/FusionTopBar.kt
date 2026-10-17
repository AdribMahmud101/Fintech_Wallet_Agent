package app.fusion.wallet.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.components.FusionBrandLockup
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.i18n.AppLocale
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.Obsidian

/**
 * Honest top bar: brand, a permanent TEST-network badge (no mainnet look-alike), an optional
 * DEMO DATA badge (debug fixtures only) and the language switch.
 */
@Composable
fun FusionTopBar(lang: String, onLang: (String) -> Unit, demoData: Boolean, modifier: Modifier = Modifier) {
    val surfaces = FusionTheme.surfaces
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(surfaces.canvas)
            .statusBarsPadding()
            .padding(horizontal = FusionDims.gutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            FusionBrandLockup(height = 22.dp)
            LangToggle(lang, onLang)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(label = stringResource(R.string.badge_testnet), tone = Obsidian.StatusWarning)
            if (demoData) StatusPill(label = stringResource(R.string.badge_demo), tone = Obsidian.AccentAiViolet)
        }
    }
}

@Composable
private fun LangToggle(lang: String, onLang: (String) -> Unit) {
    val surfaces = FusionTheme.surfaces
    val shape = RoundedCornerShape(FusionDims.radiusSmall)
    val label = stringResource(R.string.lang_switch)
    Row(
        Modifier
            .clip(shape)
            .background(surfaces.level2)
            .border(FusionDims.hairline, surfaces.border, shape)
            .semantics { contentDescription = label; role = Role.Switch },
    ) {
        listOf(AppLocale.EN to "EN", AppLocale.ZH to "中文").forEach { (code, text) ->
            val on = lang == code
            Text(
                text,
                modifier = Modifier
                    .clickable { onLang(code) }
                    .background(if (on) Accessible.ButtonFill else surfaces.level2)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (on) androidx.compose.ui.graphics.Color.White else Accessible.TextMuted,
            )
        }
    }
}
