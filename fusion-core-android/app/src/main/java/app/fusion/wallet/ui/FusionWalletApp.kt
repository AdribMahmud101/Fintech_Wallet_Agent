package app.fusion.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import app.fusion.wallet.ui.agent.AgentScreen
import app.fusion.wallet.ui.auth.AuthViewModel
import app.fusion.wallet.ui.auth.LoginScreen
import app.fusion.wallet.ui.auth.ProfileSheet
import app.fusion.wallet.ui.components.FusionBottomBar
import app.fusion.wallet.ui.components.NavRoute
import app.fusion.wallet.ui.i18n.AppLocale
import app.fusion.wallet.ui.bills.BillsScreen
import app.fusion.wallet.ui.judge.JudgeScreen
import app.fusion.wallet.ui.proof.ProofScreen
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

            var routeName by rememberSaveable { mutableStateOf(NavRoute.Treasury.name) }
            val route = NavRoute.valueOf(routeName)
            var profileOpen by rememberSaveable { mutableStateOf(false) }

            // The signed-in role decides which controls are enabled (the contract still enforces the money rules).
            LaunchedEffect(user?.role) {
                vm.setRole(user?.role)
                if (user?.role == app.fusion.wallet.accountant.data.UserRole.AUDITOR) {
                    routeName = NavRoute.Judge.name
                }
            }
            LaunchedEffect(user == null) { if (user == null) { profileOpen = false; routeName = NavRoute.Treasury.name } }

            // Splash has a floor so it never flashes for one frame; it also waits for the session and first data read.
            var splashElapsed by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SPLASH_MIN_MS)
                splashElapsed = true
            }
            val splashVisible = !splashElapsed || authState.checking || (user != null && state.loading)

            if (!authState.checking && user == null) {
                LoginScreen(authState, lang, setLang, onSignIn = auth::signIn, onEdit = auth::clearError)
            } else if (user != null) {
                BackHandler(enabled = profileOpen || route != NavRoute.Treasury) {
                    if (profileOpen) profileOpen = false else routeName = NavRoute.Treasury.name
                }
                Scaffold(
                    modifier = modifier.fillMaxSize(),
                    topBar = { FusionTopBar(user = user, onProfile = { profileOpen = true }, demoData = walletState.source == app.fusion.wallet.data.DataSource.DEMO) },
                    bottomBar = { FusionBottomBar(selected = route, onSelect = { routeName = it.name }) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Crossfade(targetState = route, animationSpec = tween(CROSSFADE_MS), label = "destination") { target ->
                            when (target) {
                                NavRoute.Treasury -> TreasuryScreen(vm = mainVm)
                                NavRoute.Bills -> BillsScreen(vm = mainVm)
                                NavRoute.Agent -> AgentScreen(vm = mainVm)
                                NavRoute.Safety, NavRoute.Proof -> ProofScreen(vm = mainVm)
                                NavRoute.Judge -> JudgeScreen(vm = mainVm, onBack = { routeName = NavRoute.Treasury.name })
                            }
                        }
                    }
                }
                if (profileOpen) {
                    ProfileSheet(
                        user = user, lang = lang, onLang = setLang,
                        onName = auth::setName, onAvatar = auth::setAvatar,
                        onSignOut = { profileOpen = false; auth.signOut() },
                        onDismiss = { profileOpen = false },
                        onOpenJudge = { routeName = NavRoute.Judge.name },
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
