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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun FusionTopBar(user: UserEntity?, onProfile: () -> Unit, demoData: Boolean, modifier: Modifier = Modifier) {
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
            FusionBrandLockup(height = 22.dp)
            StatusPill(label = stringResource(R.string.badge_network), tone = Tone.Warn)
            if (demoData) StatusPill(label = stringResource(R.string.badge_demo), tone = Tone.Earning)
        }
        if (user != null) {
            val label = stringResource(R.string.nav_settings)
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onProfile).semantics { role = Role.Button; contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { AvatarView(user.displayName, user.avatarColor, user.avatarIcon, 40.dp) }
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
