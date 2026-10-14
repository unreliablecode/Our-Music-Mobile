package com.github.musicyou.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import androidx.compose.material3.Scaffold
import com.github.musicyou.ui.glass.AdaptiveText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.models.Section
import com.github.musicyou.ui.glass.GlassIconButton
import com.github.musicyou.ui.glass.GlassSurface
import kotlinx.coroutines.launch

@Composable
fun TabScaffold(
    pagerState: PagerState,
    topIconButtonId: ImageVector,
    onTopIconButtonClick: () -> Unit,
    sectionTitle: String,
    appBarActions: @Composable (() -> Unit)? = null,
    tabColumnContent: List<Section>,
    content: @Composable (Int) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(bottomStart = 25.dp, bottomEnd = 25.dp),
                tint = Color.White.copy(alpha = 0.36f),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassIconButton(onClick = onTopIconButtonClick, tint = Color.White.copy(alpha = 0.2f)) {
                            Icon(
                                imageVector = if (topIconButtonId.name.endsWith("ArrowBack")) Icons.Rounded.ChevronLeft else topIconButtonId,
                                contentDescription = "Back",
                                tint = Color(0xFFFA2D48),
                                modifier = Modifier.size(27.dp),
                            )
                        }
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { appBarActions?.invoke() }
                    }
                    if (sectionTitle.isNotBlank()) {
                        Text(
                            text = sectionTitle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = Color(0xFF202023),
                            fontSize = 31.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 1.dp, bottom = 16.dp),
                        )
                    }
                    if (tabColumnContent.size > 1) AppleDetailTabs(pagerState, tabColumnContent)
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            content(it)
        }
    }
}

@Composable
private fun AppleDetailTabs(pagerState: PagerState, sections: List<Section>) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 19.dp).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        sections.forEachIndexed { index, section ->
            val selected = pagerState.currentPage == index
            GlassSurface(
                modifier = Modifier
                    .semantics { this.selected = selected }
                    .clickable(role = Role.Tab) { scope.launch { pagerState.animateScrollToPage(index) } },
                shape = RoundedCornerShape(50),
                tint = if (selected) Color(0xFFFA2D48).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.12f),
            ) {
                Text(
                    section.title,
                    color = if (selected) Color(0xFFFA2D48) else Color(0xFF6E6E73),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
        }
    }
}
