package app.fusion.wallet.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.accountant.data.UserRole
import app.fusion.wallet.auth.DemoAccount
import app.fusion.wallet.auth.DemoUsers
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.components.FusionBrandLockup
import app.fusion.wallet.ui.shell.LangToggle
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import kotlinx.coroutines.delay

@Composable
fun roleLabel(role: UserRole): String = stringResource(
    when (role) {
        UserRole.OWNER -> R.string.role_OWNER
        UserRole.MANAGER -> R.string.role_MANAGER
        UserRole.AUDITOR -> R.string.role_AUDITOR
    },
)

/**
 * Demo sign-in. Real credential checks run against salted hashes; the "demo accounts" below just fill the
 * form so a reviewer never has to type. It never signs in without the form being submitted.
 */
@Composable
fun LoginScreen(state: AuthState, lang: String, onLang: (String) -> Unit, onSignIn: (String, String) -> Unit, onEdit: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) { delay(1_000); value = System.currentTimeMillis() / 1000 }
    }
    val lockLeft = (state.lockedUntil - now).coerceAtLeast(0)
    val locked = state.error == AuthError.LOCKED && lockLeft > 0
    val canSubmit = !state.busy && !locked && email.isNotBlank() && password.isNotEmpty()
    fun submit() { if (canSubmit) { focus.clearFocus(); onSignIn(email, password) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(FusionTheme.surfaces.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FusionDims.gutter, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            FusionBrandLockup(height = 26.dp)
            LangToggle(lang, onLang)
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.login_sub), style = MaterialTheme.typography.bodyLarge, color = Muted, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email, onValueChange = { email = it; onEdit() }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.login_email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it; onEdit() }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.login_password)) },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { show = !show }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(if (show) R.string.login_hide else R.string.login_show),
                    )
                }
            },
        )

        // Error: icon + words (never colour alone), announced politely to screen readers.
        val errorText = when (state.error) {
            AuthError.INVALID_INPUT -> stringResource(R.string.login_err_input)
            AuthError.INVALID_CREDENTIALS -> stringResource(R.string.login_err_creds)
            AuthError.LOCKED -> if (lockLeft > 0) stringResource(R.string.login_err_locked, lockLeft.toString()) else null
            null -> null
        }
        if (errorText != null) {
            Row(Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Tone.Bad, modifier = Modifier.size(18.dp))
                Text(errorText, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Tone.Bad)
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { submit() }, enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accessible.ButtonFill),
        ) {
            Text(stringResource(if (state.busy) R.string.login_busy else R.string.login_button), style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.login_demo_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.login_demo_hint), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        DemoUsers.accounts.forEach { a ->
            DemoAccountCard(a, selected = email.trim().equals(a.email, ignoreCase = true)) { email = a.email; password = a.password; show = false; onEdit() }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.login_notice), style = MaterialTheme.typography.bodySmall, color = Muted)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DemoAccountCard(a: DemoAccount, selected: Boolean, onClick: () -> Unit) {
    val surfaces = FusionTheme.surfaces
    val shape = RoundedCornerShape(FusionDims.radiusMedium)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(surfaces.level1, shape)
            .border(if (selected) 2.dp else FusionDims.hairline, if (selected) Accessible.AccentText else surfaces.border, shape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(a.name, a.avatarColor, a.avatarIcon, 44.dp)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(roleLabel(a.role), style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(a.email + " · " + stringResource(R.string.login_demo_pw, a.password), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}
