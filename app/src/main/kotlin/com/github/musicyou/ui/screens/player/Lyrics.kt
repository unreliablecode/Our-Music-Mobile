package com.github.musicyou.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.ui.apple.FollowLyrics
import com.github.musicyou.ui.glass.AdaptiveIcon
import com.github.musicyou.ui.glass.GlassIconButton
import com.github.musicyou.ui.glass.GlassSurface

/** Existing player entry points share the same saved, timed lyrics view. */
@Composable
fun Lyrics(
    mediaId: String,
    isDisplayed: Boolean,
    onDismiss: () -> Unit,
    size: Dp,
    mediaMetadataProvider: () -> MediaMetadata,
    durationProvider: () -> Long,
    ensureSongInserted: () -> Unit,
    fullScreenLyrics: Boolean,
    toggleFullScreenLyrics: () -> Unit,
) {
    AnimatedVisibility(visible = isDisplayed, enter = fadeIn(), exit = fadeOut()) {
        val media = LocalPlayerServiceBinder.current?.player?.currentMediaItem
            ?.takeIf { it.mediaId == mediaId }
            ?: MediaItem.Builder().setMediaId(mediaId).setUri(mediaId).setMediaMetadata(mediaMetadataProvider()).build()
        GlassSurface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                FollowLyrics(media, Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp))
                GlassIconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    AdaptiveIcon(Icons.Rounded.Close, "Close lyrics")
                }
            }
        }
    }
}
