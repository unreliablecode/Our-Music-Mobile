package com.github.musicyou.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import com.github.musicyou.ui.glass.AdaptiveText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.R
import com.github.musicyou.ui.styling.Dimensions
import com.github.musicyou.BuildConfig
import com.github.musicyou.ui.glass.adaptiveContentColor

@Composable
fun About() {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val playerPadding = LocalPlayerPadding.current
    val currentVersion =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 16.dp + playerPadding)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.app_icon),
            contentDescription = stringResource(id = R.string.app_name),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(125.dp)
                .aspectRatio(1F),
            tint = adaptiveContentColor(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = "${stringResource(id = R.string.app_name)} v$currentVersion",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            textAlign = TextAlign.Center
        )
        Text(
            text = "An independent music player with a live glass interface.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Dimensions.spacer))
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("Our Music Mobile") },
            supportingContent = { Text("Source code and releases") },
            leadingContent = { Icon(painterResource(R.drawable.github), contentDescription = null, tint = adaptiveContentColor()) },
            modifier = Modifier.clickable { uriHandler.openUri(BuildConfig.REPOSITORY_URL) }
        )
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("Music You") },
            supportingContent = { Text("Original music player · GPL-3.0") },
            leadingContent = {
                Icon(painterResource(R.drawable.github), contentDescription = null, tint = adaptiveContentColor())
            },
            modifier = Modifier.clickable {
                uriHandler.openUri("https://github.com/DanielSevillano/music-you")
            }
        )
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("AndroidLiquidGlass") },
            supportingContent = { Text("Live glass rendering · Apache-2.0") },
            leadingContent = {
                Icon(painterResource(R.drawable.github), contentDescription = null, tint = adaptiveContentColor())
            },
            modifier = Modifier.clickable {
                uriHandler.openUri("https://github.com/unreliablecode/AndroidLiquidGlass")
            }
        )
    }
}
