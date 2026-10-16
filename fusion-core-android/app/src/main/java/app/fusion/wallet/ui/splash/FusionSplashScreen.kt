package app.fusion.wallet.ui.splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.components.FusionIconRes
import app.fusion.wallet.ui.theme.FusionWalletTheme
import app.fusion.wallet.ui.theme.Obsidian

/**
 * Cold-start brand screen: the launcher mark over the wordmark.
 *
 * Android's own splash cannot carry type -- the platform exposes a background colour and
 * an animated icon, and no branding-image slot -- so the "Fusion Wallet" line has to be
 * drawn in Compose. It hands off to [app.fusion.wallet.ui.FusionWalletApp] on an opacity
 * fade rather than a hard swap, because the system splash is still on screen when this
 * first composes and a cut would read as a flicker.
 *
 * [visible] is owned by the caller so the decision to dismiss stays next to the state
 * that justifies it.
 */
@Composable
fun FusionSplashScreen(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    // The platform splash is still on screen when this first composes, so the hand-off
    // is a fade rather than a swap -- a cut here reads as a flicker, not a transition.
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(SPLASH_FADE_MS),
        label = "splash",
    )

    // Once the fade completes, leave the tree. The background is opaque and sits above
    // the whole app, so keeping the node around at alpha 0 would not reveal anything --
    // it would just be an invisible full-screen surface left covering the UI.
    if (alpha <= 0f) return

    Box(
        modifier = modifier
            .fillMaxSize()
            // On the Box, not the content: fading only the content left an opaque
            // canvas parked over the app and the app never appeared.
            .alpha(alpha)
            .background(Obsidian.CanvasBackground),
        contentAlignment = Alignment.Center,
    ) {
        // The mark sits alone in the centred slot and the type is hung beneath it as a
        // sibling rather than the next child of the same column. In one column the text
        // would be part of what the parent centres, which pulls the mark *up* by half the
        // text block, so the mark would jump the moment the type appeared. Centring the
        // mark on its own and offsetting the type by a known amount keeps the mark exactly
        // where Android 12+ already drew it.
        SplashMark()

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = SPLASH_MARK_BOX_DP.dp / 2f + SPLASH_TEXT_GAP_DP.dp)
                .semantics { heading() },
        )
    }
}

/**
 * The launcher's adaptive-icon canvas, which is the same 288dp box Android 12+ renders the
 * system splash icon into. Sizing this screen's mark with it is what makes the hand-off
 * invisible: the artwork occupies the same fraction of the same box on both sides.
 */
private const val SPLASH_MARK_BOX_DP = 288f

/** Gap between the bottom of the mark's box and the cap height of the wordmark. */
private const val SPLASH_TEXT_GAP_DP = 22f

/** Matches the page-transition timing so the splash does not feel slower than the app. */
private const val SPLASH_FADE_MS = 160

/**
 * The launcher artwork, drawn on the same canvas the adaptive icon and the system splash
 * use so the three never disagree by a shade.
 *
 * Sizing note: this is the *foreground layer*, which process_graphics.py fits to 85% of the
 * adaptive-icon mask. Rendered at [SPLASH_MARK_BOX_DP] the shield itself therefore lands at
 * about 163dp, which is what the platform splash shows -- matching it is the whole point.
 */
@Composable
private fun SplashMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(FusionIconRes.LauncherForeground),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(SPLASH_MARK_BOX_DP.dp),
    )
}

@Preview(name = "Splash", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun FusionSplashScreenPreview() {
    FusionWalletTheme { FusionSplashScreen(visible = true) }
}