package app.fusion.wallet.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.accountant.data.AvatarIcon
import app.fusion.wallet.accountant.data.UserEntity
import app.fusion.wallet.accountant.data.UserRole
import app.fusion.wallet.auth.caps
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.shell.LangToggle
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme

/** Profile: who you are, what your role allows, avatar editing, language and sign-out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    user: UserEntity, lang: String, onLang: (String) -> Unit,
    onName: (String) -> Unit, onAvatar: (Int, AvatarIcon) -> Unit, onSignOut: () -> Unit, onDismiss: () -> Unit,
) {
    var name by remember(user.id) { mutableStateOf(user.displayName) }
    val caps = user.role.caps()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = FusionDims.gutter).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarView(user.displayName, user.avatarColor, user.avatarIcon, 72.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(user.displayName, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(roleLabel(user.role), style = MaterialTheme.typography.bodyLarge, color = Tone.Earning, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.profile_signed_as, user.email), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }

            ObsidianCard {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.profile_can), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(roleDesc(user.role)), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                    Ability(caps.manageCloud, stringResource(R.string.can_cloud))
                    Ability(caps.controlAgent, stringResource(R.string.can_pause))
                    Ability(caps.approveLarge, stringResource(R.string.can_approve))
                    Text(stringResource(R.string.profile_ui_note), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
                }
            }

            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.profile_name)) },
            )
            TextButton(onClick = { onName(name) }, enabled = name.isNotBlank() && name.trim() != user.displayName, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.profile_save))
            }

            Text(stringResource(R.string.profile_avatar), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AvatarPalette.colors.forEachIndexed { i, c ->
                    val on = user.avatarColor == i
                    val label = stringResource(colorNameRes(i))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(c)
                            .border(if (on) BorderStroke(3.dp, Color.White) else BorderStroke(0.dp, Color.Transparent), CircleShape)
                            .clickable { onAvatar(i, user.avatarIcon) }
                            .semantics { role = Role.RadioButton; selected = on; contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AvatarIcon.entries.forEach { ic ->
                    val on = user.avatarIcon == ic
                    val label = stringResource(iconNameRes(ic))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(FusionTheme.surfaces.level2)
                            .border(if (on) BorderStroke(2.dp, Accessible.AccentText) else BorderStroke(1.dp, FusionTheme.surfaces.border), CircleShape)
                            .clickable { onAvatar(user.avatarColor, ic) }
                            .semantics { role = Role.RadioButton; selected = on; contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        val g = AvatarPalette.glyph(ic)
                        if (g != null) Icon(g, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                        else Text(AvatarPalette.initials(user.displayName), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            ObsidianCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Info(stringResource(R.string.profile_company), user.company)
                    Info(stringResource(R.string.profile_email), user.email)
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.profile_language), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                LangToggle(lang, onLang)
            }

            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.profile_signout), color = Tone.Bad, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Ability(allowed: Boolean, text: String) {
    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (allowed) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null,
            tint = if (allowed) Tone.Good else Muted, modifier = Modifier.size(20.dp),
        )
        Text(
            text, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium,
            color = if (allowed) MaterialTheme.colorScheme.onSurface else Muted,
        )
    }
}

@Composable
private fun Info(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun roleDesc(r: UserRole) = when (r) {
    UserRole.OWNER -> R.string.role_desc_OWNER
    UserRole.MANAGER -> R.string.role_desc_MANAGER
    UserRole.AUDITOR -> R.string.role_desc_AUDITOR
}

private fun colorNameRes(i: Int) = when (i) {
    0 -> R.string.av_color_0
    1 -> R.string.av_color_1
    2 -> R.string.av_color_2
    3 -> R.string.av_color_3
    4 -> R.string.av_color_4
    else -> R.string.av_color_5
}

private fun iconNameRes(i: AvatarIcon) = when (i) {
    AvatarIcon.INITIALS -> R.string.av_icon_INITIALS
    AvatarIcon.PERSON -> R.string.av_icon_PERSON
    AvatarIcon.BRIEFCASE -> R.string.av_icon_BRIEFCASE
    AvatarIcon.SHIELD -> R.string.av_icon_SHIELD
    AvatarIcon.STAR -> R.string.av_icon_STAR
    AvatarIcon.ROCKET -> R.string.av_icon_ROCKET
    AvatarIcon.LEAF -> R.string.av_icon_LEAF
}
