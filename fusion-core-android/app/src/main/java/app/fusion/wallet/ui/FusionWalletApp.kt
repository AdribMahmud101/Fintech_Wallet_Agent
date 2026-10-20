package app.fusion.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.activity.ActivityScreen
import app.fusion.wallet.ui.shell.ShellViewModel
import app.fusion.wallet.ui.agent.AgentScreen
import app.fusion.wallet.ui.auth.AuthViewModel
import app.fusion.wallet.ui.auth.LoginScreen
import app.fusion.wallet.ui.auth.ProfileSheet
import app.fusion.wallet.ui.components.FusionBottomBar
import app.fusion.wallet.ui.components.NavRoute
import app.fusion.wallet.ui.i18n.AppLocale
import app.fusion.wallet.ui.bills.BillsScreen
import app.fusion.wallet.ui.judge.JudgeScreen
import app.fusion.wallet.ui.safety.SafetyScreen
import app.fusion.wallet.ui.onboarding.OnboardingFlow
import app.fusion.wallet.onboarding.OnboardingPrefs
import app.fusion.wallet.ui.settings.SettingsScreen
import app.fusion.wallet.ui.shell.FusionTopBar
import app.fusion.wallet.ui.splash.FusionSplashScreen
import app.fusion.wallet.ui.theme.FusionWalletTheme
import app.fusion.wallet.ui.treasury.TreasuryScreen
import kotlinx.coroutines.delay

/**
 * Root: sign in first, then Home (what you have) · AI Agent (what it does) · Safety (the evidence).
 *
 * The language switch re-resolves every string at runtime by handing Compose a locale-wrapped
 * Context, so switching English / 中文 never restarts the Activity or loses state.
 */
