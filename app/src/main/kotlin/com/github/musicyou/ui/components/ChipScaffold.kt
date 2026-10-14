package com.github.musicyou.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.ui.apple.MusicRed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.github.musicyou.ui.glass.AdaptiveText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.musicyou.models.Section

@Composable
fun ChipScaffold(
    tabIndex: Int,
    onTabChanged: (Int) -> Unit,
    tabColumnContent: List<Section>,
    content: @Composable (AnimatedVisibilityScope.(Int) -> Unit)
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabColumnContent.forEachIndexed { index, section ->
                    GlassSurface(shape = RoundedCornerShape(50), tint = if(index == tabIndex) MusicRed.copy(alpha = 0.12f) else Color.Unspecified) {
                        Row(Modifier.clickable { onTabChanged(index) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(text = section.title, color = if(index == tabIndex) MusicRed else Color.Unspecified)
                        }
                    }

                }
            }
        }
    ) { paddingValues ->
        Surface(
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            AnimatedContent(
                targetState = tabIndex,
                transitionSpec = {
                    val slideDirection = when (targetState > initialState) {
                        true -> AnimatedContentTransitionScope.SlideDirection.Left
                        false -> AnimatedContentTransitionScope.SlideDirection.Right
                    }

                    slideIntoContainer(slideDirection) togetherWith slideOutOfContainer(
                        slideDirection
                    )
                },
                content = content,
                label = "chips"
            )
        }
    }
}
