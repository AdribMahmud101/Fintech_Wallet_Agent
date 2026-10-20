package app.fusion.wallet.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing

/** Legacy Home draw-in rhythm; Compose applies Android's animator duration scale. */
object FusionChartMotion {
    const val EnterMillis = 700
    val EnterEasing = FastOutSlowInEasing
}
