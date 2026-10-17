package app.fusion.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.ui.accountant.AccountantScreen
import app.fusion.wallet.ui.chat.ChatFeed
import app.fusion.wallet.ui.chat.EngineStrip
import app.fusion.wallet.ui.chat.PromptComposer
import app.fusion.wallet.ui.components.FusionBottomBar
import app.fusion.wallet.ui.components.NavRoute
import app.fusion.wallet.ui.components.TopHealthAppBar
import app.fusion.wallet.ui.ledger.LedgerScreen
import app.fusion.wallet.ui.settings.SettingsDrawer
import app.fusion.wallet.ui.splash.FusionSplashScreen
import app.fusion.wallet.ui.terminal.SystemLogTerminalSheet
import app.fusion.wallet.ui.theme.FusionWalletTheme
import app.fusion.wallet.ui.tokens.TokensScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root shell — `layout.md` §2.
 *
 * [ModalNavigationDrawer] wrapping a [Scaffold] whose top bar is the wallet health header and
 * whose bottom bar is the prompt composer, with the three destinations swapped in place
 * rather than pushed.
 *
 * State is entirely local to [MainViewModel]. Nothing here knows the engine is not connected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FusionWalletApp(modifier: Modifier = Modifier) {
    FusionWalletTheme {
        val viewModel: MainViewModel = viewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(DrawerValue.Closed)

        // rememberSaveable, not remember: rotation and process death were silently sending
        // the user back to Chat.
        var routeName by rememberSaveable { mutableStateOf(NavRoute.Chat.name) }
        val route = NavRoute.valueOf(routeName)
        fun goTo(target: NavRoute) { routeName = target.name }

        // The splash is dismissed on the app's own readiness, not on a bare timer, but the
        // demo state is produced synchronously so "ready" arrives on the first composition.
        // Without a floor on the duration the screen would therefore flash for a single
        // frame, which reads as a glitch rather than a splash -- hence MAX_SPLASH_MS.
        var splashElapsed by rememberSaveable { mutableStateOf(false) }
        val stateReady = state.wallet.address.isNotEmpty()
        val splashVisible = !splashElapsed || !stateReady
        LaunchedEffect(Unit) {
            delay(SPLASH_MIN_MS)
            splashElapsed = true
        }

        // The sheet owns its own visibility animation. Driving `terminalOpen` and letting
        // AnimatedVisibility wrap the sheet (as this used to) removed the sheet from
        // composition mid-dismissal, which dropped both its container fill and its scrim --
        // the log rendered straight through onto the chat feed behind it.
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        LaunchedEffect(state.terminalOpen) {
            if (state.terminalOpen) sheetState.show() else sheetState.hide()
        }

        // Back is a stack, not an exit: sheet, then drawer, then back to Chat, and only then
        // does the system take over and close the app. Previously back on Ledger or with the
        // drawer open dismissed the whole app.
        BackHandler(
            enabled = state.terminalOpen || drawerState.isOpen || route != NavRoute.Chat,
        ) {
            when {
                state.terminalOpen -> viewModel.setTerminalOpen(false)
                drawerState.isOpen -> scope.launch { drawerState.close() }
                else -> goTo(NavRoute.Chat)
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                SettingsDrawer(
                    rpcUrl = state.rpcUrl,
                    dailyCapUsd = state.dailyCapUsd,
                    mnnThreads = state.mnnThreads,
                    whitelist = state.whitelist,
                    onDailyCapChange = viewModel::setDailyCap,
                    onThreadsChange = viewModel::setMnnThreads,
                    onToggleAsset = viewModel::toggleWhitelist,
                    onDismiss = { scope.launch { drawerState.close() } },
                )
            },
            modifier = modifier.fillMaxSize(),
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopHealthAppBar(
                        wallet = state.wallet,
                        engine = state.engine,
                        onOpenSettings = { scope.launch { drawerState.open() } },
                        onOpenTerminal = { viewModel.setTerminalOpen(true) },
                    )
                },
                bottomBar = {
                    Column {
                        // The composer only exists on Chat, so fading it avoids the
                        // composer snapping in and out on every tab change.
                        AnimatedVisibility(
                            visible = route == NavRoute.Chat,
                            enter = fadeIn(tween(COMPOSER_ENTER_MS)),
                            exit = fadeOut(tween(COMPOSER_EXIT_MS)),
                        ) {
                            Column {
                                EngineStrip(
                                    mnnModel = state.engine.mnnModel,
                                    latencyMs = state.engine.mnnLatencyMs,
                                    rustOk = state.engine.rustGuardrailOk,
                                )
                                PromptComposer(onSubmit = viewModel::submit)
                            }
                        }
                        FusionBottomBar(selected = route, onSelect = ::goTo)
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    DestinationHost(route, state, viewModel)
                }
            }
        }

        SystemLogTerminalSheet(
            sheetState = sheetState,
            visible = state.terminalOpen,
            logs = state.logs,
            onClear = viewModel::clearLogs,
            onDismiss = { viewModel.setTerminalOpen(false) },
        )

        // Drawn last so it sits above the sheet: a splash that a bottom sheet could cover
        // would only be a splash until the user tapped something.
        FusionSplashScreen(visible = splashVisible)
    }
}

