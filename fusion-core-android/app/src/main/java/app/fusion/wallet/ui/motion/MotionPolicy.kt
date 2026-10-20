package app.fusion.wallet.ui.motion

/** Keep delay-based reveals under the same system switch as Compose animations. */
object MotionPolicy {
    fun enabled(animatorScale: Float): Boolean = animatorScale.isFinite() && animatorScale > 0f
    fun revealDelayMillis(index: Int, enabled: Boolean): Long = if (enabled) index.coerceAtLeast(0) * 60L else 0L
}
