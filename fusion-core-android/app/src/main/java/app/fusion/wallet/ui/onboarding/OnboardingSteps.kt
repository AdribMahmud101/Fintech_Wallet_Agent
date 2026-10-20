package app.fusion.wallet.ui.onboarding

import app.fusion.wallet.ui.common.startActivitySafe
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fusion.wallet.R
import app.fusion.wallet.data.PermissionTier
import app.fusion.wallet.onboarding.CheckItem
import app.fusion.wallet.onboarding.DownloadState
import app.fusion.wallet.ui.components.AmountText
import app.fusion.wallet.ui.components.BentoTile
import app.fusion.wallet.ui.components.StatusChip
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType

// ---- Shared step scaffolding ----------------------------------------------------------------

@Composable
private fun StepHeader(title: String, desc: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
        Text(title, style = FusionType.HeadingMedium)
        Spacer(Modifier.height(8.dp))
        Text(desc, style = FusionType.BodyMedium, color = FusionColors.TextMuted)
    }
}

@Composable
private fun KeyValueRow(label: String, value: String, valueColor: Color = FusionColors.Text) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Text(value, style = FusionType.NumberBody, color = valueColor)
    }
}

@Composable
private fun LinkChip(label: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, FusionColors.Indigo.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) {
                runCatching { context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "$label link" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = FusionType.BodySmall.copy(fontWeight = FontWeight.Medium), color = FusionColors.Indigo)
        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, tint = FusionColors.Indigo, modifier = Modifier.size(12.dp))
    }
}

private fun shortAddr(a: String): String = if (a.length > 12) "${a.take(6)}…${a.takeLast(4)}" else a

// ---- Step 1: Welcome & role -----------------------------------------------------------------

@Composable
fun WelcomeRoleStep(role: OnboardingRole, onRole: (OnboardingRole) -> Unit) {
    StepHeader(stringResource(R.string.ob_welcome_title), stringResource(R.string.ob_welcome_subtitle))
    Text(stringResource(R.string.ob_role_title), style = FusionType.HeadingSmall)
    Spacer(Modifier.height(12.dp))
    val options = listOf(
        Triple(OnboardingRole.OWNER, R.string.ob_role_owner, R.string.ob_role_owner_desc),
        Triple(OnboardingRole.APPROVER, R.string.ob_role_approver, R.string.ob_role_approver_desc),
        Triple(OnboardingRole.AUDITOR, R.string.ob_role_auditor, R.string.ob_role_auditor_desc),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { (r, nameRes, descRes) ->
            val selected = r == role
            BentoTile(
                modifier = Modifier.fillMaxWidth(),
                tint = if (selected) FusionColors.Green else null,
                onClick = { onRole(r) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(nameRes), style = FusionType.HeadingSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(descRes), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                    }
                    Spacer(Modifier.width(12.dp))
                    SelectDot(selected)
                }
            }
        }
    }
}

@Composable
private fun SelectDot(selected: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) FusionColors.Green else Color.Transparent)
            .border(1.dp, if (selected) FusionColors.Green else FusionColors.Border, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = FusionColors.Bg, modifier = Modifier.size(14.dp))
    }
}

// ---- Step 2: Network & currency -------------------------------------------------------------

@Composable
fun NetworkCurrencyStep(vm: OnboardingViewModel, lang: String, onLang: (String) -> Unit) {
    StepHeader(stringResource(R.string.ob_network_title), stringResource(R.string.ob_network_desc))
    BentoTile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ob_network_name), style = FusionType.HeadingSmall)
            StatusChip(text = stringResource(R.string.ob_network_badge), tone = FusionColors.Amber)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ob_network_desc), style = FusionType.BodySmall, color = FusionColors.TextMuted)
    }
    Spacer(Modifier.height(12.dp))
    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ob_currency_label), style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_crypto_usdc), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(28.dp))
            Text(vm.treasuryCurrency, style = FusionType.HeadingSmall)
            Spacer(Modifier.weight(1f))
            StatusChip(text = stringResource(R.string.ob_currency_locked), tone = FusionColors.TextMuted)
        }
        Spacer(Modifier.height(6.dp))
        Text(vm.currencyNote, style = FusionType.BodySmall, color = FusionColors.TextMuted)
    }
    Spacer(Modifier.height(12.dp))
    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ob_language_label), style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LangOption("en", "English", lang == "en", onLang)
            LangOption("zh", "中文", lang == "zh", onLang)
        }
    }
}

