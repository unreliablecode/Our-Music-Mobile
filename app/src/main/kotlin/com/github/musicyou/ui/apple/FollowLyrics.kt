package com.github.musicyou.ui.apple

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.Text as MaterialText
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
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.github.musicyou.ui.glass.glassClickable
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.ui.glass.LocalContrastPalette
import com.github.musicyou.utils.LyricsFetchResult
import com.github.musicyou.utils.fetchAndSaveLyrics
import com.github.musicyou.utils.parseLrc
import com.github.musicyou.utils.mutableStatePreferenceOf
import com.github.musicyou.utils.preferences
import com.github.musicyou.utils.LrcTimeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlin.math.absoluteValue

@Composable
fun FollowLyrics(media: MediaItem, modifier: Modifier = Modifier) {
    val player = LocalPlayerServiceBinder.current?.player ?: return
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    val focused = LocalWindowInfo.current.isWindowFocused
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
    val lyricsStyle = rememberPlayerDisplaySettings().value.lyricsStyle
    val follow = remember(media.mediaId) { LyricsFollowState() }

    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(player, media.mediaId, focused) {
        if (!focused) return@LaunchedEffect
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

    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 430.dp || maxWidth < 380.dp || LocalDensity.current.fontScale > 1.25f
        Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = if (compact) 2.dp else 7.dp, bottom = if (compact) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lyrics", fontSize = if (compact) 20.sp else 25.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        timeline.lines.isNotEmpty() && follow.browsing -> "Browsing lyrics · Tap a line to seek"
                        timeline.lines.isNotEmpty() -> "Following playback · Tap a line to seek"
                        !stored?.fixed.isNullOrBlank() -> "Timing isn't available for these lyrics"
                        fetching -> "Finding lyrics…"
                        outcome?.instrumental == true -> "Instrumental track"
                        outcome?.networkError == true -> "Can't connect. Saved lyrics stay available."
                        else -> "Lyrics aren't available for this track"
                    },
                    fontSize = if (compact) 10.sp else 11.sp,
                    color = MusicSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (follow.browsing) {
                androidx.compose.material3.TextButton(onClick = follow::resume) {
                    Text("Follow", color = MusicRed, fontSize = if (compact) 11.sp else 12.sp)
                }
            }
            if (fetching && stored == null) CircularProgressIndicator(Modifier.size(if (compact) 16.dp else 20.dp), strokeWidth = 2.dp)
            else GlassIconButton(onClick = { retry++ }, modifier = Modifier.size(if (compact) 34.dp else 38.dp)) { Icon(Icons.Rounded.Refresh, "Fetch lyrics again", Modifier.size(18.dp)) }
            GlassIconButton(onClick = { editing = true }, modifier = Modifier.padding(start = if (compact) 2.dp else 6.dp).size(if (compact) 34.dp else 38.dp)) { Icon(Icons.Rounded.Edit, "Edit lyrics", Modifier.size(18.dp)) }
        }

        if (timeline.lines.isNotEmpty()) {
            TimedLyricsList(
                timeline = timeline,
                activeIndex = activeIndex,
                style = lyricsStyle,
                follow = follow,
                onSeek = { index -> player.seekTo(timeline.seekPosition(index, delayMs.toLong())) },
                compact = compact,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            GlassSurface(Modifier.fillMaxWidth().padding(vertical = if (compact) 3.dp else 10.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 9.dp else 13.dp, vertical = if (compact) 4.dp else 8.dp)) {
                    Row(Modifier.fillMaxWidth().glassClickable { showDelay = !showDelay }.padding(vertical = if (compact) 2.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, "Lyrics timing", Modifier.size(if (compact) 15.dp else 17.dp))
                        Text("  Lyrics delay", Modifier.weight(1f), fontSize = if (compact) 12.sp else 13.sp)
                        Text(formatLyricsDelay(delayMs), fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (showDelay) {
                        Row(Modifier.fillMaxWidth().padding(top = if (compact) 2.dp else 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            GlassIconButton(onClick = { delayMs = (delayMs - 250).coerceAtLeast(-10_000) }, modifier = Modifier.size(if (compact) 30.dp else 34.dp)) { Icon(Icons.Rounded.Remove, "Show lyrics 0.25 seconds earlier", Modifier.size(17.dp)) }
                            Text("Positive delay shows words later", fontSize = if (compact) 9.sp else 10.sp, maxLines = 1)
                            GlassIconButton(onClick = { delayMs = (delayMs + 250).coerceAtMost(10_000) }, modifier = Modifier.size(if (compact) 30.dp else 34.dp)) { Icon(Icons.Rounded.Add, "Show lyrics 0.25 seconds later", Modifier.size(17.dp)) }
                        }
                        Text("Reset", modifier = Modifier.align(Alignment.CenterHorizontally).glassClickable { delayMs = 0 }.padding(if (compact) 4.dp else 7.dp), fontSize = if (compact) 11.sp else 12.sp, color = MusicRed)
                    }
                }
            }
        } else if (!stored?.fixed.isNullOrBlank()) {
            Text(stored?.fixed.orEmpty(), fontSize = if (compact) 19.sp else 24.sp, lineHeight = if (compact) 28.sp else 35.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = if (compact) 5.dp else 12.dp))
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (fetching) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                else Text(if (outcome?.instrumental == true) "♪" else "Try again when you're connected.", fontSize = if (outcome?.instrumental == true) 56.sp else if (compact) 13.sp else 15.sp, color = MusicSecondary)
            }
        }
        }
    }
}

