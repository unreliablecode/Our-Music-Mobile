package com.github.musicyou.ui.apple

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.github.innertube.Innertube
import com.github.innertube.requests.searchPage
import com.github.innertube.requests.relatedPage
import com.github.innertube.utils.from
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.database
import com.github.musicyou.enums.SongSortBy
import com.github.musicyou.enums.SortOrder
import com.github.musicyou.models.LocalMenuState
import com.github.musicyou.ui.components.NonQueuedMediaItemMenu
import com.github.musicyou.ui.glass.GlassIconButton
import com.github.musicyou.ui.glass.ContrastRegion
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.ui.glass.adaptiveContentColor
import com.github.musicyou.ui.glass.AdaptiveText as Text
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import com.github.musicyou.ui.navigation.Routes
import com.github.musicyou.utils.asMediaItem
import com.github.musicyou.utils.forcePlay
import com.github.musicyou.utils.thumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val MusicRed = Color(0xFFFA2D48)
val MusicInk = Color(0xFF17171B)
val MusicSecondary = Color(0xFF77777D)

@Composable
fun ApplePageTitle(title: String, onSettings: (() -> Unit)? = null, back: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) {
            IconButton(onClick = back) { Icon(Icons.Outlined.ChevronLeft, "Back", tint = MusicRed) }
        }
        Text(title, Modifier.weight(1f), fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = MusicInk)
        if (onSettings != null) {
            GlassIconButton(onClick = onSettings, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.AccountCircle, "Settings and appearance", tint = MusicRed, modifier = Modifier.size(29.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
    }
}

@Composable
fun AppleDiscoverPage(mode: String, navController: NavHostController) {
    val binder = LocalPlayerServiceBinder.current
    val menu = LocalMenuState.current
    val bottom = LocalPlayerPadding.current
    var songs by remember(mode) { mutableStateOf<List<Innertube.SongItem>>(emptyList()) }
    var loading by remember(mode) { mutableStateOf(true) }
    var failed by remember(mode) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var station by rememberSaveable { mutableStateOf("Chill") }
    val recent by remember { database.songs(SongSortBy.DateAdded, SortOrder.Descending) }.collectAsState(emptyList())
    var related by remember { mutableStateOf<Innertube.RelatedPage?>(null) }
    LaunchedEffect(mode, recent.firstOrNull()?.id) {
        if(mode == "Home") {
            related = withContext(Dispatchers.IO) { Innertube.relatedPage(recent.firstOrNull()?.id ?: "fJ9rUzIMcZQ")?.getOrNull() }
        }
    }
    LaunchedEffect(mode, retry, station) {
        loading = true
        val query = when(mode) { "New" -> "new music releases"; "Radio" -> "$station music"; else -> "popular music" }
        val result = withContext(Dispatchers.IO) {
            Innertube.searchPage(query = query, params = Innertube.SearchFilter.Song.value,
                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from)
        }
        songs = result?.getOrNull()?.items.orEmpty()
        failed = songs.isEmpty()
        loading = false
    }
    fun play(song: Innertube.SongItem) {
        binder?.stopRadio()
        binder?.player?.forcePlay(song.asMediaItem)
        binder?.setupRadio(song.info?.endpoint)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 18.dp)) {
        item { ApplePageTitle(mode, onSettings = { navController.navigate(Routes.Settings) }) }
        if (mode == "Radio") {
            item {
                Text("A station for every mood.", Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = MusicSecondary, fontSize = 17.sp)
                LazyRow(contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(listOf("Chill", "Pop", "Jazz", "Classical", "Electronic", "Rock")) { name ->
                        FilterChip(selected = station == name, onClick = { station = name }, label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MusicRed, selectedLabelColor = Color.White), shape = RoundedCornerShape(50))
                    }
                }
            }
        }
        item {
            Text(when(mode) { "New" -> "FRESH FINDS"; "Radio" -> "CONTINUOUS DISCOVERY"; else -> "PICKED FOR YOU" },
                Modifier.padding(horizontal = 24.dp).padding(top = 6.dp, bottom = 8.dp), color = MusicSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
        if (songs.isNotEmpty()) {
            item {
                val song = songs.first()
                Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(288.dp).clip(RoundedCornerShape(12.dp)).clickable { play(song) }) {
                    AsyncImage(song.thumbnail?.size(1200), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.8f)))))
                    ContrastRegion(Modifier.align(Alignment.BottomStart).padding(20.dp), overlay = Color.Black.copy(.8f), drawScrim = false) {
                        Column {
                            Text(if(mode == "Radio") "$station Station" else song.info?.name.orEmpty(), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text(if(mode == "Radio") "Tap to play · Endless music" else song.authors?.joinToString { it.name.orEmpty() }.orEmpty(), color = Color.White.copy(.8f), fontSize = 15.sp)
                        }
                    }
                    ContrastRegion(Modifier.align(Alignment.BottomEnd).padding(20.dp), overlay = Color.Black.copy(.8f), drawScrim = false) {
                        Icon(Icons.Outlined.PlayCircle, "Play", Modifier.size(44.dp), tint = Color.White)
                    }
                }
            }
            item { SectionTitle(if(mode == "New") "New & Noteworthy" else if(mode == "Radio") "Start your station" else "Top Picks") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(songs.drop(1).take(10)) { song ->
                        ArtworkCard(song.thumbnail?.url, song.info?.name.orEmpty(), song.authors?.joinToString { it.name.orEmpty() }.orEmpty(), onClick = { play(song) })
                    }
                }
            }
            item { SectionTitle(if(mode == "Radio") "Keep listening" else "Songs for your day") }
            items(songs.drop(3).take(12)) { song ->
                AppleSongRow(song.info?.name.orEmpty(), song.authors?.joinToString { it.name.orEmpty() }.orEmpty(), song.thumbnail?.url,
                    onClick = { play(song) }, onMenu = {
                        menu.display { NonQueuedMediaItemMenu(onDismiss = menu::hide, mediaItem = song.asMediaItem,
                            onGoToAlbum = { navController.navigate(Routes.Album(it)) }, onGoToArtist = { navController.navigate(Routes.Artist(it)) }) }
                    })
            }
        } else if (loading) {
            item {
                Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(.035f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = MusicRed, strokeWidth = 2.dp)
                }
            }
        } else if (failed) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.WifiOff, null, Modifier.size(40.dp), tint = MusicSecondary)
                    Text("Music is taking a moment", Modifier.padding(top = 12.dp), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("Check your connection and try again.", Modifier.padding(top = 6.dp), color = MusicSecondary)
                    TextButton(onClick = { retry++ }) { Text("Try Again", color = MusicRed) }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Recently Played") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(recent.take(10)) { song ->
                        ArtworkCard(song.thumbnailUrl, song.title, song.artistsText.orEmpty(), onClick = { binder?.player?.forcePlay(song.asMediaItem) })
                    }
                }
            }
        }
        if(!related?.albums.isNullOrEmpty()) {
            item { SectionTitle("Albums You May Like") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(related?.albums.orEmpty()) { album ->
                        ArtworkCard(album.thumbnail?.url, album.info?.name.orEmpty(), album.authors?.joinToString { it.name.orEmpty() }.orEmpty(), { navController.navigate(Routes.Album(album.key)) })
                    }
                }
            }
        }
        if(!related?.playlists.isNullOrEmpty()) {
            item { SectionTitle("Recommended Playlists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(related?.playlists.orEmpty()) { playlist ->
                        ArtworkCard(playlist.thumbnail?.url, playlist.info?.name.orEmpty(), playlist.channel?.name.orEmpty(), { navController.navigate(Routes.Playlist(playlist.key)) })
                    }
                }
            }
        }
    }
}