@Composable
private fun LangOption(code: String, label: String, selected: Boolean, onLang: (String) -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) FusionColors.Green else FusionColors.SurfaceRaised)
            .border(1.dp, if (selected) FusionColors.Green else FusionColors.Border, RoundedCornerShape(10.dp))
            .clickable(role = Role.RadioButton) { onLang(code) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(label, style = FusionType.BodyMedium.copy(fontWeight = FontWeight.Medium), color = if (selected) FusionColors.Bg else FusionColors.Text)
    }
}

// ---- Step 3: Connect treasury ---------------------------------------------------------------

@Composable
fun ConnectTreasuryStep(vm: OnboardingViewModel) {
    val state by vm.treasury.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state.result == null && !state.loading) vm.runTreasuryChecks() }

    StepHeader(stringResource(R.string.ob_treasury_title), stringResource(R.string.ob_treasury_desc))

    val result = state.result
    if (result != null) {
        val summaryTone = if (result.allPassed) FusionColors.Green else FusionColors.Red
        val summaryText = if (result.allPassed) stringResource(R.string.ob_treasury_allpass) else (result.error ?: stringResource(R.string.ob_treasury_somefail))
        BentoTile(Modifier.fillMaxWidth(), tint = summaryTone) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusChip(text = summaryText, tone = summaryTone)
                if (result.blockNumber > 0) Text(stringResource(R.string.ob_treasury_block, result.blockNumber.toString()), style = FusionType.NumberCaption, color = FusionColors.TextMuted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            result.checks.forEach { CheckRow(it) }
        }
    } else {
        BentoTile(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FusionColors.Green)
                Text(stringResource(R.string.ob_treasury_rechecking), style = FusionType.BodyMedium, color = FusionColors.TextMuted)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    SecondaryButton(
        text = if (state.loading) stringResource(R.string.ob_treasury_rechecking) else stringResource(R.string.ob_treasury_recheck),
        enabled = !state.loading,
        onClick = { vm.runTreasuryChecks() },
    )
}

@Composable
private fun CheckRow(item: CheckItem) {
    val tone = if (item.passed) FusionColors.Green else FusionColors.Red
    BentoTile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (item.passed) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null, tint = tone, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = FusionType.BodyMedium.copy(fontWeight = FontWeight.Medium))
                Spacer(Modifier.height(2.dp))
                Text(item.detail, style = FusionType.BodySmall, color = FusionColors.TextMuted)
                Text(stringResource(R.string.ob_treasury_block, item.blockNumber.toString()), style = FusionType.NumberCaption, color = FusionColors.TextMuted)
            }
        }
        if (item.link != null) {
            Spacer(Modifier.height(8.dp))
            LinkChip(label = if (item.id == "sourcify") "Sourcify" else "Explorer", url = item.link!!)
        }
    }
}

// ---- Step 4: On-device AI model -------------------------------------------------------------

