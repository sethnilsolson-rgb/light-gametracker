package com.setholson.gametracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.setholson.gametracker.model.ChessClockPreset
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class ChessClockUiState(
    val preset: ChessClockPreset = ChessClockPreset.BLITZ_5_0,
    val p1Millis: Long = ChessClockPreset.BLITZ_5_0.initialSeconds * 1000L,
    val p2Millis: Long = ChessClockPreset.BLITZ_5_0.initialSeconds * 1000L,
    val p1Moves: Int = 0,
    val p2Moves: Int = 0,
    val activePlayer: Int? = null, // 1 or 2
    val isRunning: Boolean = false,
    val flaggedPlayer: Int? = null,
)

class ChessClockViewModel : LightViewModel<Unit>() {

    private val _ui = MutableStateFlow(ChessClockUiState())
    val ui: StateFlow<ChessClockUiState> = _ui.asStateFlow()

    private var tickJob: Job? = null

    fun tapPlayerZone(player: Int) {
        val current = _ui.value
        if (current.flaggedPlayer != null) return

        // If clock hasn't started yet, tapping Player 1 starts Player 2's clock (and vice versa)
        if (current.activePlayer == null) {
            val nextActive = if (player == 1) 2 else 1
            _ui.value = current.copy(activePlayer = nextActive, isRunning = true)
            startTicking()
            return
        }

        // Only the active player's tap passes the turn
        if (current.activePlayer != player || !current.isRunning) return

        val incMs = current.preset.incrementSeconds * 1000L
        val updated = if (player == 1) {
            current.copy(
                p1Millis = current.p1Millis + incMs,
                p1Moves = current.p1Moves + 1,
                activePlayer = 2,
            )
        } else {
            current.copy(
                p2Millis = current.p2Millis + incMs,
                p2Moves = current.p2Moves + 1,
                activePlayer = 1,
            )
        }
        _ui.value = updated
    }

    fun togglePause() {
        val current = _ui.value
        if (current.flaggedPlayer != null) return
        if (current.isRunning) {
            pause()
        } else {
            val active = current.activePlayer ?: 1
            _ui.value = current.copy(activePlayer = active, isRunning = true)
            startTicking()
        }
    }

    fun pause() {
        tickJob?.cancel()
        tickJob = null
        if (_ui.value.isRunning) {
            _ui.value = _ui.value.copy(isRunning = false)
        }
    }

    fun cyclePreset() {
        pause()
        val entries = ChessClockPreset.entries
        val nextIndex = (entries.indexOf(_ui.value.preset) + 1) % entries.size
        val nextPreset = entries[nextIndex]
        val initMs = nextPreset.initialSeconds * 1000L
        _ui.value = ChessClockUiState(
            preset = nextPreset,
            p1Millis = initMs,
            p2Millis = initMs,
        )
    }

    fun reset() {
        pause()
        val preset = _ui.value.preset
        val initMs = preset.initialSeconds * 1000L
        _ui.value = ChessClockUiState(
            preset = preset,
            p1Millis = initMs,
            p2Millis = initMs,
        )
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var lastNanos = System.nanoTime()
            while (isActive) {
                delay(100L)
                val now = System.nanoTime()
                val elapsedMs = ((now - lastNanos) / 1_000_000L).coerceAtLeast(0L)
                lastNanos = now

                val current = _ui.value
                if (!current.isRunning || current.activePlayer == null) break

                if (current.activePlayer == 1) {
                    val next = (current.p1Millis - elapsedMs).coerceAtLeast(0L)
                    if (next == 0L) {
                        _ui.value = current.copy(p1Millis = 0L, isRunning = false, flaggedPlayer = 1)
                        break
                    } else {
                        _ui.value = current.copy(p1Millis = next)
                    }
                } else {
                    val next = (current.p2Millis - elapsedMs).coerceAtLeast(0L)
                    if (next == 0L) {
                        _ui.value = current.copy(p2Millis = 0L, isRunning = false, flaggedPlayer = 2)
                        break
                    } else {
                        _ui.value = current.copy(p2Millis = next)
                    }
                }
            }
        }
    }

    // Battery saver: auto-pause clock whenever screen hides or phone sleeps
    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        pause()
        super.onScreenHide(screen)
    }

    override fun onAppPause() {
        pause()
        super.onAppPause()
    }

    override fun onCleared() {
        pause()
        super.onCleared()
    }
}

class ChessClockScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, ChessClockViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChessClockViewModel>
        get() = ChessClockViewModel::class.java

    override fun createViewModel() = ChessClockViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val ui by viewModel.ui.collectAsState()
        val haptics = LocalHapticFeedback.current
        val lineColor = LightThemeTokens.colors.content.copy(alpha = 0.28f)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .swipeToBack(sideOnly = true, edgeThresholdPx = 110f) {
                        viewModel.pause()
                        goBack()
                    },
            ) {
                // Top Player (Player 2, rotated 180°)
                ClockHalf(
                    label = "Player 2",
                    millis = ui.p2Millis,
                    moves = ui.p2Moves,
                    isActive = ui.activePlayer == 2 && ui.isRunning,
                    isFlagged = ui.flaggedPlayer == 2,
                    rotated = true,
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.tapPlayerZone(2)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                // Thin Center Strip
                HorizontalDivider(thickness = 1.dp, color = lineColor)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .background(LightThemeTokens.colors.background)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ClockControlBtn("←") {
                        viewModel.pause()
                        goBack()
                    }
                    ClockControlBtn(if (ui.isRunning) "Pause" else "Start") {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.togglePause()
                    }
                    ClockControlBtn(ui.preset.label) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.cyclePreset()
                    }
                    ClockControlBtn("Reset") {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.reset()
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = lineColor)

                // Bottom Player (Player 1, upright)
                ClockHalf(
                    label = "Player 1",
                    millis = ui.p1Millis,
                    moves = ui.p1Moves,
                    isActive = ui.activePlayer == 1 && ui.isRunning,
                    isFlagged = ui.flaggedPlayer == 1,
                    rotated = false,
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.tapPlayerZone(1)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ClockHalf(
    label: String,
    millis: Long,
    moves: Int,
    isActive: Boolean,
    isFlagged: Boolean,
    rotated: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = LightThemeTokens.colors.content
    val bg = LightThemeTokens.colors.background
    val formatted = formatClockMillis(millis)

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = if (rotated) 180f else 0f }
            .then(
                if (isActive || isFlagged) {
                    Modifier.border(2.dp, content)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = "$label • Moves: $moves",
                variant = LightTextVariant.Detail,
                lighten = !isActive,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = formatted,
                fontSize = 54.sp,
                fontWeight = if (isActive) FontWeight.Normal else FontWeight.Light,
                color = if (isActive || isFlagged) content else content.copy(alpha = 0.65f),
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (isFlagged) {
                Box(
                    modifier = Modifier
                        .background(content)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "TIME UP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = bg,
                    )
                }
            } else if (isActive) {
                LightText(text = "TAP TO END TURN", variant = LightTextVariant.Detail)
            }
        }
    }
}

@Composable
private fun ClockControlBtn(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Detail)
    }
}

internal fun formatClockMillis(millis: Long): String {
    val clamped = millis.coerceAtLeast(0L)
    val totalSeconds = clamped / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (totalSeconds < 20L) {
        val tenths = (clamped % 1000L) / 100L
        String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