@Composable
fun ArtworkCard(url: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.width(166.dp).clickable(onClick = onClick)) {
        AsyncImage(url.thumbnail(600), title, Modifier.size(166.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(.05f)), contentScale = ContentScale.Crop)
        Text(title, Modifier.padding(top = 8.dp), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = MusicSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AppleSongRow(title: String, subtitle: String, url: String?, onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
    Column(Modifier.padding(start = 24.dp, end = 16.dp)) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMenu).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(url.thumbnail(180), null, Modifier.size(50.dp).clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(.04f)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MusicSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onMenu != null) IconButton(onClick = onMenu) { Icon(Icons.Outlined.MoreHoriz, "Song options", tint = MusicSecondary) }
        }
        HorizontalDivider(Modifier.padding(start = 62.dp), color = Color.Black.copy(.07f), thickness = .5.dp)
    }
}

@Composable
fun AppleLibraryPage(navController: NavHostController) {
    val bottom = LocalPlayerPadding.current
    val binder = LocalPlayerServiceBinder.current
    val recent by remember { database.songs(SongSortBy.DateAdded, SortOrder.Descending) }.collectAsState(emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
        item { ApplePageTitle("Library", onSettings = { navController.navigate(Routes.Settings) }) }
        val entries = listOf(Triple("Playlists", Icons.AutoMirrored.Outlined.QueueMusic, Routes.Playlists), Triple("Artists", Icons.Outlined.Mic, Routes.Artists),
            Triple("Albums", Icons.Outlined.Album, Routes.Albums), Triple("Songs", Icons.Outlined.MusicNote, Routes.Songs), Triple("Downloaded", Icons.Outlined.Download, Routes.Downloads),
            Triple("Favorites", Icons.Outlined.StarBorder, Routes.BuiltInPlaylist(0)))
        items(entries) { (label, icon, route) ->
            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().clickable { navController.navigate(route) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(27.dp), tint = MusicRed)
                    Text(label, Modifier.weight(1f).padding(start = 18.dp), fontSize = 21.sp)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(21.dp), tint = MusicSecondary.copy(.55f))
                }
                HorizontalDivider(color = Color.Black.copy(.08f), thickness = .5.dp)
            }
        }
        item { SectionTitle("Recently Added") }
        if (recent.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp, 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(44.dp), tint = MusicSecondary)
                    Text("Your music lives here", Modifier.padding(top = 14.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Find a song to start your collection.", Modifier.padding(top = 6.dp), fontSize = 15.sp, color = MusicSecondary)
                    TextButton(onClick = { navController.navigate(Routes.Search) }) { Text("Find Music", color = MusicRed) }
                }
            }
        } else {
            items(recent.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    pair.forEach { song ->
                        Column(Modifier.weight(1f).clickable { binder?.player?.forcePlay(song.asMediaItem) }) {
                            AsyncImage(song.thumbnailUrl.thumbnail(600), song.title, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                            Text(song.title, Modifier.padding(top = 8.dp), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artistsText.orEmpty(), color = MusicSecondary, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    if(pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun AppleSearchPage(navController: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var library by rememberSaveable { mutableStateOf(false) }
    val bottom = LocalPlayerPadding.current
    Column(Modifier.fillMaxSize()) {
        ApplePageTitle("Search")
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("Music", "Your Library").forEachIndexed { index, label ->
                Column(Modifier.clickable { library = index == 1 }.padding(bottom = 10.dp)) {
                    Text(label, color = if(library == (index == 1)) MusicRed else MusicSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if(library == (index == 1)) Box(Modifier.padding(top = 7.dp).height(2.dp).width(if(index == 0) 45.dp else 83.dp).background(MusicRed))
                }
            }
        }
        GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 24.dp), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(23.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    textStyle = TextStyle(color = adaptiveContentColor(), fontSize = 16.sp),
                    singleLine = true, cursorBrush = SolidColor(adaptiveContentColor(MusicRed)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
                    decorationBox = { field ->
                        Box { if(query.isEmpty()) Text("Artists, Songs, Lyrics, and More", fontSize = 15.sp, color = MusicSecondary); field() }
                    }
                )
                if(query.isNotEmpty()) IconButton(onClick = { query = ""; submitted = "" }) { Icon(Icons.Outlined.Close, "Clear search") }
                else Spacer(Modifier.width(14.dp))
            }
        }
        if(submitted.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            if(library) {
                val songs by remember(submitted) { database.search("%$submitted%") }.collectAsState(emptyList())
                val binder = LocalPlayerServiceBinder.current
                LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
                    items(songs) { song -> AppleSongRow(song.title, song.artistsText.orEmpty(), song.thumbnailUrl, { binder?.player?.forcePlay(song.asMediaItem) }) }
                    if(songs.isEmpty()) item { Text("No songs in your library match this search.", Modifier.padding(24.dp), color = MusicSecondary) }
                }
            } else {
                com.github.musicyou.ui.screens.search.SearchResults(submitted,
                    onAlbumClick = { navController.navigate(Routes.Album(it)) }, onArtistClick = { navController.navigate(Routes.Artist(it)) }, onPlaylistClick = { navController.navigate(Routes.Playlist(it)) })
            }
        } else {
            Text("Browse Categories", Modifier.padding(24.dp, 20.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val categories = listOf("Pop" to Color(0xFFE83F67), "Chill" to Color(0xFF638095), "Rock" to Color(0xFFBC4728), "Jazz" to Color(0xFF99733E), "Electronic" to Color(0xFF725DBA), "Classical" to Color(0xFF567E65), "R&B" to Color(0xFF9C4A7F), "Acoustic" to Color(0xFFB57751))
            LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(categories) { (name, color) ->
                    ContrastRegion(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(10.dp)).background(color).clickable { query = name; submitted = name }, overlay = color) {
                        Icon(Icons.Outlined.MusicNote, null, Modifier.align(Alignment.TopEnd).padding(10.dp).size(60.dp), tint = Color.White.copy(.22f))
                        Text(name, Modifier.align(Alignment.BottomStart).padding(14.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
