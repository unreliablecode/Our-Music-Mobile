package com.github.musicyou.ui.glass.motion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** A horizontal control lets its enclosing list retain vertical scrolling. */
internal suspend fun PointerInputScope.liquidControlGestures(
    onPress: (Offset) -> Unit,
    onDragStart: (Offset, Offset) -> Unit,
    onDrag: (Offset, Offset) -> Unit,
    onFinish: (Boolean, Offset) -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var dragging = false
        var finished = false
        var distance = Offset.Zero
        onPress(down.position)
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                if (change.changedToUpIgnoreConsumed()) {
                    change.consume()
                    finished = true
                    onFinish(dragging, change.position)
                    break
                }
                if (!change.pressed) break
                val amount = change.positionChange()
                distance += amount
                if (!dragging) {
                    val slop = viewConfiguration.touchSlop
                    if (abs(distance.y) > slop && abs(distance.y) > abs(distance.x)) break
                    if (abs(distance.x) > slop) {
                        dragging = true
                        change.consume()
                        onDragStart(down.position, change.position)
                    }
                } else {
                    change.consume()
                    onDrag(change.position, amount)
                }
                if (!dragging) {
                    val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                    if (finalEvent.changes.firstOrNull { it.id == down.id }?.isConsumed == true) break
                }
            }
        } finally {
            if (!finished) onCancel()
        }
    }
}