/**
 * Tab-switch timing. Measured on the 393dp device rather than guessed: at 220/180 the
 * switch felt like it was waiting on something, and at 2400 (a slow-motion build used to
 * photograph the transition) it was obviously broken. 160ms in / 130ms out is under
 * Android's default 300ms activity transition and reads as immediate without cutting the
 * slide abruptly. The composer fades slightly faster still -- it is secondary chrome.
 */
private const val ENTER_MS = 160
private const val EXIT_MS = 130
private const val COMPOSER_ENTER_MS = 140
private const val COMPOSER_EXIT_MS = 100

/**
 * Shortest the cold-start splash is allowed to stay up.
 *
 * A brand moment has to last long enough to be read, and the system splash that precedes
 * it is itself only held for a few hundred milliseconds. Anything past roughly a second
 * and users start reading it as the app being slow, so this is a floor, not a target:
 * the real dismissal condition is the wallet state arriving.
 */
private const val SPLASH_MIN_MS = 900L

/**
 * The three destinations, with a direction-aware horizontal transition.
 *
 * Forward and backward swaps slide opposite ways, so the bar reads as a position in a
 * sequence rather than three unrelated screens. The offsets are deliberately shallow (1/6
 * of the width) — a full-width slide on a dense layout like this reads as a page turn and
 * costs the user their place.
 */
@Composable
private fun DestinationHost(
    route: NavRoute,
    state: FusionUiState,
    viewModel: MainViewModel,
) {
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            val sign = if (forward) 1 else -1
            (slideInHorizontally(tween(ENTER_MS)) { width -> sign * width / 6 } + fadeIn(tween(ENTER_MS)))
                .togetherWith(
                    slideOutHorizontally(tween(EXIT_MS)) { width -> -sign * width / 6 } +
                        fadeOut(tween(EXIT_MS))
                )
                .using(SizeTransform(clip = false))
        },
        label = "destination",
    ) { target ->
        when (target) {
            NavRoute.Chat -> ChatFeed(
                messages = state.messages,
                suggestions = DemoData.suggestions,
                onSuggestion = viewModel::useSuggestion,
                onConfirm = viewModel::confirmPending,
                onCancel = viewModel::cancelPending,
            )

            NavRoute.Accountant -> AccountantScreen()
            NavRoute.Tokens -> TokensScreen(wallet = state.wallet)
            NavRoute.Ledger -> LedgerScreen(
                wallet = state.wallet,
                dailyCapUsd = state.dailyCapUsd.toDouble(),
            )
        }
    }
}

private operator fun NavRoute.compareTo(other: NavRoute): Int = ordinal - other.ordinal