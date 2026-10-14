package com.github.musicyou.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import androidx.compose.material3.MaterialTheme
import com.github.musicyou.ui.glass.AdaptiveText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.enums.SortOrder
import com.github.musicyou.models.SortBy
import com.github.musicyou.ui.glass.GlassIconButton
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.ui.glass.LocalContrastOverlay

@Composable
fun <T : SortBy> SortingHeader(
    sortBy: T,
    changeSortBy: (T) -> Unit,
    sortByEntries: List<T>,
    sortOrder: SortOrder,
    toggleSortOrder: (SortOrder) -> Unit,
    size: Int,
    @PluralsRes itemCountText: Int
) {
    var isSorting by rememberSaveable { mutableStateOf(false) }
    val sortOrderIconRotation by animateFloatAsState(
        targetValue = if (sortOrder == SortOrder.Ascending) 0f else 180f,
        label = "rotation"
    )

    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TextButton(onClick = { isSorting = true }) {
                Text(stringResource(sortBy.text), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFA2D48))
            }
            DropdownMenu(
                expanded = isSorting,
                onDismissRequest = { isSorting = false },
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                shape = RoundedCornerShape(20.dp),
            ) {
                CompositionLocalProvider(LocalContrastOverlay provides Color.Transparent) {
                    GlassSurface(
                        modifier = Modifier.width(248.dp),
                        shape = RoundedCornerShape(20.dp),
                        tint = Color.White.copy(alpha = 0.86f),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            sortByEntries.forEachIndexed { index, entry ->
                                val checked = sortBy == entry
                                Row(
                                    modifier = Modifier.fillMaxWidth().semantics { selected = checked }.clickable(role = Role.RadioButton) {
                                        isSorting = false
                                        changeSortBy(entry)
                                    }.padding(horizontal = 17.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(entry.icon, null, Modifier.size(21.dp), tint = Color(0xFFFA2D48))
                                    Text(stringResource(entry.text), Modifier.weight(1f).padding(start = 12.dp), fontSize = 15.sp, fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal)
                                    if (checked) Icon(Icons.Rounded.Check, "Selected", Modifier.size(19.dp), tint = Color(0xFFFA2D48))
                                }
                                if (index < sortByEntries.lastIndex) HorizontalDivider(Modifier.padding(start = 51.dp, end = 17.dp), color = Color.Black.copy(alpha = 0.07f))
                            }
                        }
                    }
                }
            }
        }

        GlassIconButton(
            onClick = { toggleSortOrder(!sortOrder) },
            modifier = Modifier.size(34.dp).graphicsLayer { rotationZ = sortOrderIconRotation },
            tint = Color.White.copy(alpha = 0.18f),
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = if (sortOrder == SortOrder.Ascending) "Sort descending" else "Sort ascending",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.weight(1F))

        Text(
            text = pluralStringResource(id = itemCountText, count = size, size),
            style = MaterialTheme.typography.labelLarge
        )

    }
}
