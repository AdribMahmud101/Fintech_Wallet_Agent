package app.fusion.wallet.ui.settings

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.BuildConfig
import app.fusion.wallet.R
import app.fusion.wallet.onboarding.DownloadState
import app.fusion.wallet.ui.onboarding.GUARDIAN_IMPORT_CLASS
import app.fusion.wallet.ui.onboarding.PrimaryButton
import app.fusion.wallet.ui.onboarding.SESSION_IMPORT_CLASS
import app.fusion.wallet.ui.onboarding.SecondaryButton
import app.fusion.wallet.ui.onboarding.launchKeyImport
import app.fusion.wallet.ui.components.BentoTile
import app.fusion.wallet.ui.components.StatusChip
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType

/**
 * Settings (UX plan §4): Network & deployment, On-device model, Keys, Language, Judge mode,
 * About. Every row does real work — opens the live explorer/Sourcify, re-downloads and
 * verifies the on-device model against pinned hashes, removes imported keys, switches language.
 */
@Composable
fun SettingsScreen(
    lang: String,
    onLang: (String) -> Unit,
    onClose: () -> Unit,
    onOpenJudge: () -> Unit,
    onRedoOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val model by vm.model.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { vm.refreshKeys(); vm.refreshModel() } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize().background(FusionColors.Bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.set_title), style = FusionType.HeadingMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.set_close), tint = FusionColors.TextMuted)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // --- Network & deployment ---
            SectionHeader(stringResource(R.string.set_network))
            vm.contracts.forEach { ContractRow(it) }

            // --- On-device model ---
            SectionHeader(stringResource(R.string.set_model))
            ModelSection(model, onRedownload = { vm.redownload() }, onCancel = { vm.cancelDownload() }, onVerify = { vm.refreshModel() })

            // --- Keys ---
            SectionHeader(stringResource(R.string.set_keys))
            KeyRow(
                title = stringResource(R.string.ob_keys_guardian),
                address = keys.guardianAddress,
                imported = keys.guardianImported,
                importText = stringResource(R.string.ob_keys_import_guardian),
                onImport = { launchKeyImport(context, GUARDIAN_IMPORT_CLASS) },
                onRemove = { vm.removeGuardian() },
            )
            KeyRow(
                title = stringResource(R.string.ob_keys_session),
                address = keys.sessionAddress,
                imported = keys.sessionImported,
                importText = stringResource(R.string.ob_keys_import_session),
                onImport = { launchKeyImport(context, SESSION_IMPORT_CLASS) },
                onRemove = { vm.removeSession() },
            )

            // --- Language ---
            SectionHeader(stringResource(R.string.set_language))
            BentoTile(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LangChip("en", "English", lang == "en", onLang)
                    LangChip("zh", "中文", lang == "zh", onLang)
                }
            }

            // --- Judge mode ---
            SectionHeader(stringResource(R.string.set_judge))
            BentoTile(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.set_judge_desc), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                Spacer(Modifier.height(12.dp))
                CommandRow(stringResource(R.string.set_judge_verify_all), "bash scripts/judge/verify_all.sh")
                Spacer(Modifier.height(10.dp))
                CommandRow(stringResource(R.string.set_judge_live_demo), "bash scripts/demo/live_demo.sh")
                Spacer(Modifier.height(14.dp))
                PrimaryButton(text = stringResource(R.string.set_judge_open), onClick = onOpenJudge)
            }

            // --- About ---
            SectionHeader(stringResource(R.string.set_about))
            BentoTile(Modifier.fillMaxWidth()) {
                AboutRow(stringResource(R.string.set_about_version), BuildConfig.VERSION_NAME)
                AboutRow(stringResource(R.string.set_about_build), if (BuildConfig.DEBUG) "debug (${BuildConfig.VERSION_CODE})" else "release (${BuildConfig.VERSION_CODE})")
                AboutRow(stringResource(R.string.set_about_model_id), "LFM2.5-230M-MNN")
                AboutRow(stringResource(R.string.set_about_network), "Base Sepolia · 84532")
            }
            Spacer(Modifier.height(4.dp))
            SecondaryButton(text = stringResource(R.string.set_redo_onboarding), onClick = onRedoOnboarding)
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = FusionType.HeadingSmall, color = FusionColors.Text)
}

