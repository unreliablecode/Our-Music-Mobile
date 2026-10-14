package com.github.musicyou.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.github.musicyou.enums.SettingsSection

@Composable
fun SettingsPage(
    section: SettingsSection,
    pop: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { SettingsHeader(stringResource(section.resourceId), pop) },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())) {
            when (section) {
                SettingsSection.General -> GeneralSettings()
                SettingsSection.Player -> PlayerSettings()
                SettingsSection.Gestures -> GestureSettings()
                SettingsSection.Cache -> CacheSettings()
                SettingsSection.Database -> DatabaseSettings()
                SettingsSection.Other -> OtherSettings()
                SettingsSection.About -> About()
            }
        }
    }
}
