package app.fusion.wallet.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import app.fusion.wallet.accountant.data.UserEntity
import app.fusion.wallet.ui.auth.AvatarView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.alpha
import app.fusion.wallet.ui.motion.eventPulse
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.data.DataSource
import app.fusion.wallet.ui.theme.FusionColors
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.components.FusionBrandLockup
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.i18n.AppLocale
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme

/**
 * One slim row: brand, a permanent TESTNET badge (so nothing here is mistaken for real money), a
 * DEMO badge in debug builds only, and the language switch.
 */
@Composable
fun FusionTopBar(user: UserEntity?, onProfile: () -> Unit, demoData: Boolean, modifier: Modifier = Modifier, onSettings: (() -> Unit)? = null, wallet: WalletState = WalletState()) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(FusionTheme.surfaces.canvas)
            .statusBarsPadding()
            .padding(horizontal = FusionDims.gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FusionBrandLockup(height = 22.dp)
                    StatusPill(label = stringResource(R.string.badge_network), tone = Tone.Warn)
                }
                val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
                val cached = wallet.source != DataSource.LIVE || wallet.isDisplayStale(now) || wallet.refreshing
                val block = wallet.blockNumber
                val pulse = eventPulse(block)
                val tone = if (cached) FusionColors.Amber else FusionColors.Green
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).alpha(if (cached) 1f else 1f - pulse * 0.55f).background(tone, CircleShape))
                    Text(if (block == null) "Waiting for block" else "${if (cached) "Cached" else "Live"} · block $block", style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 1)
                }
            }
            if (demoData) StatusPill(label = stringResource(R.string.badge_demo), tone = Tone.Earning)
        }
        if (user != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onSettings != null) {
                    val settingsLabel = stringResource(R.string.open_settings)
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onSettings).semantics { role = Role.Button; contentDescription = settingsLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null, tint = Accessible.TextMuted, modifier = Modifier.size(22.dp))
                    }
                }
                val label = stringResource(R.string.profile_open)
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onProfile).semantics { role = Role.Button; contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) { AvatarView(user.displayName, user.avatarColor, user.avatarIcon, 40.dp) }
            }
        }
    }
}

/** English / 中文 switch. Used on the login screen and in the profile. */
@Composable
fun LangToggle(lang: String, onLang: (String) -> Unit) {
    val surfaces = FusionTheme.surfaces
    val shape = RoundedCornerShape(FusionDims.radiusSmall)
    val label = stringResource(R.string.lang_switch)
    Row(
        Modifier
            .clip(shape)
            .background(surfaces.level2)
            .border(FusionDims.hairline, surfaces.border, shape)
            .semantics { contentDescription = label },
    ) {
        listOf(AppLocale.EN to "EN", AppLocale.ZH to "中文").forEach { (code, text) ->
            val on = lang == code
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable { onLang(code) }
                    .background(if (on) Accessible.ButtonFill else surfaces.level2)
                    .padding(horizontal = 12.dp)
                    .semantics { role = Role.RadioButton; selected = on },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Color.White else Accessible.TextMuted,
                )
            }
        }
    }
}
