package com.github.musicyou.ui.apple

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil3.compose.AsyncImage
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.database
import com.github.musicyou.models.LocalMenuState
import com.github.musicyou.ui.components.BaseMediaItemMenu
import com.github.musicyou.ui.components.NonQueuedMediaItemMenu
import com.github.musicyou.ui.components.MenuEntry
import com.github.musicyou.ui.screens.player.SleepTimer
import com.github.musicyou.ui.glass.*
import com.github.musicyou.ui.glass.AdaptiveText as Text
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import com.github.musicyou.ui.navigation.Navigation
import com.github.musicyou.ui.navigation.Routes
import com.github.musicyou.ui.visualization.AudioVisualization
import com.github.musicyou.ui.visualization.VisualizationMode
import com.github.musicyou.ui.export.VideoExportDialog
import com.github.musicyou.update.rememberUpdateCoordinator
import com.github.musicyou.utils.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

val LocalMusicNavController = staticCompositionLocalOf<NavHostController?> { null }

@Composable
fun rememberCurrentMedia(): MediaItem? {
    val player = LocalPlayerServiceBinder.current?.player
    var item by remember(player) { mutableStateOf(player?.currentMediaItem, neverEqualPolicy()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { item = mediaItem }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    return item
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleMusicShell(navController: NavHostController, intentAction: String?, expandFromNotification: Boolean = false) {
    val background = rememberBackgroundSettings()
    val photoContrast = rememberWallpaperContrast(background.value)
    val readability = rememberWallpaperReadability(photoContrast)
    val updates = rememberUpdateCoordinator()
    val updateState by updates.state.collectAsState()
    var dismissedUpdate by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(updates) { updates.checkAutomatically() }
    val backdrop = rememberLayerBackdrop()
    val wallpaperBackdrop = rememberLayerBackdrop()
    val playerBackdrop = rememberLayerBackdrop()
    val expandedBackdrop = rememberCombinedBackdrop(backdrop, playerBackdrop)
    val media = rememberCurrentMedia()
    val display = rememberPlayerDisplaySettings()
    val visibleReadability = if (display.value.ambient && media != null) {
        remember(readability.palette.primary) { wallpaperReadability(listOf(Color.Black, Color.White), readability.palette.primary) }
    } else readability
    val menu = LocalMenuState.current
    var expanded by rememberSaveable { mutableStateOf(expandFromNotification) }
    var notificationOpened by rememberSaveable { mutableStateOf(false) }
    var editingBackground by rememberSaveable { mutableStateOf(false) }
    val legacySheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val hideTabs = destination?.hasRoute<Routes.Settings>() == true || destination?.hasRoute<Routes.SettingsPage>() == true || destination?.hasRoute<Routes.Updates>() == true
    val playerPadding = if(hideTabs) 28.dp else if(media == null) 106.dp else 180.dp
    val playerVisibility = remember { MutableTransitionState(false) }
    playerVisibility.targetState = expanded && media != null
    val playerModalPresent = playerVisibility.currentState || playerVisibility.targetState || !playerVisibility.isIdle
    val pageBlocked = playerModalPresent || menu.isDisplayed || editingBackground
    val playerExpanded = expanded && media != null
    val pageAlpha by animateFloatAsState(
        targetValue = if (playerExpanded) 0f else 1f,
        animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing),
        label = "player page fade",
    )
    val fallbackArtworkUri = media?.mediaMetadata?.artworkUri?.toString()
        ?.takeIf { background.value.imageUri == null }
    val artworkAlpha by animateFloatAsState(
        targetValue = if (playerExpanded && fallbackArtworkUri != null) 1f else 0f,
        animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing),
        label = "player artwork background fade",
    )
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(pageBlocked) {
        if (pageBlocked) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    CompositionLocalProvider(LocalBackdrop provides backdrop,
        LocalMusicNavController provides navController,
        LocalWallpaperContrast provides photoContrast,
        LocalGlassStyle provides GlassStyle(background.glassEnabled, background.glassTint, background.glassBlur),
        LocalPlayerPadding provides playerPadding) {
        ProvideWallpaperReadability(visibleReadability) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    Box(Modifier.fillMaxSize().layerBackdrop(wallpaperBackdrop)) {
                        AppBackground(
                            background,
                            Modifier.fillMaxSize(),
                            fallbackArtworkUri = fallbackArtworkUri,
                            fallbackArtworkAlpha = artworkAlpha,
                        )
                        if (display.value.ambient) media?.let { LiveAmbientBackdrop(it, Modifier.fillMaxSize()) }
                        if (visibleReadability.overlay.alpha > 0f) Box(Modifier.fillMaxSize().background(visibleReadability.overlay))
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha }.modalUnderlay(pageBlocked).statusBarsPadding()) {
                        CompositionLocalProvider(LocalBackdrop provides wallpaperBackdrop) {
                            Navigation(navController, legacySheetState, intentAction)
                        }
                    }
                }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha }.modalUnderlay(pageBlocked)) {
                    CompositionLocalProvider(LocalBackdrop provides wallpaperBackdrop) {
                        if(!hideTabs) {
                            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 680.dp).navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if(media != null) AppleMiniPlayer(media, { expanded = true })
                                AppleTabBar(navController)
                            }
                        }
                        if(hideTabs) {
                            GlassIconButton(onClick = { editingBackground = true }, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(18.dp).size(52.dp)) {
                                Icon(Icons.Outlined.Wallpaper, "Customize background", tint = MusicRed)
                            }
                        }
                        if(updateState.hasAvailableUpdate && updateState.release?.versionName != dismissedUpdate && destination?.hasRoute<Routes.Updates>() != true && !pageBlocked) {
                            GlassSurface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).widthIn(max = 580.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Our Music ${updateState.release?.versionName}", fontWeight = FontWeight.SemiBold)
                                        Text("A new version is available", fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { navController.navigate(Routes.Updates) }) { Text("View", color = MusicRed) }
                                    IconButton(onClick = { dismissedUpdate = updateState.release?.versionName.orEmpty() }) { Icon(Icons.Outlined.Close, "Dismiss update") }
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxSize().layerBackdrop(playerBackdrop).modalUnderlay(menu.isDisplayed || editingBackground)) {
                    ModalOverlay(active = playerModalPresent, onDismiss = { expanded = false }, paneTitle = "Now playing") {
                        AnimatedVisibility(visibleState = playerVisibility, enter = fadeIn(), exit = fadeOut()) {
                            media?.let { AppleFullPlayer(it, { expanded = false }) }
                        }
                    }
                }
                ModalOverlay(active = menu.isDisplayed, onDismiss = menu::hide, paneTitle = "Song options",
                    modifier = Modifier.modalUnderlay(editingBackground)) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(.15f)).clickable { menu.hide() })
                    CompositionLocalProvider(LocalBackdrop provides if(expanded) expandedBackdrop else backdrop) {
                        GlassSurface(Modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp).fillMaxWidth().navigationBarsPadding().padding(12.dp).heightIn(max = 650.dp), shape = RoundedCornerShape(28.dp)) {
                            Column {
                                Box(Modifier.padding(top = 12.dp, bottom = 6.dp).size(34.dp, 4.dp).align(Alignment.CenterHorizontally).clip(CircleShape).background(MusicSecondary.copy(.45f)))
                                menu.content()
                            }
                        }
                    }
                }
                ModalOverlay(active = editingBackground, onDismiss = { editingBackground = false }, paneTitle = "Customize background") {
                    BackgroundEditor(background) { editingBackground = false }
                }
            }
        }
    }
    LaunchedEffect(media?.mediaId, expandFromNotification) {
        // Opening a song keeps the page visible; the mini-player expands on tap.
        if(media == null) expanded = false
        else if(expandFromNotification && !notificationOpened) {
            expanded = true
            notificationOpened = true
        }
    }
}

