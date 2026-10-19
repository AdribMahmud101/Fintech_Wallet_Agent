package app.fusion.wallet.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Text
import app.fusion.wallet.R
import app.fusion.wallet.onboarding.OnboardingPrefs
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType

enum class OnboardingRole(val id: String) { OWNER("OWNER"), APPROVER("APPROVER"), AUDITOR("AUDITOR") }

const val ONBOARDING_STEPS = 6

/**
 * First-run stepper (UX plan §3). Six steps, each doing real work; Skip and Back on every step;
 * step index and selections survive rotation via [rememberSaveable], while the async results
 * (treasury checks, model download, policy) live in [OnboardingViewModel] across config changes.
 */
@Composable
fun OnboardingFlow(
    lang: String,
    onLang: (String) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    vm: OnboardingViewModel = viewModel(),
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var roleName by rememberSaveable { mutableStateOf(OnboardingPrefs.getSelectedRole(context)) }
    var wifiOnly by rememberSaveable { mutableStateOf(OnboardingPrefs.isWifiOnly(context)) }
    val role = runCatching { OnboardingRole.valueOf(roleName) }.getOrDefault(OnboardingRole.AUDITOR)

    fun goBack() { if (step > 0) step-- }
    fun advance() { if (step < ONBOARDING_STEPS - 1) step++ else onFinish() }

    BackHandler(enabled = step > 0) { goBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FusionColors.Bg)
            .statusBarsPadding(),
    ) {
        StepProgress(step = step)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            when (step) {
                0 -> WelcomeRoleStep(role = role, onRole = {
                    roleName = it.name
                    OnboardingPrefs.setSelectedRole(context, it.name)
                })
                1 -> NetworkCurrencyStep(vm = vm, lang = lang, onLang = onLang)
                2 -> ConnectTreasuryStep(vm = vm)
                3 -> ModelStep(vm = vm, wifiOnly = wifiOnly, onWifiOnly = { wifiOnly = it })
                4 -> KeysStep(
                    vm = vm,
                    onImportGuardian = { launchKeyImport(context, GUARDIAN_IMPORT_CLASS) },
                    onImportSession = { launchKeyImport(context, SESSION_IMPORT_CLASS) },
                )
                5 -> PolicyReviewStep(vm = vm)
            }
            Spacer(Modifier.height(16.dp))
        }

        StepNav(
            step = step,
            onBack = ::goBack,
            onSkip = ::advance,
            onNext = ::advance,
        )
    }
}

@Composable
private fun StepProgress(step: Int) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.ob_step_n_of, step + 1, ONBOARDING_STEPS),
            style = FusionType.BodySmall,
            color = FusionColors.TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(ONBOARDING_STEPS) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step) FusionColors.Green else FusionColors.Border),
                )
            }
        }
    }
}

@Composable
private fun StepNav(step: Int, onBack: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit) {
    val last = step == ONBOARDING_STEPS - 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostButton(
            text = stringResource(R.string.ob_back),
            enabled = step > 0,
            onClick = onBack,
        )
        Spacer(Modifier.weight(1f))
        GhostButton(
            text = stringResource(R.string.ob_skip),
            enabled = true,
            onClick = onSkip,
        )
        Spacer(Modifier.width(10.dp))
        PrimaryButton(
            text = stringResource(if (last) R.string.ob_finish else R.string.ob_next),
            onClick = onNext,
        )
    }
}

// --- Shared K23-styled controls (kept local so §3/§4 never redefine color tokens) ------------

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) FusionColors.Green else FusionColors.Border)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FusionType.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) FusionColors.Bg else FusionColors.TextMuted,
        )
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FusionType.BodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) FusionColors.Text else FusionColors.TextMuted.copy(alpha = 0.5f),
        )
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FusionColors.SurfaceRaised)
            .border(1.dp, FusionColors.Border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FusionType.BodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) FusionColors.Text else FusionColors.TextMuted,
        )
    }
}

// --- Import activity launch ------------------------------------------------------------------

const val GUARDIAN_IMPORT_CLASS = "app.fusion.wallet.signer.GuardianImportActivity"
const val SESSION_IMPORT_CLASS = "app.fusion.wallet.agent.SessionImportActivity"

/**
 * Launches the existing hardware-key import screen by explicit component. These live in the
 * debug source set, so a release build (where they are absent) falls through gracefully.
 */
fun launchKeyImport(context: Context, className: String): Boolean = try {
    context.startActivity(
        Intent().setComponent(ComponentName(context.packageName, className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
} catch (e: ActivityNotFoundException) {
    false
}