@Composable
fun ModelStep(vm: OnboardingViewModel, wifiOnly: Boolean, onWifiOnly: (Boolean) -> Unit) {
    val state by vm.model.collectAsStateWithLifecycle()
    StepHeader(stringResource(R.string.ob_model_title), stringResource(R.string.ob_model_desc))

    val status = state.status
    val ready = status?.ready == true
    val statusTone = if (ready) FusionColors.Green else FusionColors.Amber
    val statusText = when {
        status == null -> stringResource(R.string.ob_model_none)
        ready -> stringResource(R.string.ob_model_ready, status.verifiedFiles.size, status.totalFiles)
        status.verifiedFiles.isEmpty() -> stringResource(R.string.ob_model_none)
        else -> stringResource(R.string.ob_model_partial, status.verifiedFiles.size, status.totalFiles)
    }

    BentoTile(Modifier.fillMaxWidth(), tint = if (ready) FusionColors.Green else null) {
        StatusChip(text = statusText, tone = statusTone)
        if (status != null && status.totalSizeOnDisk > 0) {
            Spacer(Modifier.height(8.dp))
            Text("${status.totalSizeOnDisk / 1_000_000} MB / ${status.expectedTotalSize / 1_000_000} MB", style = FusionType.NumberCaption, color = FusionColors.TextMuted)
        }
    }

    Spacer(Modifier.height(12.dp))
    // Wi-Fi only toggle
    BentoTile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ob_model_wifi), style = FusionType.BodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = wifiOnly, onCheckedChange = onWifiOnly, enabled = !state.downloading,
                colors = SwitchDefaults.colors(checkedThumbColor = FusionColors.Green, checkedTrackColor = FusionColors.Green.copy(alpha = 0.3f), uncheckedThumbColor = FusionColors.TextMuted, uncheckedTrackColor = FusionColors.SurfaceRaised),
            )
        }
    }

    // Download progress / error
    when (val d = state.download) {
        is DownloadState.Downloading -> {
            Spacer(Modifier.height(12.dp))
            DownloadProgress(
                label = if (d.isResuming) stringResource(R.string.ob_model_resuming, d.currentFileName, d.percent.toInt())
                else stringResource(R.string.ob_model_downloading, d.currentFileName, d.percent.toInt()),
                fraction = d.percent / 100f,
            )
        }
        is DownloadState.Verifying -> {
            Spacer(Modifier.height(12.dp))
            DownloadProgress(label = stringResource(R.string.ob_model_verifying, d.currentFileName), fraction = null)
        }
        is DownloadState.CheckingStorage -> {
            Spacer(Modifier.height(12.dp))
            DownloadProgress(label = "…", fraction = null)
        }
        is DownloadState.Error -> {
            Spacer(Modifier.height(12.dp))
            BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Red) {
                Text(d.message, style = FusionType.BodySmall, color = FusionColors.Red)
            }
        }
        else -> {}
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.downloading) {
            SecondaryButton(text = stringResource(R.string.ob_model_cancel), onClick = { vm.cancelDownload() })
        } else if (!ready) {
            PrimaryButton(
                text = if ((status?.verifiedFiles?.isNotEmpty() == true)) stringResource(R.string.ob_model_resume) else stringResource(R.string.ob_model_download),
                onClick = { vm.startDownload(wifiOnly) },
            )
        }
        SecondaryButton(text = stringResource(R.string.ob_model_verify), enabled = !state.downloading, onClick = { vm.refreshModelStatus() })
    }

    // Warm-up self-test (only meaningful once the model is present)
    if (ready) {
        Spacer(Modifier.height(16.dp))
        val test = state.selfTest
        when {
            state.selfTesting -> BentoTile(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FusionColors.Indigo)
                    Text(stringResource(R.string.ob_model_selftesting), style = FusionType.BodyMedium, color = FusionColors.TextMuted)
                }
            }
            test != null && test.ok -> BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Indigo) {
                StatusChip(text = stringResource(R.string.ob_model_selftest_ok, test.choice, test.latencyLabel), tone = FusionColors.Indigo)
                Spacer(Modifier.height(6.dp))
                Text(test.plannerKind, style = FusionType.NumberCaption, color = FusionColors.TextMuted)
            }
            test != null && !test.ok -> BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Red) {
                Text(stringResource(R.string.ob_model_selftest_fail, test.error ?: ""), style = FusionType.BodySmall, color = FusionColors.Red)
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(text = stringResource(R.string.ob_model_selftest), enabled = !state.selfTesting, onClick = { vm.runSelfTest() })
    }
}

@Composable
private fun DownloadProgress(label: String, fraction: Float?) {
    BentoTile(Modifier.fillMaxWidth()) {
        Text(label, style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Spacer(Modifier.height(8.dp))
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = FusionColors.Green, trackColor = FusionColors.Border)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = FusionColors.Green, trackColor = FusionColors.Border)
        }
    }
}

// ---- Step 5: Keys ---------------------------------------------------------------------------

@Composable
fun KeysStep(vm: OnboardingViewModel, onImportGuardian: () -> Unit, onImportSession: () -> Unit) {
    val keys by vm.keys.collectAsStateWithLifecycle()
    // Re-read import status whenever we return from the (separate-process) import screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refreshKeys() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    StepHeader(stringResource(R.string.ob_keys_title), stringResource(R.string.ob_keys_desc))

    // Authority table
    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ob_keys_can_do), style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Spacer(Modifier.height(8.dp))
        AuthorityRow(stringResource(R.string.ob_keys_owner), stringResource(R.string.ob_keys_owner_cap), FusionColors.Green)
        AuthorityRow(stringResource(R.string.ob_keys_session), stringResource(R.string.ob_keys_session_cap), FusionColors.Indigo)
        AuthorityRow(stringResource(R.string.ob_keys_guardian), stringResource(R.string.ob_keys_guardian_cap), FusionColors.Red)
    }

    Spacer(Modifier.height(12.dp))
    KeyImportTile(
        title = stringResource(R.string.ob_keys_guardian),
        address = keys.guardianAddress,
        imported = keys.guardianImported,
        onImport = onImportGuardian,
        onRemove = { vm.removeGuardian() },
    )
    Spacer(Modifier.height(12.dp))
    KeyImportTile(
        title = stringResource(R.string.ob_keys_session),
        address = keys.sessionAddress,
        imported = keys.sessionImported,
        onImport = onImportSession,
        onRemove = { vm.removeSession() },
    )
}

