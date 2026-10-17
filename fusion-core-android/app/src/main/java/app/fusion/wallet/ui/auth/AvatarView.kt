package app.fusion.wallet.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fusion.wallet.accountant.data.AvatarIcon

/**
 * Avatar palette. Every fill keeps white initials/glyphs at >= 4.5:1 contrast, and none relies on
 * colour alone (the glyph or initials carry identity).
 */
object AvatarPalette {
    val colors = listOf(
        Color(0xFF4F52D6), // indigo
        Color(0xFF0F766E), // teal
        Color(0xFFB45309), // amber
        Color(0xFFBE123C), // rose
        Color(0xFF0369A1), // sky
        Color(0xFF15803D), // green
    )

    fun color(index: Int): Color = colors[index.coerceIn(0, colors.lastIndex)]

    fun glyph(icon: AvatarIcon): ImageVector? = when (icon) {
        AvatarIcon.INITIALS -> null
        AvatarIcon.PERSON -> Icons.Filled.Person
        AvatarIcon.BRIEFCASE -> Icons.Filled.Work
        AvatarIcon.SHIELD -> Icons.Filled.Shield
        AvatarIcon.STAR -> Icons.Filled.Star
        AvatarIcon.ROCKET -> Icons.Filled.RocketLaunch
        AvatarIcon.LEAF -> Icons.Filled.Eco
    }

    /** "Lin Mei" -> "LM"; "陈伟" -> "陈伟"; single word -> its first letter. */
    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].codePoints().limit(2).toArray().joinToString("") { String(Character.toChars(it)) }.uppercase()
            else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
        }
    }
}

/** Decorative by default (the name is always next to it); pass a description where it is a button on its own. */
@Composable
fun AvatarView(name: String, color: Int, icon: AvatarIcon, size: Dp, modifier: Modifier = Modifier) {
    val glyph = AvatarPalette.glyph(icon)
    Box(
        modifier.size(size).clip(CircleShape).background(AvatarPalette.color(color)),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            Icon(glyph, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
        } else {
            Text(
                AvatarPalette.initials(name),
                color = Color.White,
                style = TextStyle(fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
