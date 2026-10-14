package com.github.musicyou.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.github.musicyou.ui.glass.AdaptiveText as Text
import androidx.compose.ui.text.font.FontWeight
import com.github.musicyou.ui.apple.MusicRed
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.models.ActionInfo

@Composable
fun CoverScaffold(
    primaryButton: ActionInfo,
    secondaryButton: ActionInfo,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.widthIn(max = 400.dp)
    ) {
        content()
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(primaryButton, secondaryButton).filter { it.enabled }.forEach { action ->
                GlassSurface(Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxSize().clickable(onClick = action.onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(action.icon, null, tint = MusicRed, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(stringResource(action.description), color = MusicRed, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}