@Composable
private fun AppleTabBar(navController: NavHostController) {
    val entry by navController.currentBackStackEntryAsState()
    val dest = entry?.destination
    val routes = listOf(Routes.Home, Routes.New, Routes.Radio, Routes.Library)
    val labels = listOf("Home", "New", "Radio", "Library")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.GridView, Icons.Outlined.Radio, Icons.Outlined.LibraryMusic)
    var lastSelectedTab by rememberSaveable { mutableIntStateOf(0) }
    val librarySelected = dest?.hasRoute<Routes.Library>() == true || dest?.hasRoute<Routes.Songs>() == true || dest?.hasRoute<Routes.Artists>() == true || dest?.hasRoute<Routes.Albums>() == true || dest?.hasRoute<Routes.Playlists>() == true || dest?.hasRoute<Routes.Downloads>() == true || dest?.hasRoute<Routes.BuiltInPlaylist>() == true || dest?.hasRoute<Routes.LocalPlaylist>() == true
    val rootTab = if(librarySelected) 3 else routes.indexOfFirst { dest?.hasRoute(it::class) == true }
    LaunchedEffect(rootTab) { if(rootTab >= 0) lastSelectedTab = rootTab }
    fun navigate(route: Any) {
        navController.navigate(route) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val selectedIndex = if(dest?.hasRoute<Routes.Search>() == true) -1 else rootTab.takeIf { it >= 0 } ?: lastSelectedTab
        AnimatedGlassTabs(
            tabs = labels.mapIndexed { index, label -> GlassTab(label, icons[index]) },
            selectedIndex = selectedIndex,
            onSelect = { navigate(routes[it]) },
            modifier = Modifier.weight(1f),
            accentColor = MusicRed
        )
        GlassIconButton(onClick = { navigate(Routes.Search) }, modifier = Modifier.size(70.dp), tint = if(dest?.hasRoute<Routes.Search>() == true) MusicRed.copy(.12f) else Color.Unspecified) {
            Icon(Icons.Outlined.Search, "Search", Modifier.size(28.dp), tint = if(dest?.hasRoute<Routes.Search>() == true) MusicRed else MusicInk)
        }
    }
}