@Stable
internal class LyricsFollowState {
    var browsing by mutableStateOf(false)
        private set

    fun browse() { browsing = true }
    fun resume() { browsing = false }
}

/** Timed cues retain their own layout while the emphasis moves between lines. */
@Composable
internal fun TimedLyricsList(
    timeline: LrcTimeline,
    activeIndex: Int,
    style: LyricsStyle,
    follow: LyricsFollowState,
    onSeek: (Int) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    list: LazyListState = rememberLazyListState(),
) {
    val density = LocalDensity.current
    val palette = LocalContrastPalette.current
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    val smooth = style == LyricsStyle.ANIMATED_SMOOTH
    val viewportDp = with(density) { viewportHeight.toDp() }

    LaunchedEffect(timeline) {
        follow.resume()
        list.scrollToItem(0)
    }
    LaunchedEffect(dragging, timeline) {
        if (dragging) follow.browse()
        else if (follow.browsing) {
            delay(6_000)
            follow.resume()
        }
    }
    LaunchedEffect(activeIndex, timeline, viewportHeight, style, follow.browsing) {
        if (activeIndex !in timeline.lines.indices || viewportHeight <= 0 || follow.browsing) return@LaunchedEffect
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
        if (smooth && item != null) {
            val distance = item.offset + item.size / 2f - viewportHeight / 3f
            if (distance.absoluteValue > 1f) {
                list.animateScrollBy(distance, tween(650, easing = FastOutSlowInEasing))
            }
        } else {
            val offset = -viewportHeight / 3 + (item?.size ?: with(density) { 45.dp.roundToPx() }) / 2
            list.animateScrollToItem(activeIndex, offset)
        }
    }

    LazyColumn(
        state = list,
        contentPadding = if (smooth) PaddingValues(
            top = maxOf(if (compact) 12.dp else 20.dp, viewportDp / 3 - if (compact) 12.dp else 20.dp),
            bottom = maxOf(if (compact) 72.dp else 200.dp, viewportDp * 2 / 3),
        ) else PaddingValues(top = if (compact) 12.dp else 20.dp, bottom = if (compact) 72.dp else 200.dp),
        modifier = modifier.testTag("timed-lyrics").onSizeChanged { viewportHeight = it.height },
        verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 13.dp),
    ) {
        itemsIndexed(timeline.lines, key = { index, line -> "${line.timeMs}_$index" }) { index, line ->
            val active = index == activeIndex
            val scale = animateFloatAsState(
                targetValue = if (active) 1f else if (smooth) 0.91f else 0.94f,
                animationSpec = spring(dampingRatio = 0.88f, stiffness = if (smooth) 155f else 400f),
                label = "lyric emphasis",
            )
            val lift = animateFloatAsState(
                targetValue = if (smooth && !active) with(density) { 3.dp.toPx() } else 0f,
                animationSpec = spring(dampingRatio = 1f, stiffness = 155f),
                label = "lyric lift",
            )
            val color by animateColorAsState(
                targetValue = if (active) palette.primary else palette.secondary,
                animationSpec = tween(if (smooth) 400 else 120),
                label = "lyric color",
            )
            MaterialText(
                text = line.text.ifBlank { "♪" },
                fontSize = if (compact) { if (smooth || active) 22.sp else 20.sp } else if (smooth || active) 30.sp else 27.sp,
                fontWeight = if (smooth) FontWeight.Bold else if (active) FontWeight.Bold else FontWeight.Medium,
                lineHeight = if (compact) 29.sp else 37.sp,
                color = color,
                modifier = Modifier.fillMaxWidth()
                    .testTag("lyric-line-$index")
                    .semantics { selected = active }
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationY = lift.value
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .clickable {
                        follow.resume()
                        onSeek(index)
                    }
                    .padding(vertical = if (compact) 2.dp else 4.dp),
            )
        }
    }
}

private fun formatLyricsDelay(delayMs: Int): String =
    if (delayMs == 0) "0.00 s" else String.format(java.util.Locale.US, "%s%.2f s", if (delayMs > 0) "+" else "−", delayMs.absoluteValue / 1000f)