@Composable
private fun ContractRow(c: SettingsViewModel.ContractInfo) {
    val clipboard = LocalClipboardManager.current
    BentoTile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.label, style = FusionType.BodyMedium.copy(fontWeight = FontWeight.Medium))
                Spacer(Modifier.height(2.dp))
                Text(c.address, style = FusionType.NumberCaption, color = FusionColors.TextMuted, maxLines = 1)
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(c.address)) }, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.set_copy), tint = FusionColors.TextMuted, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkChip(stringResource(R.string.set_basescan), c.explorerUrl)
            if (c.sourcifyUrl != null) LinkChip(stringResource(R.string.set_sourcify), c.sourcifyUrl)
        }
    }
}

@Composable
private fun ModelSection(model: SettingsViewModel.ModelInfo, onRedownload: () -> Unit, onCancel: () -> Unit, onVerify: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val status = model.status
    val ready = status?.ready == true
    BentoTile(Modifier.fillMaxWidth(), tint = if (ready) FusionColors.Green else null) {
        StatusChip(
            text = if (status == null) stringResource(R.string.ob_model_none)
            else stringResource(R.string.ob_model_partial, status.verifiedFiles.size, status.totalFiles),
            tone = if (ready) FusionColors.Green else FusionColors.Amber,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_model_hash), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                Text(if (model.hash.isEmpty()) "—" else "${model.hash.take(16)}…", style = FusionType.NumberCaption, color = FusionColors.Text)
            }
            if (model.hash.isNotEmpty()) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(model.hash)) }, modifier = Modifier.size(44.dp)) {
                    Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.set_copy), tint = FusionColors.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        AboutRow(stringResource(R.string.set_model_size), "${(status?.totalSizeOnDisk ?: 0) / 1_000_000} MB")
        AboutRow(
            stringResource(R.string.set_model_latency),
            model.lastLatencyMs?.let { "$it ms" } ?: stringResource(R.string.set_model_latency_none),
        )
        when (val d = model.download) {
            is DownloadState.Downloading -> {
                Spacer(Modifier.height(10.dp))
                Text("${d.currentFileName} · ${d.percent.toInt()}%", style = FusionType.BodySmall, color = FusionColors.TextMuted)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = FusionColors.Green, trackColor = FusionColors.Border)
            }
            is DownloadState.Verifying -> {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.ob_model_verifying, d.currentFileName), style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
            is DownloadState.Error -> {
                Spacer(Modifier.height(10.dp))
                Text(d.message, style = FusionType.BodySmall, color = FusionColors.Red)
            }
            else -> {}
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.downloading) {
                SecondaryButton(text = stringResource(R.string.ob_model_cancel), onClick = onCancel)
            } else {
                PrimaryButton(text = stringResource(R.string.set_redownload), onClick = onRedownload)
            }
            SecondaryButton(text = stringResource(R.string.set_verify), enabled = !model.downloading, onClick = onVerify)
        }
    }
}

@Composable
private fun KeyRow(title: String, address: String, imported: Boolean, importText: String, onImport: () -> Unit, onRemove: () -> Unit) {
    BentoTile(Modifier.fillMaxWidth(), tint = if (imported) FusionColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = FusionType.BodyMedium.copy(fontWeight = FontWeight.Medium))
                if (address.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(if (address.length > 12) "${address.take(6)}…${address.takeLast(4)}" else address, style = FusionType.NumberCaption, color = FusionColors.TextMuted)
                }
            }
            StatusChip(
                text = if (imported) stringResource(R.string.ob_keys_imported) else stringResource(R.string.ob_keys_not_imported),
                tone = if (imported) FusionColors.Green else FusionColors.TextMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (imported) SecondaryButton(text = stringResource(R.string.ob_keys_remove), onClick = onRemove)
        else PrimaryButton(text = importText, onClick = onImport)
    }
}

@Composable
private fun CommandRow(label: String, command: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FusionColors.SurfaceRaised).border(1.dp, FusionColors.Border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(command, style = FusionType.NumberCaption, color = FusionColors.Text, modifier = Modifier.weight(1f), maxLines = 2)
            IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.set_copy), tint = FusionColors.Indigo, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Text(value, style = FusionType.NumberBody, color = FusionColors.Text)
    }
}

@Composable
private fun LinkChip(label: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, FusionColors.Indigo.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "$label link" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = FusionType.BodySmall.copy(fontWeight = FontWeight.Medium), color = FusionColors.Indigo)
        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, tint = FusionColors.Indigo, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun LangChip(code: String, label: String, selected: Boolean, onLang: (String) -> Unit) {
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
