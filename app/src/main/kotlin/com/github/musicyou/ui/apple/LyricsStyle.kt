package com.github.musicyou.ui.apple

internal enum class LyricsStyle(val preferenceValue: String) {
    DEFAULT("default"),
    ANIMATED_SMOOTH("animated_smooth");

    companion object {
        fun fromPreference(value: String?): LyricsStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}
