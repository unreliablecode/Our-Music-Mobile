package com.github.musicyou.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import com.github.musicyou.ui.glass.AdaptiveText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.R
import com.github.musicyou.enums.SettingsSection
import com.github.musicyou.ui.components.ValueSelectorDialog
import com.github.musicyou.ui.glass.BackgroundEditor
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.ui.glass.GlassToggle
import com.github.musicyou.ui.glass.rememberBackgroundSettings
import com.github.musicyou.ui.styling.Dimensions

@Composable
fun SettingsScreen(
    pop: () -> Unit,
    onGoToSettingsPage: (Int) -> Unit,
    onGoToUpdates: () -> Unit = {},
) {
    val playerPadding = LocalPlayerPadding.current
    val background = rememberBackgroundSettings()
    var showBackgroundEditor by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { SettingsHeader(stringResource(R.string.settings), pop, "Back") },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = playerPadding + 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlassSurface(
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(25.dp),
                    tint = Color.White.copy(alpha = 0.5f),
                ) {
                    SettingsNavigationRow(
                        title = "Background & Glass",
                        icon = Icons.Rounded.Wallpaper,
                        subtitle = "Your photo, your position, your atmosphere",
                        onClick = { showBackgroundEditor = true },
                    )
                }
                Spacer(Modifier.height(10.dp))
                GlassSurface(
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(25.dp),
                    tint = Color.White.copy(alpha = 0.5f),
                ) {
                    SettingsNavigationRow(
                        title = "Updates & Changelog",
                        icon = Icons.Rounded.SystemUpdate,
                        subtitle = "Check releases and see what's new",
                        onClick = onGoToUpdates,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "MUSIC",
                    color = Color(0xFF77777D),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(start = 15.dp, bottom = 8.dp),
                )
                GlassSurface(
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(25.dp),
                    tint = Color.White.copy(alpha = 0.5f),
                ) {
                    Column {
                        SettingsSection.entries.forEachIndexed { index, section ->
                            SettingsNavigationRow(
                                title = stringResource(section.resourceId),
                                icon = section.icon,
                                onClick = { onGoToSettingsPage(index) },
                            )
                            if (index < SettingsSection.entries.lastIndex) {
                                HorizontalDivider(Modifier.padding(start = 61.dp, end = 15.dp), color = Color.Black.copy(alpha = 0.07f))
                            }
                        }
                    }
                }
            }
        }
        if (showBackgroundEditor) {
            Dialog(
                onDismissRequest = { showBackgroundEditor = false },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                BackgroundEditor(background, onDismiss = { showBackgroundEditor = false })
            }
        }
    }
}

@Composable
internal fun SettingsHeader(title: String, pop: () -> Unit, backLabel: String = "Settings") {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp)) {
        TextButton(onClick = pop, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = null, tint = Color(0xFFFA2D48), modifier = Modifier.size(27.dp))
            Text(backLabel, color = Color(0xFFFA2D48), fontSize = 17.sp)
        }
        Text(title, color = Color(0xFF202023), fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 5.dp, top = 3.dp, bottom = 13.dp))
    }
}

@Composable
private fun SettingsNavigationRow(title: String, icon: ImageVector, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = if (subtitle == null) 16.dp else 19.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp).background(Color(0xFFFA2D48).copy(alpha = 0.11f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFFFA2D48), modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFF202023), fontSize = 17.sp, fontWeight = if (subtitle == null) FontWeight.Normal else FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Color(0xFF737378), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color(0xFF96969B), modifier = Modifier.size(21.dp))
    }
}

@Composable
inline fun <reified T : Enum<T>> EnumValueSelectorSettingsEntry(
    title: String,
    selectedValue: T,
    crossinline onValueSelected: (T) -> Unit,
    icon: ImageVector,
    isEnabled: Boolean = true,
    crossinline valueText: @Composable (T) -> String,
    noinline trailingContent: @Composable (() -> Unit)? = null
) {
    ValueSelectorSettingsEntry(
        title = title,
        selectedValue = selectedValue,
        values = enumValues<T>().toList(),
        onValueSelected = onValueSelected,
        icon = icon,
        isEnabled = isEnabled,
        valueText = valueText,
        trailingContent = trailingContent,
    )
}

@Composable
inline fun <T> ValueSelectorSettingsEntry(
    title: String,
    selectedValue: T,
    values: List<T>,
    crossinline onValueSelected: (T) -> Unit,
    icon: ImageVector,
    isEnabled: Boolean = true,
    crossinline valueText: @Composable (T) -> String = { it.toString() },
    noinline trailingContent: @Composable (() -> Unit)? = null
) {
    var isShowingDialog by remember { mutableStateOf(false) }

    if (isShowingDialog) {
        ValueSelectorDialog(
            onDismiss = { isShowingDialog = false },
            title = title,
            selectedValue = selectedValue,
            values = values,
            onValueSelected = onValueSelected,
            valueText = valueText
        )
    }

    SettingsEntry(
        title = title,
        text = valueText(selectedValue),
        icon = icon,
        onClick = { isShowingDialog = true },
        isEnabled = isEnabled,
        trailingContent = trailingContent
    )
}

@Composable
fun SwitchSettingEntry(
    title: String,
    text: String,
    icon: ImageVector,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isEnabled: Boolean = true
) {
    SettingsEntry(
        title = title,
        text = text,
        icon = icon,
        onClick = { onCheckedChange(!isChecked) },
        isEnabled = isEnabled
    ) {
        GlassToggle(
            checked = isChecked,
            onCheckedChange = { if (isEnabled) onCheckedChange(it) },
            modifier = Modifier.semantics { if (!isEnabled) disabled() },
        )
    }
}

@Composable
fun SettingsEntry(
    title: String,
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    isEnabled: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).alpha(if (isEnabled) 1f else Dimensions.lowOpacity),
        shape = RoundedCornerShape(20.dp),
        tint = Color.White.copy(alpha = 0.46f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = isEnabled, onClick = onClick).padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFFFA2D48), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFF202023), fontSize = 16.sp)
                if (text.isNotBlank()) Text(text, color = Color(0xFF737378), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.width(9.dp))
            if (trailingContent != null) trailingContent()
            else Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color(0xFF96969B), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun SettingsInformation(
    text: String,
) {
    Row(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = Color(0xFF8B8B91),
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = text,
            color = Color(0xFF737378),
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
fun SettingsProgress(
    text: String,
    progress: Float,
    actionButton: @Composable () -> Unit = {}
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        tint = Color.White.copy(alpha = 0.46f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text, color = Color(0xFF202023), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("${(progress * 100).toInt()}%", color = Color(0xFF737378), fontSize = 12.sp)
                }
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                    color = Color(0xFFFA2D48),
                    trackColor = Color.Black.copy(alpha = 0.08f),
                )
            }
            actionButton()
        }
    }
}
