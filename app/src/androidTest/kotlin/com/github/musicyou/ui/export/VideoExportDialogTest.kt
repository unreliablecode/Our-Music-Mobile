package com.github.musicyou.ui.export

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.musicyou.export.VideoExports
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real configuration controls without downloading or exporting a user's song. */
@RunWith(AndroidJUnit4::class)
class VideoExportDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var saved: Map<String, *> = emptyMap<String, Any?>()

    @Before
    fun prepare() {
        val preferences = context.getSharedPreferences("video_export", Context.MODE_PRIVATE)
        saved = preferences.all.toMap()
        assumeFalse("An existing export is left undisturbed", VideoExports.get(context).state.value.running)
        preferences.edit().clear().putString("aspect", "LANDSCAPE").putString("background", "AURORA").putString("resolution", "HD").commit()
    }

    @After
    fun restorePreferences() {
        compose.activityRule.scenario.onActivity { it.setContent {} }
        val editor = context.getSharedPreferences("video_export", Context.MODE_PRIVATE).edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    @Test
    fun anIncompleteSongCannotStartExportOrInventTimedLyrics() {
        show()
        compose.onNodeWithTag("video-export-start").assertIsNotEnabled().assertIsDisplayed()
        compose.onNodeWithText("A complete offline download is required for reliable audio export.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("export-include-lyrics").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("No synchronized timing is saved for this song.").assertIsDisplayed()
    }

    @Test
    fun portraitAndLandscapeChoicesRemainReachableWhileSettingsScroll() {
        show()
        compose.onNodeWithText("Portrait 9:16").performScrollTo().performClick()
        compose.onNodeWithText("Portrait 9:16").assertIsSelected()
        compose.onNodeWithText("1080p").performScrollTo().performClick()
        compose.onNodeWithText("1080p").assertIsSelected()
        compose.onNodeWithText("Oscilloscope").performScrollTo().performClick()
        compose.onNodeWithText("Oscilloscope").assertIsSelected()
        compose.onNodeWithText("Sunset").performScrollTo().performClick()
        compose.onNodeWithText("Sunset").assertIsSelected()
        compose.onNodeWithText("Landscape 16:9").performScrollTo().performClick()
        compose.onNodeWithText("Landscape 16:9").assertIsSelected()
        compose.onNodeWithTag("video-export-start").assertIsNotEnabled().assertIsDisplayed()
    }

    private fun show() {
        val media = MediaItem.Builder().setMediaId("video-export-ui-fixture-no-download")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Video export layout fixture").setArtist("Local test").build()).build()
        compose.setContent {
            MaterialTheme { VideoExportDialog(media, 10_000L) {} }
        }
    }
}