@Composable
fun FusionWalletApp(modifier: Modifier = Modifier) {
    val base = LocalContext.current
    var lang by rememberSaveable { mutableStateOf(AppLocale.saved(base)) }
    val localized = remember(lang, base) { AppLocale.wrap(base, lang) }
    val setLang: (String) -> Unit = { code -> lang = code; AppLocale.save(base, code) }

    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) {
        FusionWalletTheme {
            val auth: AuthViewModel = viewModel()
            val vm: AccountantViewModel = viewModel()
            val mainVm: MainViewModel = viewModel()
            val authState by auth.state.collectAsStateWithLifecycle()
            val state by vm.state.collectAsStateWithLifecycle()
            val walletState by mainVm.state.collectAsStateWithLifecycle()
            val user = authState.user
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, mainVm) {
                val observer = LifecycleEventObserver { _, _ ->
                    mainVm.autopilot.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                mainVm.autopilot.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer); mainVm.autopilot.setForeground(false) }
            }

            val shell: ShellViewModel = viewModel()
            val routeName by shell.destination.collectAsStateWithLifecycle()
            val route = NavRoute.valueOf(routeName)
            var profileOpen by rememberSaveable { mutableStateOf(false) }
            var settingsOpen by rememberSaveable { mutableStateOf(false) }
            // First run → onboarding; the persisted flag survives relaunch (OnboardingPrefs).
            var onboardingDone by rememberSaveable { mutableStateOf(OnboardingPrefs.isCompleted(base)) }

            LaunchedEffect(user?.role, onboardingDone, authState.checking) {
                mainVm.autopilot.setAccess(user != null && onboardingDone && user.role != app.fusion.wallet.accountant.data.UserRole.AUDITOR)
                if (!authState.checking && user == null) mainVm.autopilot.setEnabled(false)
            }

            // The signed-in role decides which controls are enabled (the contract still enforces the money rules).
            LaunchedEffect(user?.role) {
                vm.setRole(user?.role)
                if (user?.role == app.fusion.wallet.accountant.data.UserRole.AUDITOR) {
                    shell.navigate(NavRoute.Judge)
                }
            }
            LaunchedEffect(user == null) { if (user == null) { shell.navigate(NavRoute.Treasury) } }

            // Splash has a floor so it never flashes for one frame; it also waits for the session and first data read.
            var splashElapsed by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SPLASH_MIN_MS)
                splashElapsed = true
            }
            val splashVisible = !splashElapsed || authState.checking || (user != null && state.loading)

            if (!authState.checking && user == null) {
                LoginScreen(authState, lang, setLang, onSignIn = auth::signIn, onEdit = auth::clearError)
            } else if (user != null && !onboardingDone) {
                // The first-run stepper (UX plan §3). Finishing persists the flag and drops to Home.
                // Preselect the role of the person who signed in (Owner / approver / auditor),
                // unless they already picked one in an earlier run of the stepper.
                remember(user.role) {
                    if (!OnboardingPrefs.hasSelectedRole(base)) {
                        OnboardingPrefs.setSelectedRole(base, when (user.role) {
                            app.fusion.wallet.accountant.data.UserRole.OWNER -> "OWNER"
                            app.fusion.wallet.accountant.data.UserRole.MANAGER -> "APPROVER"
                            app.fusion.wallet.accountant.data.UserRole.AUDITOR -> "AUDITOR"
                        })
                    }
                    true
                }
                OnboardingFlow(
                    lang = lang,
                    onLang = setLang,
                    onFinish = { OnboardingPrefs.setCompleted(base); onboardingDone = true },
                )
            } else if (user != null) {
                BackHandler(enabled = settingsOpen || profileOpen || route != NavRoute.Treasury) {
                    if (settingsOpen) settingsOpen = false
                    else if (profileOpen) profileOpen = false
                    else shell.navigate(NavRoute.Treasury)
                }
                Scaffold(
                    modifier = modifier.fillMaxSize(),
                    topBar = { FusionTopBar(user = user, onProfile = { profileOpen = true }, onSettings = { settingsOpen = true }, demoData = walletState.source == app.fusion.wallet.data.DataSource.DEMO) },
                    bottomBar = { FusionBottomBar(selected = route, onSelect = shell::navigate) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Crossfade(targetState = route, animationSpec = tween(CROSSFADE_MS), label = "destination") { target ->
                            when (target) {
                                NavRoute.Treasury -> TreasuryScreen(vm = mainVm)
                                NavRoute.Activity -> ActivityScreen(vm = mainVm)
                                NavRoute.Settings -> {
                                    // Settings is an overlay, not a tab: a Settings route opens it over Home.
                                    LaunchedEffect(Unit) { settingsOpen = true; shell.navigate(NavRoute.Treasury) }
                                    TreasuryScreen(vm = mainVm)
                                }
                                NavRoute.Bills -> BillsScreen(vm = mainVm)
                                NavRoute.Agent -> AgentScreen(vm = mainVm)
                                NavRoute.Safety, NavRoute.Proof -> SafetyScreen(vm = mainVm)
                                NavRoute.Judge -> JudgeScreen(vm = mainVm, onBack = { shell.navigate(NavRoute.Treasury) })
                            }
                        }
                    }
                }
                if (profileOpen) {
                    ProfileSheet(
                        user = user, lang = lang, onLang = setLang,
                        onName = auth::setName, onAvatar = auth::setAvatar,
                        onSignOut = { profileOpen = false; shell.navigate(NavRoute.Treasury); auth.signOut() },
                        onDismiss = { profileOpen = false },
                        onOpenJudge = { profileOpen = false; shell.navigate(NavRoute.Judge) },
                    )
                }
                if (settingsOpen) {
                    SettingsScreen(
                        lang = lang,
                        onLang = setLang,
                        onClose = { settingsOpen = false },
                        onOpenJudge = { settingsOpen = false; shell.navigate(NavRoute.Judge) },
                        onRedoOnboarding = { OnboardingPrefs.reset(base); settingsOpen = false; onboardingDone = false },
                    )
                }
            }

            FusionSplashScreen(visible = splashVisible)
        }
    }
}

private const val CROSSFADE_MS = 140

/** Shortest the cold-start splash stays up; the real condition is the session check and first data read. */
private const val SPLASH_MIN_MS = 900L
