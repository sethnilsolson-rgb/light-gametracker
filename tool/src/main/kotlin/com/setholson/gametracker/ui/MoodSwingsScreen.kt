package com.setholson.gametracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.setholson.gametracker.data.GameRepository
import com.setholson.gametracker.db.GameDatabase
import com.setholson.gametracker.model.MoodSwingsMode
import com.setholson.gametracker.model.MoodSwingsState
import com.setholson.gametracker.model.defaultMoodSwingsPlayers
import com.setholson.gametracker.model.moodSwingsTurnRank
import com.setholson.gametracker.model.resolveMoodSwingsRound
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightVibrationUsage
import com.thelightphone.sdk.LightVibrationWaveform
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.vibrationAmplitudesOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

sealed interface MoodSwingsEditAction {
    data class RenamePlayer(val playerId: Int, val currentName: String) : MoodSwingsEditAction
    data class SetRoundScore(val playerId: Int, val playerName: String) : MoodSwingsEditAction
}

class MoodSwingsViewModel(
    private val repository: GameRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow(MoodSwingsState())
    val state: StateFlow<MoodSwingsState> = _state.asStateFlow()

    private val _undoStack = MutableStateFlow<List<MoodSwingsState>>(emptyList())
    val canUndo: StateFlow<Boolean> = MutableStateFlow(false)

    private val _editAction = MutableStateFlow<MoodSwingsEditAction?>(null)
    val editAction: StateFlow<MoodSwingsEditAction?> = _editAction.asStateFlow()

    init {
        viewModelScope.launch {
            repository.loadMoodSwingsState()?.let { saved ->
                _state.value = saved
            }
        }
    }

    private fun updateAndPersist(
        recordUndo: Boolean = false,
        transform: (MoodSwingsState) -> MoodSwingsState,
    ) {
        val prev = _state.value
        val next = transform(prev)
        if (next == prev) return
        if (recordUndo) {
            val nextStack = (listOf(prev) + _undoStack.value).take(8)
            _undoStack.value = nextStack
            (canUndo as MutableStateFlow).value = nextStack.isNotEmpty()
        }
        _state.value = next
        viewModelScope.launch {
            repository.saveMoodSwingsState(next)
        }
    }

    fun selectMode(mode: MoodSwingsMode) {
        val current = _state.value
        val existingNames = current.players.associate { it.id to it.name }
        _undoStack.value = emptyList()
        (canUndo as MutableStateFlow).value = false
        updateAndPersist {
            MoodSwingsState(
                mode = mode,
                players = defaultMoodSwingsPlayers(mode, existingNames),
            )
        }
    }

    fun adjustRoundScore(playerId: Int, delta: Int) {
        updateAndPersist { current ->
            if (current.winnerId != null) return@updateAndPersist current
            current.copy(
                players = current.players.map { p ->
                    if (p.id == playerId) {
                        p.copy(currentRoundScore = (p.currentRoundScore + delta).coerceAtLeast(0))
                    } else {
                        p
                    }
                },
            )
        }
    }

    fun setFirstPlayer(index: Int) {
        updateAndPersist { current ->
            if (index !in current.players.indices) return@updateAndPersist current
            current.copy(firstPlayerIndex = index)
        }
    }

    fun scoreRound() {
        updateAndPersist(recordUndo = true) { current ->
            resolveMoodSwingsRound(current)
        }
    }

    fun undoLastRound() {
        val stack = _undoStack.value
        val previous = stack.firstOrNull() ?: return
        val remaining = stack.drop(1)
        _undoStack.value = remaining
        (canUndo as MutableStateFlow).value = remaining.isNotEmpty()
        _state.value = previous
        viewModelScope.launch {
            repository.saveMoodSwingsState(previous)
        }
    }

    fun resetMatch() {
        val current = _state.value
        val existingNames = current.players.associate { it.id to it.name }
        _undoStack.value = emptyList()
        (canUndo as MutableStateFlow).value = false
        updateAndPersist {
            MoodSwingsState(
                mode = current.mode,
                players = defaultMoodSwingsPlayers(current.mode, existingNames).mapIndexed { i, p ->
                    val preserved = current.players.getOrNull(i)?.name ?: p.name
                    p.copy(name = preserved)
                },
            )
        }
    }

    fun startEdit(action: MoodSwingsEditAction) {
        _editAction.value = action
    }

    fun cancelEdit() {
        _editAction.value = null
    }

    fun submitEdit(rawText: String) {
        val action = _editAction.value ?: return
        _editAction.value = null
        val trimmed = rawText.trim()
        when (action) {
            is MoodSwingsEditAction.RenamePlayer -> {
                if (trimmed.isNotBlank()) {
                    updateAndPersist { current ->
                        current.copy(
                            players = current.players.map { p ->
                                if (p.id == action.playerId) p.copy(name = trimmed.take(18)) else p
                            },
                        )
                    }
                }
            }
            is MoodSwingsEditAction.SetRoundScore -> {
                val parsed = trimmed.toIntOrNull()
                if (parsed != null) {
                    updateAndPersist { current ->
                        current.copy(
                            players = current.players.map { p ->
                                if (p.id == action.playerId) {
                                    p.copy(currentRoundScore = parsed.coerceAtLeast(0))
                                } else {
                                    p
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

class MoodSwingsScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, MoodSwingsViewModel>(sealedActivity) {

    private val repository = GameRepository.getInstance {
        lightContext.buildDatabase(
            GameDatabase::class.java,
            GameRepository.DATABASE_NAME,
        ) {
            fallbackToDestructiveMigration(true)
        }
    }

    override val viewModelClass: Class<MoodSwingsViewModel>
        get() = MoodSwingsViewModel::class.java

    override fun createViewModel() = MoodSwingsViewModel(repository)

    private val roundWinWaveform = LightVibrationWaveform(
        durations = listOf(80.milliseconds, 60.milliseconds, 120.milliseconds),
        amplitudes = vibrationAmplitudesOf(180, 0, 220),
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val canUndo by viewModel.canUndo.collectAsState()
        val editAction by viewModel.editAction.collectAsState()

        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            val content = LightThemeTokens.colors.content
            val bg = LightThemeTokens.colors.background
            val sidePad = 1.5f.gridUnitsAsDp()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .then(
                        if (editAction == null) {
                            Modifier.swipeToBack { goBack() }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (editAction != null) {
                    val title = when (val act = editAction) {
                        is MoodSwingsEditAction.RenamePlayer -> "Rename ${act.currentName}"
                        is MoodSwingsEditAction.SetRoundScore -> "${act.playerName} Score"
                        null -> ""
                    }
                    LightTextInputEditor(
                        title = title,
                        state = textFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        submitLabel = "Save",
                        singleLine = true,
                        onSubmit = { seq -> viewModel.submitEdit(seq.toString()) },
                        onBack = { viewModel.cancelEdit() },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.Text("Mood Swings"),
                            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                        )

                        // Mode selector: 2P, 3P, 4P, 2v2
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = sidePad, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MoodSwingsMode.entries.forEach { m ->
                                val sel = state.mode == m
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (sel) content else bg, RoundedCornerShape(5.dp))
                                        .border(
                                            1.dp,
                                            content.copy(alpha = if (sel) 1f else 0.45f),
                                            RoundedCornerShape(5.dp),
                                        )
                                        .lightClickable {
                                            runCatching { sealedActivity.vibrator.click() }
                                            viewModel.selectMode(m)
                                        }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = m.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (sel) bg else content,
                                    )
                                }
                            }
                        }

                        // Round & rules summary strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = sidePad, vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val headerInfo = if (state.winnerId != null) {
                                "Match complete · First to ${state.targetWins}"
                            } else {
                                "Round ${state.roundNumber} · First to ${state.targetWins} · Ties → 1st"
                            }
                            LightText(
                                text = headerInfo,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                        }

                        // Last round result / match winner banner
                        if (state.lastRoundBanner.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = sidePad, vertical = 3.dp)
                                    .background(
                                        if (state.winnerId != null) content else bg,
                                        RoundedCornerShape(5.dp),
                                    )
                                    .border(
                                        1.dp,
                                        content.copy(alpha = if (state.winnerId != null) 1f else 0.5f),
                                        RoundedCornerShape(5.dp),
                                    )
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = state.lastRoundBanner,
                                    fontSize = 12.sp,
                                    fontWeight = if (state.winnerId != null) FontWeight.Bold else FontWeight.Normal,
                                    color = if (state.winnerId != null) bg else content,
                                )
                            }
                        }

                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(start = sidePad),
                        ) {
                            Spacer(modifier = Modifier.height(4.dp))

                            state.players.forEachIndexed { idx, player ->
                                val rank = moodSwingsTurnRank(idx, state.firstPlayerIndex, state.players.size)
                                val rankLabel = ordinalLabel(rank + 1)
                                val isFirst = rank == 0
                                val hasHurtFeelings = state.hurtFeelingsPlayerId == player.id
                                val isMatchWinner = state.winnerId == player.id

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = sidePad, top = 7.dp, bottom = 7.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Tappable turn-order badge (tap to make this player go 1st)
                                        Box(
                                            modifier = Modifier
                                                .background(if (isFirst) content else bg, RoundedCornerShape(4.dp))
                                                .border(
                                                    1.dp,
                                                    content.copy(alpha = if (isFirst) 1f else 0.45f),
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .lightClickable {
                                                    runCatching { sealedActivity.vibrator.click() }
                                                    viewModel.setFirstPlayer(idx)
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = rankLabel,
                                                fontSize = 10.sp,
                                                fontWeight = if (isFirst) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isFirst) bg else content,
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(7.dp))

                                        // Player name + badges
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .lightClickable {
                                                    textFieldState.setTextAndPlaceCursorAtEnd(player.name)
                                                    viewModel.startEdit(
                                                        MoodSwingsEditAction.RenamePlayer(player.id, player.name),
                                                    )
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            LightText(
                                                text = player.name,
                                                variant = LightTextVariant.Subheading,
                                            )
                                            if (isMatchWinner) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                StatusPill("WIN", content, bg)
                                            } else if (hasHurtFeelings) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                StatusPill("HURT", content, bg)
                                            }
                                        }

                                        // Round win pips (● ● ○)
                                        val pips = buildString {
                                            repeat(state.targetWins) { i ->
                                                append(if (i < player.roundWins) "●" else "○")
                                                if (i < state.targetWins - 1) append(" ")
                                            }
                                        }
                                        Text(
                                            text = pips,
                                            fontSize = 13.sp,
                                            color = content,
                                            modifier = Modifier.padding(end = 10.dp),
                                        )

                                        // Current round mood total
                                        Text(
                                            text = "${player.currentRoundScore}",
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = content,
                                        )
                                    }

                                    if (state.winnerId == null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            MoodBtn("−3", Modifier.weight(1f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustRoundScore(player.id, -3)
                                            }
                                            MoodBtn("−1", Modifier.weight(1f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustRoundScore(player.id, -1)
                                            }
                                            MoodBtn("+1", Modifier.weight(1f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustRoundScore(player.id, 1)
                                            }
                                            MoodBtn("+3", Modifier.weight(1f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustRoundScore(player.id, 3)
                                            }
                                            MoodBtn("Set", Modifier.weight(1f)) {
                                                textFieldState.setTextAndPlaceCursorAtEnd(
                                                    if (player.currentRoundScore > 0) "${player.currentRoundScore}" else "",
                                                )
                                                viewModel.startEdit(
                                                    MoodSwingsEditAction.SetRoundScore(player.id, player.name),
                                                )
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = content.copy(alpha = 0.2f),
                                    modifier = Modifier.padding(end = sidePad),
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Primary action: Score Round or New Match
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = sidePad)
                                    .background(content, RoundedCornerShape(6.dp))
                                    .lightClickable {
                                        runCatching {
                                            sealedActivity.vibrator.vibrate(
                                                roundWinWaveform,
                                                LightVibrationUsage.Touch,
                                            )
                                        }
                                        if (state.winnerId != null) {
                                            viewModel.resetMatch()
                                        } else {
                                            viewModel.scoreRound()
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (state.winnerId != null) "Play Again" else "Score Round ${state.roundNumber}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = bg,
                                )
                            }

                            if (state.history.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(14.dp))
                                LightText(
                                    text = "ROUNDS",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(end = sidePad, bottom = 4.dp),
                                )
                                state.history.forEach { rec ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(end = sidePad, top = 4.dp, bottom = 4.dp),
                                    ) {
                                        val headline = buildString {
                                            append("R${rec.roundNumber}: ${rec.winnerName}")
                                            if (rec.wonByTiebreak) append(" (tiebreak)")
                                            rec.hurtFeelingsName?.let { append(" · Hurt: $it") }
                                        }
                                        LightText(text = headline, variant = LightTextVariant.Detail)
                                        LightText(
                                            text = rec.scoresSummary,
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                        )
                                    }
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = content.copy(alpha = 0.14f),
                                        modifier = Modifier.padding(end = sidePad),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        // Bottom bar: Undo & Reset
                        HorizontalDivider(thickness = 0.5.dp, color = content.copy(alpha = 0.25f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = sidePad, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MoodBtn(
                                label = "Undo Round",
                                modifier = Modifier.weight(1f),
                                enabled = canUndo,
                            ) {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.undoLastRound()
                            }
                            MoodBtn("Reset Match", Modifier.weight(1f)) {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.resetMatch()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(fg, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = bg,
        )
    }
}

@Composable
private fun MoodBtn(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val content = LightThemeTokens.colors.content
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = content.copy(alpha = if (enabled) 0.5f else 0.2f),
                shape = RoundedCornerShape(5.dp),
            )
            .then(if (enabled) Modifier.lightClickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            lighten = !enabled,
        )
    }
}

private fun ordinalLabel(n: Int): String = when (n) {
    1 -> "1st"
    2 -> "2nd"
    3 -> "3rd"
    else -> "${n}th"
}
