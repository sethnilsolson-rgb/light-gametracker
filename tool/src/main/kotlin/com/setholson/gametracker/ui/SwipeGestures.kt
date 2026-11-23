package com.setholson.gametracker.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Triggers [onBack] when the user deliberately swipes right starting from the left edge.
 */
fun Modifier.swipeToBack(
    sideOnly: Boolean = true,
    edgeThresholdPx: Float = 150f,
    swipeDistanceThresholdPx: Float = 110f,
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
