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
import app.fusion.wallet.ui.components.FusionBottomBar
import app.fusion.wallet.ui.components.NavRoute
import app.fusion.wallet.ui.i18n.AppLocale
import app.fusion.wallet.ui.proof.ProofScreen
import app.fusion.wallet.ui.shell.FusionTopBar
import app.fusion.wallet.ui.splash.FusionSplashScreen
import app.fusion.wallet.ui.theme.FusionWalletTheme
import app.fusion.wallet.ui.treasury.TreasuryScreen
import kotlinx.coroutines.delay

/**
 * Root shell: Treasury (what you have) · Agent (what the AI does) · Proof (the evidence).
 *
 * The language switch re-resolves every string at runtime by handing Compose a locale-wrapped
 * Context, so switching English / 中文 never restarts the Activity or loses state.
 */
@Composable
fun FusionWalletApp(modifier: Modifier = Modifier) {
    val base = LocalContext.current
    var lang by rememberSaveable { mutableStateOf(AppLocale.saved(base)) }
    val localized = remember(lang, base) { AppLocale.wrap(base, lang) }

    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) {
        FusionWalletTheme {
            val vm: AccountantViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()

            // rememberSaveable so rotation / process death keeps the tab.
            var routeName by rememberSaveable { mutableStateOf(NavRoute.Treasury.name) }
            val route = NavRoute.valueOf(routeName)

            // Splash has a floor so it never flashes for one frame; it also waits for the first data read.
            var splashElapsed by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SPLASH_MIN_MS)
                splashElapsed = true
            }
            val splashVisible = !splashElapsed || state.loading

            // Back returns to Treasury first; from Treasury the system takes over.
            BackHandler(enabled = route != NavRoute.Treasury) { routeName = NavRoute.Treasury.name }

            Scaffold(
                modifier = modifier.fillMaxSize(),
                topBar = {
                    FusionTopBar(
                        lang = lang,
                        onLang = { code -> lang = code; AppLocale.save(base, code) },
                        demoData = state.demoData,
                    )
                },
                bottomBar = { FusionBottomBar(selected = route, onSelect = { routeName = it.name }) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    Crossfade(targetState = route, animationSpec = tween(CROSSFADE_MS), label = "destination") { target ->
                        when (target) {
                            NavRoute.Treasury -> TreasuryScreen()
                            NavRoute.Agent -> AgentScreen()
                            NavRoute.Proof -> ProofScreen()
                        }
                    }
                }
            }

            FusionSplashScreen(visible = splashVisible)
        }
    }
}

private const val CROSSFADE_MS = 140

/** Shortest the cold-start splash stays up; the real condition is the first data read finishing. */
private const val SPLASH_MIN_MS = 900L
