package com.coinportracker.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.coinportracker.core.Decimals
import com.coinportracker.core.Fmt
import com.coinportracker.data.HubState
import com.coinportracker.domain.Currency
import com.coinportracker.ui.theme.LocalReducedMotion
import java.math.BigDecimal

fun HubState.goalProgress(): Pair<BigDecimal, Double>? {
    val target = settings.goal ?: return null
    // meta em US$ não depende do câmbio (antes sumia sem câmbio quando a exibição estava em R$)
    val fx = fx?.toDouble() ?: if (settings.goalCurrency == Currency.USD) 1.0 else return null
    val valueCur = when (settings.goalCurrency) {
        Currency.USD -> BigDecimal.valueOf(agg.totalUsd)
        Currency.BRL -> if (currency == Currency.BRL) conv(agg.totalUsd) ?: return null else BigDecimal.valueOf(agg.totalUsd * fx)
    }
    val pct = Decimals.pct(valueCur, target)?.toDouble() ?: return null
    return target to (pct / 100.0)
}

@Composable
fun GoalRing(h: HubState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val g = h.goalProgress() ?: return
    val frac = g.second.toFloat().coerceIn(0f, 1f)
    val reduced = LocalReducedMotion.current
    val anim by animateFloatAsState(if (reduced) frac else frac, com.coinportracker.ui.theme.Motion.spatial(), label = "goal")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val fill = MaterialTheme.colorScheme.onSurface
    Box(modifier.size(44.dp).clip(CircleShape).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 3.5.dp.toPx()
            val inset = sw / 2
            val arc = Size(size.width - sw, size.height - sw)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
            if (anim > 0f) drawArc(fill, -90f, 360f * anim, false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("${(g.second * 100).toInt().coerceIn(0, 100)}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun GoalDialog(h: HubState, onDismiss: () -> Unit, onEdit: () -> Unit) {
    val g = h.goalProgress()
    val target = h.settings.goal
    val cur = h.settings.goalCurrency
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meta") },
        text = {
            Column {
                if (target != null && g != null) {
                    Text("Alvo ${Fmt.money(target, cur, h.hide)}")
                    Spacer(Modifier.height(6.dp))
                    val pct = (g.second * 100)
                    Text("${Fmt.num(pct, 1)}% do objetivo")
                } else Text("Defina um valor alvo em Ajustes.")
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("Ajustes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
