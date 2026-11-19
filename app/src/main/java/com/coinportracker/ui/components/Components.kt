package com.coinportracker.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coinportracker.portfolio.Explanation
import com.coinportracker.ui.theme.LocalExtra
import java.math.BigDecimal

@Composable
fun PremiumCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val x = LocalExtra.current
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(x.cardTop, x.cardBottom)))
            .border(BorderStroke(1.dp, x.cardBorder), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp))
    }
}

@Composable
fun gainColor(v: BigDecimal?): Color = when {
    v == null -> MaterialTheme.colorScheme.onSurfaceVariant
    // mesmo arredondamento da exibição (2 casas): "0,00%" fica cinza, nunca verde/vermelho
    v.setScale(2, java.math.RoundingMode.HALF_EVEN).signum() > 0 -> LocalExtra.current.gain
    v.setScale(2, java.math.RoundingMode.HALF_EVEN).signum() < 0 -> LocalExtra.current.loss
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Número com transição de "rolagem" por caractere: muda só o dígito alterado,
 * sem recarregar a tela. O texto final é sempre o valor exato formatado.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    var previous by remember { mutableStateOf(text) }
    val up = remember(text) { compareNumeric(text, previous) >= 0 }
    SideEffect { previous = text }
    Row(modifier) {
        // Alinhado à direita: chave estável por posição a partir do fim.
        val chars = text.toList()
        chars.forEachIndexed { i, ch ->
            key(chars.size - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val dir = if (up) 1 else -1
                        (slideInVertically(tween(260)) { h -> h * dir } + fadeIn(tween(200))) togetherWith (slideOutVertically(tween(260)) { h -> -h * dir } + fadeOut(tween(160)))
                    }, label = "digit",
                ) { c -> Text(c.toString(), style = style, color = color) }
            }
        }
    }
}

private fun compareNumeric(a: String, b: String): Int {
    fun n(s: String) = s.filter { it.isDigit() || it == ',' || it == '-' }.replace(',', '.').toBigDecimalOrNull()
    val x = n(a); val y = n(b)
    return if (x == null || y == null) 0 else x.compareTo(y)
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
fun MetricRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface, sub: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick) else Modifier).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle)
        }
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.SansSerif), color = valueColor, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        if (onClick != null) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Info, "Detalhar cálculo", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)) }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, onClick: (() -> Unit)? = null) {
    Column(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Placeholder com brilho (sem spinners). */
@Composable
fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(
        Brush.linearGradient(listOf(base, base.copy(alpha = 0.35f).compositeOver(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)), base),
            start = Offset(x * 600f, 0f), end = Offset(x * 600f + 400f, 200f))))
}


@Composable
fun SkeletonList(rows: Int = 6) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Shimmer(Modifier.size(36.dp).clip(CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Shimmer(Modifier.fillMaxWidth(0.5f).height(12.dp)); Spacer(Modifier.height(6.dp)); Shimmer(Modifier.fillMaxWidth(0.3f).height(10.dp)) }
                Shimmer(Modifier.width(70.dp).height(14.dp))
            }
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), Color.Transparent))))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(14.dp)); FilledTonalButton(onClick = onAction) { Text(action) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, fab: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = fab,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

@Composable
fun <T> ChipSelector(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                .border(1.dp, if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                .clickable { onSelect(o) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(label(o), style = MaterialTheme.typography.labelLarge, color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T?, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, allowNone: String? = null, onNone: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let(text) ?: (allowNone ?: ""), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true,
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone != null) DropdownMenuItem(text = { Text(allowNone) }, onClick = { onNone(); open = false })
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Composable
fun DecimalField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, supporting: String? = null, isError: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = { s -> onChange(s.filter { it.isDigit() || it == ',' || it == '.' || it == '-' }) }, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = modifier.fillMaxWidth(),
        supportingText = supporting?.let { { Text(it) } }, isError = isError,
    )
}

@Composable
fun TextFieldX(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, supporting: String? = null, isError: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, modifier = modifier.fillMaxWidth(), supportingText = supporting?.let { { Text(it) } }, isError = isError)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplainSheet(explanation: Explanation?, onDismiss: () -> Unit, onOpenTx: (Long) -> Unit = {}) {
    if (explanation == null) return
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(explanation.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("Fórmula: ${explanation.formula}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(explanation.lines.size) { i ->
                    val l = explanation.lines[i]
                    Row(Modifier.fillMaxWidth().then(if (l.txId != null && l.txId > 0) Modifier.clickable { onOpenTx(l.txId) } else Modifier).padding(vertical = 5.dp)) {
                        Text(l.label, style = if (l.emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                            color = if (l.emphasis) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(l.value, style = if (l.emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall, fontWeight = if (l.emphasis) FontWeight.SemiBold else FontWeight.Normal)
                    }
                    if (l.emphasis) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            explanation.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Confirmar", destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
fun AssetAvatar(symbol: String, color: Color, size: androidx.compose.ui.unit.Dp = 38.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.12f)))).border(1.dp, color.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
        Text(symbol.take(3).uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
    }
}

fun colorFor(key: String): Color = com.coinportracker.ui.theme.Palette.chart[(key.hashCode() and 0x7fffffff) % com.coinportracker.ui.theme.Palette.chart.size]

@Composable
fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle, modifier = modifier)
}
