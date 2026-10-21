package com.github.musicyou.ui.visualization

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Matches the window cadence to the plot on devices without per-view frame rate support. */
@Composable
internal fun VisualizationFrameRate(active: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    DisposableEffect(view, context, active, configuration.screenWidthDp, configuration.screenHeightDp, configuration.densityDpi) {
        if (!active) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        var lease: WindowFrameRateLease? = null
        fun attach() {
            if (lease != null || !view.isAttachedToWindow) return
            val window = (view.parent as? DialogWindowProvider)?.window ?: context.activityWindow()
            if (window != null) lease = WindowFrameRateRequests.acquire(window, view.display)
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = attach()
            override fun onViewDetachedFromWindow(v: View) {
                lease?.close()
                lease = null
            }
        }
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (view.display?.displayId == displayId) lease?.refresh(view.display)
            }
        }
        view.addOnAttachStateChangeListener(attachListener)
        manager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        attach()
        onDispose {
            manager?.unregisterDisplayListener(displayListener)
            view.removeOnAttachStateChangeListener(attachListener)
            lease?.close()
        }
    }
}

private fun Context.activityWindow(): Window? {
    var current = this
    while (true) {
        if (current is Activity) return current.window
        if (current !is ContextWrapper || current.baseContext === current) return null
        current = current.baseContext
    }
}

private class WindowFrameRateLease(window: Window) {
    private val reference = WeakReference(window)
    private var closed = false

    fun refresh(display: Display?) {
        if (!closed) reference.get()?.let { WindowFrameRateRequests.refresh(it, display) }
    }

    fun close() {
        if (closed) return
        closed = true
        reference.get()?.let(WindowFrameRateRequests::release)
    }
}

/** Accessed on the UI thread; the values intentionally hold no strong window reference. */
private object WindowFrameRateRequests {
    private class Request(val originalMode: Int, val originalRate: Float, var mode: Int) {
        var users = 1
    }

    private val requests = WeakHashMap<Window, Request>()

    fun acquire(window: Window, display: Display?): WindowFrameRateLease {
        val existing = requests[window]
        if (existing != null) {
            existing.users++
            refresh(window, display)
        } else {
            val attributes = window.attributes
            val request = Request(attributes.preferredDisplayModeId, attributes.preferredRefreshRate, modeAtSixtyHertz(display))
            requests[window] = request
            window.attributes = attributes.apply {
                preferredRefreshRate = 60f
                preferredDisplayModeId = request.mode
            }
        }
        return WindowFrameRateLease(window)
    }

    fun refresh(window: Window, display: Display?) {
        val request = requests[window] ?: return
        val mode = modeAtSixtyHertz(display)
        if (mode == request.mode) return
        val attributes = window.attributes
        // A later owner can override either preference. Only replace a display
        // mode that is still ours when a fold or resolution change needs it.
        if (attributes.preferredDisplayModeId == request.mode) {
            attributes.preferredDisplayModeId = mode
            window.attributes = attributes
            request.mode = mode
        }
    }

    fun release(window: Window) {
        val request = requests[window] ?: return
        if (--request.users > 0) return
        requests.remove(window)
        val attributes = window.attributes
        var changed = false
        if (attributes.preferredDisplayModeId == request.mode) {
            attributes.preferredDisplayModeId = request.originalMode
            changed = true
        }
        if (attributes.preferredRefreshRate == 60f) {
            attributes.preferredRefreshRate = request.originalRate
            changed = true
        }
        if (changed) window.attributes = attributes
    }

    private fun modeAtSixtyHertz(display: Display?): Int {
        val current = display?.mode ?: return 0
        return display.supportedModes.asSequence().filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                abs(it.refreshRate - 60f) <= 1f
        }.minByOrNull { abs(it.refreshRate - 60f) }?.modeId ?: 0
    }
}
