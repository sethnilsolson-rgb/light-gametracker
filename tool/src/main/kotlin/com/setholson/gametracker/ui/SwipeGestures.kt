package com.setholson.gametracker.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Triggers [onBack] when the user swipes quickly from the left edge toward the right.
 */
fun Modifier.swipeToBack(
    sideOnly: Boolean = false,
    edgeThresholdPx: Float = 260f,
    swipeDistanceThresholdPx: Float = 70f,
    onBack: () -> Unit,
): Modifier = pointerInput(sideOnly, edgeThresholdPx, swipeDistanceThresholdPx) {
    var startX = 0f
    var dragAccum = 0f

    detectHorizontalDragGestures(
        onDragStart = { offset ->
            startX = offset.x
            dragAccum = 0f
        },
        onDragEnd = {
            val validStart = !sideOnly || startX <= edgeThresholdPx
            if (validStart && dragAccum >= swipeDistanceThresholdPx) {
                onBack()
            }
            dragAccum = 0f
        },
        onDragCancel = {
            dragAccum = 0f
        },
        onHorizontalDrag = { _, dragAmount ->
            dragAccum += dragAmount
        },
    )
}
