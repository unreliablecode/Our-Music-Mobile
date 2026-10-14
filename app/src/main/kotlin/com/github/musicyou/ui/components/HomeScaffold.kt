package com.github.musicyou.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.musicyou.ui.apple.ApplePageTitle
import com.github.musicyou.ui.apple.LocalMusicNavController

@Composable
fun HomeScaffold(@StringRes title: Int, snackbarHost: @Composable () -> Unit = {}, floatingActionButton: @Composable () -> Unit = {}, openSearch: () -> Unit, openSettings: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        val navController = LocalMusicNavController.current
        Column {
            ApplePageTitle(stringResource(title), onSettings = openSettings, back = { navController?.popBackStack() })
            Box(Modifier.weight(1f)) { content() }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) { floatingActionButton() }
        snackbarHost()
    }
}
