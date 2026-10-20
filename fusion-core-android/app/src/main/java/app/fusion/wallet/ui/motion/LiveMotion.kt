package app.fusion.wallet.ui.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.math.BigInteger

val LocalMotionEnabled = staticCompositionLocalOf<Boolean?> { null }

@Composable
fun rememberMotionEnabled(): Boolean = LocalMotionEnabled.current ?: observeMotionEnabled()

/** Shared once by the app shell; standalone previews can observe the same setting directly. */
@Composable
fun observeMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = MotionPolicy.enabled(runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f))
    var enabled by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        enabled = read()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** One pulse per real event (new block or confirmed transaction), no free-running simulation. */
@Composable
fun eventPulse(key: Any?, enabled: Boolean = rememberMotionEnabled()): Float {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(key, enabled) {
        pulse.snapTo(0f)
        if (key != null && enabled) { pulse.snapTo(1f); pulse.animateTo(0f, tween(400)) }
    }
    return if (enabled) pulse.value else 0f
}

/** Initial data paints immediately; only subsequent real balance changes count between endpoints. */
@Composable
fun refreshAmount(target: BigInteger): BigInteger {
    val enabled = rememberMotionEnabled()
    var start by remember { mutableStateOf(target) }
    var end by remember { mutableStateOf(target) }
    val fraction = remember { Animatable(1f) }
    fun current() = start + ((end - start).toBigDecimal() * fraction.value.toBigDecimal()).toBigInteger()
    LaunchedEffect(target, enabled) {
        val displayed = current()
        start = displayed
        end = target
        if (!enabled || start == end) fraction.snapTo(1f)
        else { fraction.snapTo(0f); fraction.animateTo(1f, tween(400)) }
    }
    return if (enabled) current() else target
}
