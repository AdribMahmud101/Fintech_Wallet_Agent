package app.fusion.wallet.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.components.FusionIcon
import app.fusion.wallet.ui.components.FusionIconRes
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.RuleRow
import app.fusion.wallet.ui.components.VelocityMeter
import app.fusion.wallet.ui.model.PreFlightReport
import app.fusion.wallet.ui.shorten
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian

/**
 * `layout.md` View 2 — "Obsidian Institutional" Pre-Flight Guardrail Card.
 *
 * Every value here is rendered from the [PreFlightReport] the engine produced. Nothing in
 * this composable recomputes a limit: the card is a view of the engine's verdict, which is
 * the only way it cannot drift from what actually gates the transaction.
 */
@Composable
fun PreFlightGuardrailCard(
    report: PreFlightReport,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaces = FusionTheme.surfaces

    ObsidianCard(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FusionIcon(
                resId = FusionIconRes.ShieldBlock,
                contentDescription = null,
                size = 16.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.preflight_header),
                style = MaterialTheme.typography.labelSmall,
                color = Obsidian.StatusSuccess,
            )
        }

        Spacer(Modifier.height(10.dp))

        // Intent recap: what the agent believes it is about to do.
        Column(Modifier.padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = report.intent.action.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accessible.TextMuted,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = report.intent.amountUsd,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = report.intent.token,
                    style = MaterialTheme.typography.titleMedium,
                    color = Obsidian.AccentAiViolet,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "→ " + app.fusion.wallet.ui.shorten(report.intent.destination),
                style = MonoStyle,
                color = Accessible.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(surfaces.border))
        Spacer(Modifier.height(6.dp))

        Column(Modifier.padding(horizontal = 14.dp)) {
            report.rules.forEach { rule ->
                RuleRow(rule.label, rule.detail, rule.state)
            }
        }

        Spacer(Modifier.height(4.dp))
        VelocityMeter(
            spentUsd = report.dailySpentUsd + amountOf(report),
            capUsd = report.dailyCapUsd,
            modifier = Modifier.padding(horizontal = 14.dp),
        )

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(FusionDims.radiusSmall),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accessible.ButtonFill,
                    contentColor = Obsidian.TextPrimary,
                ),
            ) {
                Text(stringResource(R.string.preflight_confirm), style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(FusionDims.radiusSmall),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = androidx.compose.foundation.BorderStroke(
                    FusionDims.hairline,
                    surfaces.borderFocused,
                ),
            ) {
                Text(stringResource(R.string.preflight_cancel), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Parses the display string back out of the report for the projected meter. */
private fun amountOf(report: PreFlightReport): Double =
    report.intent.amountUsd.removePrefix("$").toDoubleOrNull() ?: 0.0

/** `layout.md` View 1, Parsing state — pulsing violet chip while MNN works. */
@Composable
fun ParsingIndicator(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "mnn")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        modifier = modifier
            .border(
                FusionDims.hairline,
                Obsidian.AccentAiViolet.copy(alpha = 0.45f),
                RoundedCornerShape(FusionDims.radiusSmall),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .alpha(pulse)
                .background(Obsidian.AccentAiViolet, RoundedCornerShape(FusionDims.radiusSmall)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Obsidian.AccentAiViolet,
        )
    }
}