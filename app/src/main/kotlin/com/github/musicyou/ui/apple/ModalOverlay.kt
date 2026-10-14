package com.github.musicyou.ui.apple

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/** Keeps the captured scene visible while excluding its controls from focus. */
internal fun Modifier.modalUnderlay(blocked: Boolean): Modifier =
    if (blocked) focusProperties { canFocus = false }.clearAndSetSemantics {} else this

/** A modal in the same window as the scene sampled by the glass renderer. */
@Composable
internal fun ModalOverlay(
    active: Boolean,
    onDismiss: () -> Unit,
    paneTitle: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // Register after the underlying navigation and before nested modal handlers.
    BackHandler(enabled = active, onBack = onDismiss)
    if (active) {
        Box(modifier.fillMaxSize().semantics { this.paneTitle = paneTitle }) {
            // A sibling barrier intercepts empty regions without consuming the
            // gestures handled by buttons, sliders and scrolling modal content.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            })
            content()
        }
    }
}