@Composable
private fun AuthorityRow(name: String, cap: String, tone: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = FusionType.BodyMedium, modifier = Modifier.weight(1f))
        StatusChip(text = cap, tone = tone)
    }
}

@Composable
private fun KeyImportTile(title: String, address: String, imported: Boolean, onImport: () -> Unit, onRemove: () -> Unit) {
    BentoTile(Modifier.fillMaxWidth(), tint = if (imported) FusionColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = FusionType.BodyMedium.copy(fontWeight = FontWeight.Medium))
                if (address.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(shortAddr(address), style = FusionType.NumberCaption, color = FusionColors.TextMuted)
                }
            }
            StatusChip(
                text = if (imported) stringResource(R.string.ob_keys_imported) else stringResource(R.string.ob_keys_not_imported),
                tone = if (imported) FusionColors.Green else FusionColors.TextMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (imported) {
                SecondaryButton(text = stringResource(R.string.ob_keys_remove), onClick = onRemove)
            } else {
                PrimaryButton(
                    text = if (title == stringResource(R.string.ob_keys_guardian)) stringResource(R.string.ob_keys_import_guardian) else stringResource(R.string.ob_keys_import_session),
                    onClick = onImport,
                )
            }
        }
    }
}

// ---- Step 6: Policy review ------------------------------------------------------------------

@Composable
fun PolicyReviewStep(vm: OnboardingViewModel) {
    val state by vm.policy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadPolicy() }

    StepHeader(stringResource(R.string.ob_policy_title), stringResource(R.string.ob_policy_desc))

    val summary = state.summary
    when {
        summary != null -> {
            BentoTile(Modifier.fillMaxWidth()) {
                KeyValueRow(stringResource(R.string.ob_policy_tier), tierLabel(summary.tiers.tier))
            }
            Spacer(Modifier.height(12.dp))
            BentoTile(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ob_policy_daily), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                Spacer(Modifier.height(4.dp))
                AmountText(raw = summary.limits.dailyLimit.raw, style = FusionType.NumberMedium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AmountText(raw = summary.limits.dailyRemaining.raw, style = FusionType.NumberCaption, color = FusionColors.TextMuted)
                    Text(stringResource(R.string.ob_policy_daily_remaining), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                }
            }
            Spacer(Modifier.height(12.dp))
            BentoTile(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ob_policy_session), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                Spacer(Modifier.height(4.dp))
                AmountText(raw = summary.limits.sessionLimit.raw, style = FusionType.NumberMedium)
            }
            Spacer(Modifier.height(12.dp))
            BentoTile(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ob_policy_reserve), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                Spacer(Modifier.height(4.dp))
                AmountText(raw = summary.reserveBuffer.totalRequired.raw, style = FusionType.NumberMedium)
            }
            Spacer(Modifier.height(12.dp))
            BentoTile(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ob_policy_vendors), style = FusionType.BodyMedium, modifier = Modifier.weight(1f))
                    if (summary.vendorsLoaded) {
                        StatusChip(text = stringResource(R.string.ob_policy_vendors_count, summary.verifiedVendors.size), tone = FusionColors.Green)
                    } else {
                        StatusChip(text = stringResource(R.string.ob_policy_vendors_loading), tone = FusionColors.Amber)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Amber) {
                Text(summary.ruleNotice, style = FusionType.BodySmall, color = FusionColors.Text)
            }
        }
        state.error != null -> {
            BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Red) {
                Text(state.error!!, style = FusionType.BodySmall, color = FusionColors.Red)
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton(text = stringResource(R.string.ob_policy_retry), onClick = { vm.loadPolicy() })
        }
        else -> {
            BentoTile(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FusionColors.Green)
                    Text(stringResource(R.string.ob_policy_loading), style = FusionType.BodyMedium, color = FusionColors.TextMuted)
                }
            }
        }
    }
}

private fun tierLabel(tier: PermissionTier): String = when (tier) {
    PermissionTier.READ_ONLY -> "Read only"
    PermissionTier.OWNER_APPROVED -> "Owner approved"
    PermissionTier.AUTONOMOUS -> "Autonomous"
}
