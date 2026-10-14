package com.github.musicyou.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.SystemUpdate
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.BuildConfig
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.ui.glass.AdaptiveText as Text
import com.github.musicyou.ui.glass.GlassIconButton
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.ui.glass.GlassToggle
import com.github.musicyou.ui.glass.adaptiveContentColor
import java.text.DateFormat
import java.util.Date

@Composable
fun UpdateScreen(coordinator: UpdateCoordinator = rememberUpdateCoordinator(), onBack: () -> Unit = {}) {
    val state by coordinator.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(coordinator) { if (state.phase == UpdatePhase.Idle) coordinator.checkNow() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = LocalPlayerPadding.current + 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = adaptiveContentColor()) }
            Text("Updates", Modifier.padding(start = 16.dp), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (state.hasAvailableUpdate) Icons.Outlined.SystemUpdate else Icons.Outlined.CheckCircle,
                    null, Modifier.size(40.dp), tint = adaptiveContentColor(MaterialTheme.colorScheme.primary))
                Text(when (state.phase) {
                    UpdatePhase.Checking -> "Checking for updates"
                    UpdatePhase.Available -> "An update is ready"
                    UpdatePhase.Downloading -> "Downloading update"
                    UpdatePhase.Ready -> "Ready to install"
                    UpdatePhase.AwaitingPermission -> "Allow update installation"
                    UpdatePhase.Installing -> "Installing update"
                    UpdatePhase.Error -> "Update could not finish"
                    UpdatePhase.Current -> "You are up to date"
                    UpdatePhase.Idle -> "Our Music updates"
                }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Installed version ${BuildConfig.VERSION_NAME}")
                state.release?.let { Text("Latest version ${it.versionName}") }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (state.checkedAt > 0) Text("Last checked ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.checkedAt))}", fontSize = 12.sp)
                if (state.phase == UpdatePhase.Downloading) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(state.progress * 100).toInt()}% · ${state.downloadedBytes / 1048576} MB downloaded")
                    UpdateButton("Pause download", coordinator::cancelDownload)
                } else if (state.phase in listOf(UpdatePhase.Ready, UpdatePhase.AwaitingPermission)) {
                    UpdateButton("Install update", coordinator::installReadyUpdate)
                } else if (state.hasAvailableUpdate && state.phase != UpdatePhase.Installing) {
                    UpdateButton(if (state.progress > 0) "Resume download" else "Download and install", coordinator::downloadAndInstall)
                }
                if (state.phase !in listOf(UpdatePhase.Checking, UpdatePhase.Downloading, UpdatePhase.Installing)) {
                    UpdateButton("Check now", coordinator::checkNow)
                }
            }
        }
        GlassSurface(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Automatic checks", fontWeight = FontWeight.SemiBold)
                    Text("Check for new releases when Our Music opens.", fontSize = 13.sp)
                }
                GlassToggle(state.automaticChecks, coordinator::setAutomaticChecks)
            }
        }
        state.release?.let { release ->
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("What's new", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(release.changelog.ifBlank { "See the release notes on GitHub." }, style = MaterialTheme.typography.bodyLarge)
                    UpdateButton("View release on GitHub") { uriHandler.openUri(release.releaseUrl) }
                }
            }
        }
        Text("Android will ask you to confirm before an update is installed.", fontSize = 13.sp)
    }
}

@Composable
private fun UpdateButton(label: String, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.SemiBold, color = adaptiveContentColor(MaterialTheme.colorScheme.primary))
        }
    }
}
