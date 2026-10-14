package com.github.musicyou.ui.apple

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.database
import com.github.musicyou.models.Lyrics
import com.github.musicyou.ui.components.TextFieldDialog
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import com.github.musicyou.ui.glass.AdaptiveText as Text
import com.github.musicyou.ui.glass.GlassIconButton
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.utils.LyricsFetchResult
import com.github.musicyou.utils.fetchAndSaveLyrics
import com.github.musicyou.utils.parseLrc
import com.github.musicyou.utils.mutableStatePreferenceOf
import com.github.musicyou.utils.preferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlin.math.absoluteValue

@Composable
fun FollowLyrics(media: MediaItem, modifier: Modifier = Modifier) {
    val player = LocalPlayerServiceBinder.current?.player ?: return
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    val stored by remember(media.mediaId) { database.lyrics(media.mediaId) }.collectAsState(initial = null)
    val timeline = remember(stored?.synced) { parseLrc(stored?.synced) }
    var delayMs by remember(media.mediaId) {
        val preferenceKey = "lyrics_delay_${media.mediaId}"
        mutableStatePreferenceOf(context.preferences.getInt(preferenceKey, 0)) {
            context.preferences.edit().putInt(preferenceKey, it).apply()
        }
    }
    var position by remember(media.mediaId) { mutableLongStateOf(player.currentPosition) }
    var retry by remember(media.mediaId) { mutableIntStateOf(0) }
    var fetching by remember(media.mediaId) { mutableStateOf(true) }
    var outcome by remember(media.mediaId) { mutableStateOf<LyricsFetchResult?>(null) }
    var showDelay by remember(media.mediaId) { mutableStateOf(false) }
    var editing by remember(media.mediaId) { mutableStateOf(false) }
    val list = rememberLazyListState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    var lineIndex by remember(media.mediaId) { mutableIntStateOf(-1) }

    LaunchedEffect(media.mediaId) { list.scrollToItem(0) }

    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(player, media.mediaId) {
        while (isActive) {
            position = player.currentPosition
            delay(100)
        }
    }
    LaunchedEffect(media.mediaId, retry) {
        fetching = true
        var durationWaits = 0
        while (player.duration <= 0 && durationWaits++ < 20) {
            delay(100)
        }
        try {
            outcome = fetchAndSaveLyrics(media, player.duration, force = retry > 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            outcome = LyricsFetchResult(stored, networkError = true)
        } finally {
            fetching = false
        }
    }
    val activeIndex = timeline.currentIndex(position, delayMs.toLong())
    LaunchedEffect(activeIndex, timeline, viewportHeight) {
        lineIndex = activeIndex
        if (activeIndex >= 0 && viewportHeight > 0) {
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
            val offset = -viewportHeight / 3 + (item?.size ?: 70) / 2
            list.animateScrollToItem(activeIndex, offset)
        }
    }

    if (editing) {
        TextFieldDialog(
            title = if (timeline.lines.isNotEmpty()) "Edit timed lyrics" else "Edit lyrics",
            hintText = if (timeline.lines.isNotEmpty()) "[00:00.00]Words" else "Words",
            initialTextInput = stored?.synced?.takeIf { timeline.lines.isNotEmpty() } ?: stored?.fixed.orEmpty(),
            singleLine = false,
            maxLines = 12,
            isTextInputValid = { true },
            onDismiss = { editing = false },
            onDone = { text ->
                database.query {
                    database.insert(media)
                    database.upsert(
                        Lyrics(media.mediaId, fixed = if (timeline.lines.isEmpty()) text else stored?.fixed,
                            synced = if (timeline.lines.isNotEmpty()) text else stored?.synced)
                    )
                }
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lyrics", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        timeline.lines.isNotEmpty() -> "Following playback · Tap a line to seek"
                        !stored?.fixed.isNullOrBlank() -> "Timing isn't available for these lyrics"
                        fetching -> "Finding lyrics…"
                        outcome?.instrumental == true -> "Instrumental track"
                        outcome?.networkError == true -> "Can't connect. Saved lyrics stay available."
                        else -> "Lyrics aren't available for this track"
                    },
                    fontSize = 11.sp,
                    color = MusicSecondary,
                )
            }
            if (fetching && stored == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else GlassIconButton(onClick = { retry++ }, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Refresh, "Fetch lyrics again", Modifier.size(18.dp)) }
            GlassIconButton(onClick = { editing = true }, modifier = Modifier.padding(start = 6.dp).size(38.dp)) { Icon(Icons.Rounded.Edit, "Edit lyrics", Modifier.size(18.dp)) }
        }

        if (timeline.lines.isNotEmpty()) {
            LazyColumn(
                state = list,
                userScrollEnabled = false,
                contentPadding = PaddingValues(top = 20.dp, bottom = 200.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportHeight = it.height },
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                itemsIndexed(timeline.lines, key = { index, line -> "${line.timeMs}_$index" }) { index, line ->
                    val active = index == lineIndex
                    val scale by animateFloatAsState(if (active) 1f else 0.94f, label = "lyric emphasis")
                    Text(
                        text = line.text.ifBlank { "♪" },
                        fontSize = if (active) 30.sp else 27.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        lineHeight = 37.sp,
                        color = if (active) Color.Unspecified else MusicSecondary,
                        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                            .clickable { player.seekTo(timeline.seekPosition(index, delayMs.toLong())) }.padding(vertical = 4.dp),
                    )
                }
            }
            GlassSurface(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { showDelay = !showDelay }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, "Lyrics timing", Modifier.size(17.dp))
                        Text("  Lyrics delay", Modifier.weight(1f), fontSize = 13.sp)
                        Text(formatLyricsDelay(delayMs), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (showDelay) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            GlassIconButton(onClick = { delayMs = (delayMs - 250).coerceAtLeast(-10_000) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Remove, "Show lyrics 0.25 seconds earlier", Modifier.size(17.dp)) }
                            Text("Positive delay shows words later", fontSize = 10.sp)
                            GlassIconButton(onClick = { delayMs = (delayMs + 250).coerceAtMost(10_000) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Add, "Show lyrics 0.25 seconds later", Modifier.size(17.dp)) }
                        }
                        Text("Reset", modifier = Modifier.align(Alignment.CenterHorizontally).clickable { delayMs = 0 }.padding(7.dp), fontSize = 12.sp, color = MusicRed)
                    }
                }
            }
        } else if (!stored?.fixed.isNullOrBlank()) {
            Text(stored?.fixed.orEmpty(), fontSize = 24.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp))
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (fetching) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                else Text(if (outcome?.instrumental == true) "♪" else "Try again when you're connected.", fontSize = if (outcome?.instrumental == true) 56.sp else 15.sp, color = MusicSecondary)
            }
        }
    }
}

private fun formatLyricsDelay(delayMs: Int): String =
    if (delayMs == 0) "0.00 s" else String.format(java.util.Locale.US, "%s%.2f s", if (delayMs > 0) "+" else "−", delayMs.absoluteValue / 1000f)
