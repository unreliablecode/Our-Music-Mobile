package com.github.musicyou.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.musicyou.R
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import com.github.musicyou.ui.glass.AdaptiveText as Text
import com.github.musicyou.ui.glass.ContrastRegion
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.ui.glass.LocalContrastOverlay
import kotlinx.coroutines.delay

@PublishedApi
@Composable
internal fun GlassDialogShell(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalContrastOverlay provides Color.Transparent) {
            GlassSurface(
                modifier = modifier.widthIn(max = 420.dp).fillMaxWidth().padding(28.dp).imePadding(),
                shape = RoundedCornerShape(20.dp),
                tint = Color.White.copy(alpha = 0.86f),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 23.dp, bottom = 17.dp),
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()).padding(horizontal = 19.dp).padding(bottom = 18.dp),
                        content = content,
                    )
                    HorizontalDivider(color = Color.Black.copy(alpha = 0.09f))
                    actions()
                }
            }
        }
    }
}

@Composable
fun TextFieldDialog(
    title: String,
    hintText: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String = stringResource(id = R.string.cancel),
    doneText: String = stringResource(id = R.string.done),
    initialTextInput: String = "",
    singleLine: Boolean = true,
    maxLines: Int = 1,
    onCancel: () -> Unit = onDismiss,
    isTextInputValid: (String) -> Boolean = { it.isNotEmpty() },
) {
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by rememberSaveable(initialTextInput, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialTextInput, selection = TextRange(initialTextInput.length)))
    }
    val save = {
        if (isTextInputValid(textFieldValue.text)) {
            onDismiss()
            onDone(textFieldValue.text)
        }
    }
    GlassDialogShell(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
        actions = { DialogActions(cancelText, doneText, onCancel, save, isTextInputValid(textFieldValue.text)) },
    ) {
        ContrastRegion(overlay = Color.White.copy(alpha = 0.95f)) {
            TextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                singleLine = singleLine,
                maxLines = maxLines,
                shape = RoundedCornerShape(14.dp),
                textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF202023)),
                placeholder = { Text(hintText, fontSize = 15.sp, color = Color(0xFF85858B)) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.95f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.95f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Color(0xFFFA2D48),
                    focusedTextColor = Color(0xFF202023),
                    unfocusedTextColor = Color(0xFF202023),
                ),
                keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.None),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }
    LaunchedEffect(Unit) {
        delay(300)
        focusRequester.requestFocus()
    }
}

@Composable
fun ConfirmationDialog(
    title: String,
    text: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    cancelText: String = stringResource(id = R.string.cancel),
    confirmText: String = stringResource(id = R.string.confirm),
    onCancel: () -> Unit = onDismiss,
) {
    GlassDialogShell(
        title = title,
        onDismiss = onDismiss,
        actions = { DialogActions(cancelText, confirmText, onCancel, { onConfirm(); onDismiss() }) },
    ) {
        if (!text.isNullOrBlank()) Text(text, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DialogActions(cancelText: String, confirmText: String, onCancel: () -> Unit, onConfirm: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 13.dp)) {
            Text(cancelText, fontSize = 16.sp, color = Color(0xFFFA2D48))
        }
        TextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 13.dp)) {
            Text(confirmText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color(0xFFFA2D48) else Color(0xFF8C8C91))
        }
    }
}

@Composable
inline fun <T> ValueSelectorDialog(
    noinline onDismiss: () -> Unit,
    title: String,
    selectedValue: T,
    values: List<T>,
    crossinline onValueSelected: (T) -> Unit,
    crossinline valueText: @Composable (T) -> String = { it.toString() },
) {
    GlassDialogShell(
        title = title,
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 15.dp)) {
                Text(stringResource(R.string.cancel), fontSize = 16.sp, color = Color(0xFFFA2D48))
            }
        },
    ) {
        values.forEachIndexed { index, value ->
            val checked = selectedValue == value
            Row(
                modifier = Modifier.fillMaxWidth().semantics { selected = checked }.clickable(role = Role.RadioButton) {
                    onDismiss()
                    onValueSelected(value)
                }.padding(horizontal = 6.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(valueText(value), modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal)
                if (checked) Icon(Icons.Rounded.Check, "Selected", modifier = Modifier.size(22.dp), tint = Color(0xFFFA2D48))
            }
            if (index < values.lastIndex) HorizontalDivider(color = Color.Black.copy(alpha = 0.07f))
        }
    }
}
