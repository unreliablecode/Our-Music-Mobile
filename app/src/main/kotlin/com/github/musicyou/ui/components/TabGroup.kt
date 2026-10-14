package com.github.musicyou.ui.components

import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import com.github.musicyou.ui.glass.AdaptiveText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.github.musicyou.models.Section
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabGroup(
    pagerState: PagerState,
    content: List<Section>
) {
    val scope = rememberCoroutineScope()

    val tabs: @Composable () -> Unit = {
        content.forEachIndexed { index, section ->
            LeadingIconTab(
                selected = index == pagerState.currentPage,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                },
                text = { Text(text = section.title) },
                icon = {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = section.title
                    )
                },
                unselectedContentColor = MaterialTheme.colorScheme.onBackground
            )
        }
    }

    if (pagerState.pageCount >= 3) {
        PrimaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            tabs = tabs,
            containerColor = androidx.compose.ui.graphics.Color.Transparent
        )
    } else {
        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            tabs = tabs,
            containerColor = androidx.compose.ui.graphics.Color.Transparent
        )
    }
}