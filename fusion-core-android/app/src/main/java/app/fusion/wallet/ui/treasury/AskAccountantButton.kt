package app.fusion.wallet.ui.treasury

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.theme.FusionType

/** The Accountant entry point: a small glowing "Ask" pill with a bot icon. The glow pulses unless system animations are off. */
@Composable
fun AskAccountantButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    val pulse by rememberInfiniteTransition(label = "askGlow").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "askGlowAlpha",
    )
    val glow = if (animate) pulse else 0.7f
    val violet = Color(0xFF8B5CF6)
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .shadow(elevation = 16.dp, shape = shape, ambientColor = violet.copy(alpha = glow), spotColor = violet.copy(alpha = glow))
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(violet, Color(0xFF6366F1))))
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
            .clickable(role = Role.Button, onClickLabel = "Ask the Accountant", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .semantics { contentDescription = "Ask the Accountant" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_nav_smart_toy), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text("Ask", style = FusionType.BodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