@Composable
fun AppleMiniPlayer(media: MediaItem, open: () -> Unit) {
    val player = LocalPlayerServiceBinder.current?.player ?: return
    var playing by remember(player) { mutableStateOf(player.shouldBePlaying) }
    player.DisposableListener { object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { playing = player.shouldBePlaying }
    } }
    GlassSurface(Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxSize().glassClickable(onClick = open).padding(start = 10.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(media.mediaMetadata.artworkUri, null, Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(media.mediaMetadata.title?.toString().orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(media.mediaMetadata.artist?.toString().orEmpty(), color = MusicSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GlassIconButton(onClick = { if(playing) player.pause() else { if(player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() } }) { Icon(if(playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if(playing) "Pause" else "Play", Modifier.size(29.dp)) }
            GlassIconButton(onClick = { player.forceSeekToNext() }) { Icon(Icons.Filled.SkipNext, "Next song", Modifier.size(28.dp)) }
        }
    }
}

@Composable
private fun AppleFullPlayer(media: MediaItem, close: () -> Unit) {
    val display = rememberPlayerDisplaySettings()
    if (display.value.car) {
        CarPlayer(media, display, close)
        return
    }
    val binder = LocalPlayerServiceBinder.current ?: return
    val player = binder.player
    val menu = LocalMenuState.current
    val context = LocalContext.current
    var playing by remember { mutableStateOf(player.shouldBePlaying) }
    var repeat by remember { mutableIntStateOf(player.repeatMode) }
    var shuffled by remember { mutableStateOf(player.shuffleModeEnabled) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(playbackErrorMessage(player.playerError)) }
    var queue by remember { mutableStateOf(player.currentTimeline.mediaItems) }
    var queueIndex by remember { mutableIntStateOf(player.currentMediaItemIndex) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var exportItem by remember { mutableStateOf<MediaItem?>(null) }
    var exportDurationMs by remember { mutableLongStateOf(0L) }
    val visualization = display.value.visualization
    val presenting = exportItem == null
    val frame = rememberAudioVisualization(visualization != VisualizationMode.OFF && page == 0 && presenting, retainWhenInactive = true)
    val sleepTimerLeft by (binder.sleepTimerMillisLeft ?: flowOf(null)).collectAsState(null)
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume) }
    val liked by remember(media.mediaId) { database.likedAt(media.mediaId) }.collectAsState(null)
    val toggleLiked: () -> Unit = { database.query { database.insert(media); database.like(media.mediaId, if (liked == null) System.currentTimeMillis() else null) } }
    player.DisposableListener { object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            playing = player.shouldBePlaying; repeat = player.repeatMode; shuffled = player.shuffleModeEnabled
            error = playbackErrorMessage(player.playerError); queue = player.currentTimeline.mediaItems; queueIndex = player.currentMediaItemIndex
        }
    } }
    GlassSurface(Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val shortScreen = maxHeight < 560.dp
            val compact = landscape || shortScreen
            val horizontalPadding = if (landscape) 16.dp else if (shortScreen) 20.dp else 30.dp
            Column(Modifier.widthIn(max = if (landscape) 960.dp else 680.dp).fillMaxHeight().fillMaxWidth()
                .statusBarsPadding().navigationBarsPadding().align(Alignment.Center).padding(horizontal = horizontalPadding)) {
                Row(Modifier.fillMaxWidth().padding(top = if (compact) 2.dp else 10.dp).pointerInput(Unit) {
                    detectVerticalDragGestures { change, amount -> change.consume(); if (amount > 15f) close() }
                }, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Outlined.KeyboardArrowDown, "Close player", Modifier.size(32.dp)) }
                    Text("NOW PLAYING", Modifier.weight(1f), fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, color = MusicSecondary)
                    GlassIconButton(onClick = { menu.display {
                        NonQueuedMediaItemMenu(mediaItem = media, onDismiss = menu::hide)
                        MenuEntry(Icons.Outlined.Schedule, "Sleep Timer", onClick = { menu.hide(); showSleepTimer = true })
                        MenuEntry(Icons.Outlined.Tune, "Player options", onClick = { menu.display { PlayerDisplayOptions(display, menu::hide) } })
                        MenuEntry(Icons.Outlined.Movie, "Export music video", onClick = {
                            menu.hide()
                            exportDurationMs = player.duration.coerceAtLeast(0L)
                            exportItem = media
                        })
                    } }, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.MoreHoriz, "Playback options") }
                }
                if (landscape) {
                    Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerPageContent(page, media, queue, queueIndex, player, visualization, frame, playing && error == null,
                            compact = true, modifier = Modifier.weight(1.15f).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                            TrackInfo(media, liked, onLike = toggleLiked, compact = true)
                            PlaybackProgress(media.mediaId, player, presenting)
                            error?.let { Text(it, Modifier.padding(top = 2.dp), color = MusicRed, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            TransportControls(player, playing, error, compact = true, modifier = Modifier.padding(vertical = 3.dp))
                            VolumeControl(volume, { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * maxVolume).toInt(), 0) }, compact = true)
                        }
                    }
                    PlayerPageActions(page, shuffled, repeat,
                        onLyrics = { page = if (page == 1) 0 else 1 },
                        onShuffle = { player.shuffleModeEnabled = !shuffled },
                        onRepeat = { player.repeatMode = (repeat + 1) % 3 },
                        onQueue = { page = if (page == 2) 0 else 2 },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                } else {
                    Spacer(Modifier.height(if (shortScreen) 6.dp else 18.dp))
                    PlayerPageContent(page, media, queue, queueIndex, player, visualization, frame, playing && error == null,
                        compact = shortScreen, modifier = Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(if (shortScreen) 8.dp else 22.dp))
                    TrackInfo(media, liked, onLike = toggleLiked, compact = shortScreen)
                    PlaybackProgress(media.mediaId, player, presenting)
                    error?.let { Text(it, Modifier.padding(top = if (shortScreen) 2.dp else 8.dp), color = MusicRed,
                        fontSize = if (shortScreen) 10.sp else 12.sp, maxLines = if (shortScreen) 1 else 2, overflow = TextOverflow.Ellipsis) }
                    TransportControls(player, playing, error, compact = shortScreen,
                        modifier = Modifier.padding(vertical = if (shortScreen) 4.dp else 14.dp))
                    VolumeControl(volume, { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * maxVolume).toInt(), 0) }, compact = shortScreen)
                    PlayerPageActions(page, shuffled, repeat,
                        onLyrics = { page = if (page == 1) 0 else 1 },
                        onShuffle = { player.shuffleModeEnabled = !shuffled },
                        onRepeat = { player.repeatMode = (repeat + 1) % 3 },
                        onQueue = { page = if (page == 2) 0 else 2 },
                        modifier = Modifier.fillMaxWidth().padding(bottom = if (shortScreen) 2.dp else 14.dp, top = if (shortScreen) 2.dp else 6.dp))
                }
            }
        }
    }
    if(showSleepTimer) SleepTimer(sleepTimerLeft) { showSleepTimer = false }
    exportItem?.let { VideoExportDialog(it, exportDurationMs) { exportItem = null } }
}

