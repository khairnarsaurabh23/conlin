package com.jobpilot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelShape = RoundedCornerShape(4.dp)
private val ButtonShape = RoundedCornerShape(4.dp)

/** A full-screen page: handles system bars and the keyboard in one place. */
@Composable
fun Page(
    snack: SnackbarHostState,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = bottomBar,
        contentWindowInsets = WindowInsets.systemBars,
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize(), content = content)
    }
}

/** Screen header written as a path: jobpilot/inbox. */
@Composable
fun Header(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 16.dp, end = 6.dp, top = 6.dp)
            .heightIn(min = 46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text("jobpilot/", fontFamily = Mono, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            title.lowercase().replace(' ', '-'), fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(PanelShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, PanelShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        content = content,
    )
}

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    if (primary) {
        Button(
            onClick = onClick, modifier = modifier.heightIn(min = 36.dp), enabled = enabled, shape = ButtonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface,
            ),
            contentPadding = pad,
        ) { Text(text, maxLines = 1) }
    } else {
        OutlinedButton(
            onClick = onClick, modifier = modifier.heightIn(min = 36.dp), enabled = enabled, shape = ButtonShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.4f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = pad,
        ) { Text(text, maxLines = 1) }
    }
}

/** Low-emphasis text action. */
@Composable
fun Quiet(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary) {
    TextButton(
        onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), shape = ButtonShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) { Text(text, maxLines = 1) }
}

/** Section heading written like a code comment, with an optional count on the right. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String = "") {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "// " + text.lowercase(), fontFamily = Mono, fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        if (trailing.isNotBlank()) Label(trailing)
    }
}

/** Small monospace label: card kinds, counts, timestamps. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines: Int = 1) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Bordered monospace tag, e.g. a status. */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.lowercase(), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** One line of evidence, marked the way a diff marks lines: + for, - against, ! caution. */
@Composable
fun Mark(sign: String, text: String, color: Color, modifier: Modifier = Modifier, maxLines: Int = 3) {
    Row(modifier.padding(vertical = 1.dp)) {
        Text(sign, fontFamily = Mono, fontSize = 13.sp, color = color, modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Drafted text shown as a quotation: a rule down the left side. */
@Composable
fun Quote(text: String, modifier: Modifier = Modifier, maxLines: Int = 4) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
fun ScoreText(score: Int) {
    Text("$score%", color = scoreColor(score), fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp)
}

/** Underlined text tabs. */
@Composable
fun <T> Seg(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Column(
                Modifier.clip(RoundedCornerShape(3.dp)).clickable { onSelect(o) }.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label(o).lowercase(), fontFamily = Mono, fontSize = 12.5.sp,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(20.dp).height(2.dp).background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
}

/** A thin usage bar with a caption, e.g. "Invites this week  12 of 60". */
@Composable
fun Meter(label: String, value: Int, max: Int, modifier: Modifier = Modifier) {
    val frac = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
    val full = value >= max
    Column(modifier) {
        Row {
            Label(label.lowercase(), Modifier.weight(1f))
            Label("$value/$max", color = if (full) warn else MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
            Box(
                Modifier.fillMaxWidth(frac).height(3.dp)
                    .background(if (full) warn else MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
fun Field(
    label: String, value: String, modifier: Modifier = Modifier, lines: Int = 1, secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = lines == 1, minLines = lines, shape = ButtonShape,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
fun NumField(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    // Not keyed on value: callers clamp the number, and re-keying would fight the user's typing.
    var text by remember { mutableStateOf(value.toString()) }
    Field(label, text, modifier, keyboard = KeyboardType.Number) { t ->
        text = t.filter(Char::isDigit).take(4)
        text.toIntOrNull()?.let(onChange)
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, hint: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint.isNotBlank()) Muted(hint)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.onSurface, checkedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant, uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
fun Dialog(
    title: String, onDismiss: () -> Unit, confirm: String, onConfirm: () -> Unit,
    confirmEnabled: Boolean = true, dismiss: String = "Cancel", onDismissButton: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = content,
        confirmButton = { Quiet(confirm, onConfirm, confirmEnabled, MaterialTheme.colorScheme.onSurface) },
        dismissButton = { Quiet(dismiss, onDismissButton ?: onDismiss) },
        shape = RoundedCornerShape(6.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    )
}

@Composable
fun EmptyNote(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content,
    )
}
