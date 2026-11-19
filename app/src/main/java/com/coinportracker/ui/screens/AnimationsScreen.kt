package com.coinportracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coinportracker.settings.AnimCurve
import com.coinportracker.settings.AnimPrefs
import com.coinportracker.settings.NumberAnim
import com.coinportracker.settings.ScreenAnim
import com.coinportracker.ui.components.CpCard
import com.coinportracker.ui.components.SlotText
import com.coinportracker.ui.theme.LocalAnim
import com.coinportracker.ui.theme.LocalExtra
import com.coinportracker.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

internal fun speedLabel(v: Float): String = if (v == 1f) "velocidade normal" else "velocidade " + speedText(v)

internal fun speedText(v: Float): String = (if (v % 1f == 0f) v.toInt().toString() else v.toString().replace('.', ',')) + "×"

/** Número de demonstração que muda sozinho (sobe e desce) enquanto a tela está aberta. */
@Composable
private fun rememberDemoValue(): Double {
    var v by remember { mutableDoubleStateOf(7927.03) }
    LaunchedEffect(Unit) {
        val steps = doubleArrayOf(3.41, -1.87, 12.06, -6.52, 0.94, -9.13, 4.28)
        var i = 0
        while (true) { delay(1800); v += steps[i++ % steps.size] }
    }
    return v
}

private fun demoText(v: Double) = "R$ " + com.coinportracker.core.Fmt.num(v, 2)

/** Ajustes → Animações: estilo dos números, curva, duração, transições, gráficos, listas, vibração e velocidade global. */
@Composable
fun AnimationsSection(vm: com.coinportracker.ui.UiModel) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val a = h.settings.anim
    val off = h.settings.lowPower || LocalReducedMotion.current
    fun set(f: (AnimPrefs) -> AnimPrefs) = vm.updateSettings { it.copy(anim = f(it.anim)) }
    val demo = rememberDemoValue()

    if (off) Text(if (h.settings.lowPower) "O modo de baixo consumo está ligado: as animações ficam desligadas até você desligá-lo em Atualização do saldo."
        else "As animações estão desligadas nas configurações do Android.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))

    // prévia principal (sempre com as escolhas atuais, mesmo no modo de baixo consumo, para você ver como fica)
    CompositionLocalProvider(LocalReducedMotion provides false, LocalAnim provides a) {
        CpCard(Modifier.padding(top = 12.dp)) {
            Text("Prévia", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            SlotText(demoText(demo), MaterialTheme.typography.displaySmall, spinOnFirst = -1)
        }
    }

    Group("Números") {
        NumberAnim.entries.forEach { n ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { set { it.copy(number = n) } }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioDot(a.number == n)
                Spacer(Modifier.width(12.dp))
                Text(n.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                CompositionLocalProvider(LocalReducedMotion provides false, LocalAnim provides a.copy(number = n)) {
                    Box(Modifier.widthIn(max = 130.dp)) { SlotText(demoText(demo), MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, spinOnFirst = -1) }
                }
            }
        }
        SliderRow("Duração", "${a.numberMs} ms", a.numberMs.toFloat(), AnimPrefs.MIN_MS.toFloat()..AnimPrefs.MAX_MS.toFloat(), enabled = a.number != NumberAnim.NONE) { v -> set { it.copy(numberMs = (v / 10).toInt() * 10) } }
        ChoiceRow("Curva", AnimCurve.entries, a.curve, { it.label }) { c -> set { it.copy(curve = c) } }
    }

    Group("Transição entre telas") {
        ChoiceRow(null, ScreenAnim.entries, a.screen, { it.label }) { v -> set { it.copy(screen = v) } }
        SpeedRow("Velocidade", a.screenSpeed, enabled = a.screen != ScreenAnim.NONE) { v -> set { it.copy(screenSpeed = v) } }
    }

    Group("Gráficos") {
        SwitchRow("Desenhar o gráfico", "A linha se desenha ao abrir e ao trocar o período", a.chart) { v -> set { it.copy(chart = v) } }
        if (a.chart) SpeedRow("Velocidade", a.chartSpeed) { v -> set { it.copy(chartSpeed = v) } }
    }

    Group("Listas e toque") {
        SwitchRow("Itens surgindo", "Cartões e linhas aparecem com um leve deslize", a.listAppear) { v -> set { it.copy(listAppear = v) } }
        SwitchRow("Vibração", "Ao percorrer o gráfico e ao ocultar os saldos", a.haptics) { v -> set { it.copy(haptics = v) } }
    }

    Group("Geral") {
        SpeedRow("Velocidade das animações", a.speed) { v -> set { it.copy(speed = v) } }
    }

    if (a != AnimPrefs()) TextButton(onClick = { set { AnimPrefs() } }, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Restaurar padrão") }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val c = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(20.dp).clip(CircleShape).background(if (selected) c else LocalExtra.current.cardHigh), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background))
    }
}

@Composable
private fun SliderRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onChange: (Float) -> Unit) {
    var local by remember(v) { mutableFloatStateOf(v) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (local == v) value else "${local.toInt() / 10 * 10} ms", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(local, { local = it }, enabled = enabled, valueRange = range, onValueChangeFinished = { onChange(local) },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface, activeTrackColor = MaterialTheme.colorScheme.onSurface, inactiveTrackColor = LocalExtra.current.cardHigh))
    }
}

@Composable
private fun SpeedRow(title: String, v: Float, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        val opts = AnimPrefs.SPEEDS
        val sel = opts.minByOrNull { kotlin.math.abs(it - v) } ?: 1f
        com.coinportracker.ui.components.Segments(opts, sel, { speedText(it) }, { if (enabled) onChange(it) }, Modifier.fillMaxWidth())
    }
}