@Composable
private fun PlayerPageContent(
    page: Int,
    media: MediaItem,
    queue: List<MediaItem>,
    queueIndex: Int,
    player: Player,
    visualization: VisualizationMode,
    frame: State<com.github.musicyou.audio.visualization.AudioVisualizationFrame?>,
    playing: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    when (page) {
        1 -> FollowLyrics(media, modifier)
        2 -> LazyColumn(modifier) {
            item { Text("Playing Next", Modifier.padding(vertical = if (compact) 8.dp else 16.dp), fontSize = if (compact) 20.sp else 25.sp, fontWeight = FontWeight.Bold) }
            itemsIndexed(queue) { index, song ->
                Row(Modifier.fillMaxWidth().clickable { player.seekToDefaultPosition(index); player.play() }.padding(vertical = if (compact) 5.dp else 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(song.mediaMetadata.artworkUri, null, Modifier.size(if (compact) 36.dp else 44.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(song.mediaMetadata.title?.toString().orEmpty(), color = if (index == queueIndex) MusicRed else MusicInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.mediaMetadata.artist?.toString().orEmpty(), color = MusicSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        else -> if (visualization == VisualizationMode.SPHERE) {
            AudioVisualization({ frame.value }, visualization, playing, modifier)
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val edge = minOf(maxWidth, maxHeight)
                    AsyncImage(media.mediaMetadata.artworkUri.thumbnail(1200), "Album artwork", Modifier.size(edge).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                }
                if (visualization != VisualizationMode.OFF) AudioVisualization(
                    { frame.value }, visualization, playing,
                    Modifier.fillMaxWidth().height(if (compact) 76.dp else if (visualization == VisualizationMode.BOTH || visualization == VisualizationMode.OSCILLOSCOPE) 180.dp else 150.dp),
                )
            }
        }
    }
}

@Composable
private fun TrackInfo(media: MediaItem, likedAt: Long?, onLike: () -> Unit, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(media.mediaMetadata.title?.toString().orEmpty(), fontSize = if (compact) 17.sp else 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(media.mediaMetadata.artist?.toString().orEmpty(), fontSize = if (compact) 13.sp else 19.sp, color = MusicSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlassIconButton(onClick = onLike, modifier = Modifier.size(if (compact) 38.dp else 44.dp)) {
            Icon(if (likedAt == null) Icons.Outlined.StarBorder else Icons.Filled.Star, "Favorite", tint = if (likedAt == null) MusicInk else MusicRed)
        }
    }
}

@Composable
private fun TransportControls(player: Player, playing: Boolean, error: String?, compact: Boolean, modifier: Modifier = Modifier) {
    val previousSize = if (compact) 46.dp else 60.dp
    val playSize = if (compact) 56.dp else 72.dp
    val iconSize = if (compact) 32.dp else 42.dp
    Row(modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(onClick = { player.forceSeekToPrevious() }, modifier = Modifier.size(previousSize)) { Icon(Icons.Filled.SkipPrevious, "Previous song", Modifier.size(iconSize)) }
        GlassIconButton(onClick = { if (playing && error == null) player.pause() else { if (error != null || player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() } }, modifier = Modifier.size(playSize)) {
            Icon(if (playing && error == null) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing && error == null) "Pause" else "Play", Modifier.size(if (compact) 44.dp else 58.dp))
        }
        GlassIconButton(onClick = { player.forceSeekToNext() }, modifier = Modifier.size(previousSize)) { Icon(Icons.Filled.SkipNext, "Next song", Modifier.size(iconSize)) }
    }
}

@Composable
private fun VolumeControl(value: Float, onChange: (Float) -> Unit, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.VolumeDown, null, Modifier.size(if (compact) 18.dp else 20.dp), tint = MusicSecondary)
        GlassSlider(value = value, onValueChange = onChange, modifier = Modifier.weight(1f), contentDescription = "Volume", activeTrackColor = adaptiveContentColor(MusicSecondary))
        Icon(Icons.Outlined.VolumeUp, null, Modifier.size(if (compact) 18.dp else 20.dp), tint = MusicSecondary)
    }
}

@Composable
private fun PlayerPageActions(
    page: Int,
    shuffled: Boolean,
    repeat: Int,
    onLyrics: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        GlassIconButton(onClick = onLyrics, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.FormatQuote, "Lyrics", tint = if (page == 1) MusicRed else MusicInk) }
        GlassIconButton(onClick = onShuffle, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.Shuffle, "Shuffle", tint = if (shuffled) MusicRed else MusicInk) }
        GlassIconButton(onClick = onRepeat, modifier = Modifier.size(44.dp)) { Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, "Repeat", tint = if (repeat != Player.REPEAT_MODE_OFF) MusicRed else MusicInk) }
        GlassIconButton(onClick = onQueue, modifier = Modifier.size(44.dp)) { Icon(Icons.AutoMirrored.Outlined.QueueMusic, "Playing next", tint = if (page == 2) MusicRed else MusicInk) }
    }
}

/** Playback time changes only recompose this control instead of the live visualization. */
@Composable
private fun PlaybackProgress(mediaId: String, player: Player, active: Boolean) {
    var seek by remember(mediaId) { mutableStateOf<Float?>(null) }
    val position by player.positionAndDurationState(active)
    val duration = position.second.takeIf { it > 0 } ?: 1L
    GlassSlider(
        value = seek ?: (position.first.toFloat() / duration).coerceIn(0f, 1f),
        onValueChange = { seek = it },
        onValueChangeFinished = { seek?.let { player.seekTo((it * duration).toLong()) }; seek = null },
        onValueChangeCanceled = { seek = null },
        enabled = position.second > 0,
        contentDescription = "Playback position",
        activeTrackColor = adaptiveContentColor(MusicSecondary),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(position.first), fontSize = 11.sp, color = MusicSecondary)
        Text("−${formatTime((duration - position.first).coerceAtLeast(0))}", fontSize = 11.sp, color = MusicSecondary)
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun playbackErrorMessage(failure: Throwable?): String? {
    if (failure == null) return null
    val causes = generateSequence(failure) { it.cause }.take(12).toList()
    val access = causes.filterIsInstance<com.github.innertube.requests.StreamAccessException>().firstOrNull()
    if (access != null) return access.message
    val providerReason = causes.filterIsInstance<androidx.media3.common.PlaybackException>()
        .firstOrNull { it.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_REMOTE_ERROR }?.message
    if (!providerReason.isNullOrBlank()) return providerReason
    val response = causes.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return when (response?.responseCode) {
        401, 403 -> "The music service rejected this audio link. Tap play to request a fresh link."
        410 -> "This audio link has expired. Tap play to request a fresh link."
        429 -> "The music service is receiving too many requests. Try again in a few minutes."
        else -> "Playback could not start. Check your connection and tap play to retry."
    }
}
