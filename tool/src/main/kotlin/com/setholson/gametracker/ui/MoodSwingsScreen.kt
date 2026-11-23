package com.setholson.gametracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.setholson.gametracker.data.GameRepository
import com.setholson.gametracker.db.GameDatabase
import com.setholson.gametracker.model.MoodSwingsMode
import com.setholson.gametracker.model.MoodSwingsPlayer
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _editAction = MutableStateFlow<MoodSwingsEditAction?>(null)
    val editAction: StateFlow<MoodSwingsEditAction?> = _editAction.asStateFlow()

    private val _showMenu = MutableStateFlow(false)
    val showMenu: StateFlow<Boolean> = _showMenu.asStateFlow()

    private val _tableBanner = MutableStateFlow<String?>(null)
    val tableBanner: StateFlow<String?> = _tableBanner.asStateFlow()

    private val _deltas = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val deltas: StateFlow<Map<Int, Int>> = _deltas.asStateFlow()

    private var bannerJob: Job? = null
    private val deltaClearJobs = mutableMapOf<Int, Job>()

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
            _canUndo.value = nextStack.isNotEmpty()
        }
        _state.value = next
        viewModelScope.launch {
            repository.saveMoodSwingsState(next)
        }
    }

    private fun flashTableBanner(message: String, durationMs: Long = 3800L) {
        _tableBanner.value = message
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(durationMs)
            _tableBanner.value = null
        }
    }

    fun dismissTableBanner() {
        bannerJob?.cancel()
        _tableBanner.value = null
    }

    fun toggleViewMode() {
        _showMenu.value = false
        updateAndPersist { it.copy(isTableView = !it.isTableView) }
    }

    fun setShowMenu(show: Boolean) {
        _showMenu.value = show
    }

    fun selectMode(mode: MoodSwingsMode) {
        val current = _state.value
        val existingNames = current.players.associate { it.id to it.name }
        _undoStack.value = emptyList()
        _canUndo.value = false
        _deltas.value = emptyMap()
        _tableBanner.value = null
        updateAndPersist {
            MoodSwingsState(
                mode = mode,
                isTableView = current.isTableView,
                players = defaultMoodSwingsPlayers(mode, existingNames),
            )
        }
    }

    fun adjustRoundScore(playerId: Int, delta: Int) {
        if (_state.value.winnerId != null) return
        updateAndPersist { current ->
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
        val nextDelta = (_deltas.value[playerId] ?: 0) + delta
        _deltas.value = _deltas.value + (playerId to nextDelta)
        deltaClearJobs[playerId]?.cancel()
        deltaClearJobs[playerId] = viewModelScope.launch {
            delay(2000L)
            _deltas.value = _deltas.value - playerId
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
        val banner = _state.value.lastRoundBanner
        if (banner.isNotBlank()) {
            flashTableBanner(banner, durationMs = if (_state.value.winnerId != null) 6000L else 3800L)
        }
    }

    fun clearBoardScores() {
        updateAndPersist(recordUndo = true) { current ->
            current.copy(players = current.players.map { it.copy(currentRoundScore = 0) })
        }
        _deltas.value = emptyMap()
        _showMenu.value = false
    }

    fun undoLastRound() {
        val stack = _undoStack.value
        val previous = stack.firstOrNull() ?: return
        val remaining = stack.drop(1)
        _undoStack.value = remaining
        _canUndo.value = remaining.isNotEmpty()
        _state.value = previous
        _showMenu.value = false
        viewModelScope.launch {
            repository.saveMoodSwingsState(previous)
        }
    }

    fun resetMatch() {
        val current = _state.value
        val existingNames = current.players.associate { it.id to it.name }
        _undoStack.value = emptyList()
        _canUndo.value = false
        _deltas.value = emptyMap()
        _tableBanner.value = null
        _showMenu.value = false
        updateAndPersist {
            MoodSwingsState(
                mode = current.mode,
                isTableView = current.isTableView,
                players = defaultMoodSwingsPlayers(current.mode, existingNames).mapIndexed { i, p ->
                    val preserved = current.players.getOrNull(i)?.name ?: p.name
                    p.copy(name = preserved)
                },
            )
        }
    }

    fun startEdit(action: MoodSwingsEditAction) {
        _showMenu.value = false
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
        val showMenu by viewModel.showMenu.collectAsState()
        val tableBanner by viewModel.tableBanner.collectAsState()
        val deltas by viewModel.deltas.collectAsState()

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
                            Modifier.swipeToBack {
                                if (showMenu) viewModel.setShowMenu(false) else goBack()
                            }
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
                } else if (state.isTableView) {
                    // Tabletop Opposite-Facing Mode (like MTG)
                    val dividerColor = content.copy(alpha = 0.28f)
                    val topCount = state.players.size / 2
                    val topPlayers = state.players.take(topCount)
                    val bottomPlayers = state.players.drop(topCount)
                    val compact = state.players.size > 2

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Row (rotated 180 degrees facing across the table)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            topPlayers.forEachIndexed { localIdx, player ->
                                if (localIdx > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .background(dividerColor),
                                    )
                                }
                                val rank = moodSwingsTurnRank(localIdx, state.firstPlayerIndex, state.players.size)
                                MoodSwingsTableZone(
                                    player = player,
                                    targetWins = state.targetWins,
                                    turnRank = rank,
                                    delta = deltas[player.id],
                                    hasHurtFeelings = state.hurtFeelingsPlayerId == player.id,
                                    isMatchWinner = state.winnerId == player.id,
                                    compact = compact,
                                    rotated = true,
                                    onAdjust = { d ->
                                        runCatching { sealedActivity.vibrator.click() }
                                        viewModel.adjustRoundScore(player.id, d)
                                    },
                                    onSetFirst = {
                                        runCatching { sealedActivity.vibrator.click() }
                                        viewModel.setFirstPlayer(localIdx)
                                    },
                                    onRename = {
                                        textFieldState.setTextAndPlaceCursorAtEnd(player.name)
                                        viewModel.startEdit(
                                            MoodSwingsEditAction.RenamePlayer(player.id, player.name),
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        }

                        // Slim Center Strip (Back, Options, Score Round / Play Again, List Toggle)
                        MoodSwingsCenterStrip(
                            roundNumber = state.roundNumber,
                            matchFinished = state.winnerId != null,
                            banner = tableBanner,
                            onDismissBanner = { viewModel.dismissTableBanner() },
                            onBack = { goBack() },
                            onMenu = { viewModel.setShowMenu(true) },
                            onScoreOrReset = {
                                runCatching {
                                    sealedActivity.vibrator.vibrate(roundWinWaveform, LightVibrationUsage.Touch)
                                }
                                if (state.winnerId != null) viewModel.resetMatch() else viewModel.scoreRound()
                            },
                            onSwitchToList = {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.toggleViewMode()
                            },
                        )

                        // Bottom Row (upright 0 degrees)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            bottomPlayers.forEachIndexed { localIdx, player ->
                                val globalIdx = topCount + localIdx
                                if (localIdx > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .background(dividerColor),
                                    )
                                }
                                val rank = moodSwingsTurnRank(globalIdx, state.firstPlayerIndex, state.players.size)
                                MoodSwingsTableZone(
                                    player = player,
                                    targetWins = state.targetWins,
                                    turnRank = rank,
                                    delta = deltas[player.id],
                                    hasHurtFeelings = state.hurtFeelingsPlayerId == player.id,
                                    isMatchWinner = state.winnerId == player.id,
                                    compact = compact,
                                    rotated = false,
                                    onAdjust = { d ->
                                        runCatching { sealedActivity.vibrator.click() }
                                        viewModel.adjustRoundScore(player.id, d)
                                    },
                                    onSetFirst = {
                                        runCatching { sealedActivity.vibrator.click() }
                                        viewModel.setFirstPlayer(globalIdx)
                                    },
                                    onRename = {
                                        textFieldState.setTextAndPlaceCursorAtEnd(player.name)
                                        viewModel.startEdit(
                                            MoodSwingsEditAction.RenamePlayer(player.id, player.name),
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                    }

                    // Tabletop Menu Overlay (Mode, Undo, Zero Scores, Reset, History)
                    if (showMenu) {
                        MoodSwingsMenuOverlay(
                            state = state,
                            canUndo = canUndo,
                            onDismiss = { viewModel.setShowMenu(false) },
                            onSelectMode = { m ->
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.selectMode(m)
                            },
                            onUndo = {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.undoLastRound()
                            },
                            onZeroScores = {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.clearBoardScores()
                            },
                            onReset = {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.resetMatch()
                            },
                            onSwitchToList = { viewModel.toggleViewMode() },
                        )
                    }
                } else {
                    // Single-Scorekeeper Upright List Mode
                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.Text("Mood Swings"),
                            rightButton = LightBarButton.Text(
                                text = "Table",
                                onClick = {
                                    runCatching { sealedActivity.vibrator.click() }
                                    viewModel.toggleViewMode()
                                },
                            ),
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

                                        Text(
                                            text = buildWinPips(player.roundWins, state.targetWins),
                                            fontSize = 13.sp,
                                            color = content,
                                            modifier = Modifier.padding(end = 10.dp),
                                        )

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

                        HorizontalDivider(thickness = 0.5.dp, color = content.copy(alpha = 0.25f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = sidePad, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MoodBtn(
                                label = "Undo",
                                modifier = Modifier.weight(1f),
                                enabled = canUndo,
                            ) {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.undoLastRound()
                            }
                            MoodBtn("0 Scores", Modifier.weight(1f)) {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.clearBoardScores()
                            }
                            MoodBtn("Reset", Modifier.weight(1f)) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoodSwingsTableZone(
    player: MoodSwingsPlayer,
    targetWins: Int,
    turnRank: Int,
    delta: Int?,
    hasHurtFeelings: Boolean,
    isMatchWinner: Boolean,
    compact: Boolean,
    rotated: Boolean,
    onAdjust: (Int) -> Unit,
    onSetFirst: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = LightThemeTokens.colors.content
    val bg = LightThemeTokens.colors.background
    val isFirst = turnRank == 0

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = if (rotated) 180f else 0f }
            .then(
                when {
                    isMatchWinner -> Modifier.border(2.5.dp, content)
                    isFirst -> Modifier.border(1.5.dp, content.copy(alpha = 0.7f))
                    else -> Modifier
                },
            ),
    ) {
        // Left (-1 / long -3) and Right (+1 / long +3) full-area touch zones
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAdjust(-1) },
                        onLongClick = { onAdjust(-3) },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "−",
                    fontSize = if (compact) 20.sp else 28.sp,
                    color = content.copy(alpha = 0.3f),
                    modifier = Modifier.padding(start = if (compact) 8.dp else 18.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAdjust(1) },
                        onLongClick = { onAdjust(3) },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "+",
                    fontSize = if (compact) 20.sp else 28.sp,
                    color = content.copy(alpha = 0.3f),
                    modifier = Modifier.padding(end = if (compact) 8.dp else 18.dp),
                )
            }
        }

        // Top strip: Turn-order pill + tappable Name + HURT/WIN badge + live delta
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (compact) 6.dp else 10.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(if (isFirst) content else bg, RoundedCornerShape(3.dp))
                    .border(1.dp, content.copy(alpha = if (isFirst) 1f else 0.5f), RoundedCornerShape(3.dp))
                    .lightClickable(onClick = onSetFirst)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = ordinalLabel(turnRank + 1),
                    fontSize = 9.sp,
                    fontWeight = if (isFirst) FontWeight.Bold else FontWeight.Normal,
                    color = if (isFirst) bg else content,
                )
            }
            Text(
                text = player.name,
                fontSize = if (compact) 12.sp else 14.sp,
                color = content.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.lightClickable(onClick = onRename),
            )
            if (isMatchWinner) {
                StatusPill("WIN", content, bg)
            } else if (hasHurtFeelings) {
                StatusPill("HURT", content, bg)
            }
            if (delta != null && delta != 0) {
                val sign = if (delta > 0) "+$delta" else "$delta"
                Text(
                    text = sign,
                    fontSize = if (compact) 11.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = content.copy(alpha = 0.85f),
                )
            }
        }

        // Giant Mood Score Total in center
        Text(
            text = "${player.currentRoundScore}",
            fontSize = if (compact) 52.sp else 74.sp,
            fontWeight = FontWeight.Light,
            color = content,
            modifier = Modifier.align(Alignment.Center),
        )

        // Bottom strip: Round win pips (● ● ○)
        Text(
            text = buildWinPips(player.roundWins, targetWins),
            fontSize = if (compact) 15.sp else 19.sp,
            color = content,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (compact) 8.dp else 12.dp),
        )
    }
}

@Composable
private fun MoodSwingsCenterStrip(
    roundNumber: Int,
    matchFinished: Boolean,
    banner: String?,
    onDismissBanner: () -> Unit,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onScoreOrReset: () -> Unit,
    onSwitchToList: () -> Unit,
) {
    val content = LightThemeTokens.colors.content
    val bg = LightThemeTokens.colors.background
    val lineColor = content.copy(alpha = 0.28f)

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = lineColor)
        if (banner != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(content)
                    .lightClickable(onClick = onDismissBanner)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = banner,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = bg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(bg)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .lightClickable(onClick = onBack)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    LightText(text = "←", variant = LightTextVariant.Detail)
                }
                Box(
                    modifier = Modifier
                        .lightClickable(onClick = onMenu)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    LightText(text = "≡", variant = LightTextVariant.Detail)
                }
                Box(
                    modifier = Modifier
                        .background(content, RoundedCornerShape(4.dp))
                        .lightClickable(onClick = onScoreOrReset)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = if (matchFinished) "Play Again" else "Score R$roundNumber",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = bg,
                    )
                }
                Box(
                    modifier = Modifier
                        .lightClickable(onClick = onSwitchToList)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    LightText(text = "List", variant = LightTextVariant.Detail)
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = lineColor)
    }
}

@Composable
private fun MoodSwingsMenuOverlay(
    state: MoodSwingsState,
    canUndo: Boolean,
    onDismiss: () -> Unit,
    onSelectMode: (MoodSwingsMode) -> Unit,
    onUndo: () -> Unit,
    onZeroScores: () -> Unit,
    onReset: () -> Unit,
    onSwitchToList: () -> Unit,
) {
    val content = LightThemeTokens.colors.content
    val bg = LightThemeTokens.colors.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg.copy(alpha = 0.97f))
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LightText(text = "Mood Swings", variant = LightTextVariant.Subheading)
                Box(
                    modifier = Modifier
                        .border(1.dp, content.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                        .lightClickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    LightText(text = "Done", variant = LightTextVariant.Detail)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            LightText(text = "PLAYERS", variant = LightTextVariant.Detail, lighten = true)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MoodSwingsMode.entries.forEach { m ->
                    val sel = state.mode == m
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (sel) content else bg, RoundedCornerShape(5.dp))
                            .border(1.dp, content.copy(alpha = if (sel) 1f else 0.45f), RoundedCornerShape(5.dp))
                            .lightClickable { onSelectMode(m) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = m.label,
                            fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) bg else content,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MoodBtn("Undo Round", Modifier.weight(1f), enabled = canUndo, onClick = onUndo)
                MoodBtn("0 Scores", Modifier.weight(1f), onClick = onZeroScores)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MoodBtn("List View", Modifier.weight(1f), onClick = onSwitchToList)
                MoodBtn("Reset Match", Modifier.weight(1f), onClick = onReset)
            }

            if (state.history.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                LightText(text = "ROUNDS", variant = LightTextVariant.Detail, lighten = true)
                Spacer(modifier = Modifier.height(4.dp))
                LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    state.history.forEach { rec ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            val headline = buildString {
                                append("R${rec.roundNumber}: ${rec.winnerName}")
                                if (rec.wonByTiebreak) append(" (tiebreak)")
                                rec.hurtFeelingsName?.let { append(" · Hurt: $it") }
                            }
                            LightText(text = headline, variant = LightTextVariant.Detail)
                            LightText(text = rec.scoresSummary, variant = LightTextVariant.Detail, lighten = true)
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = content.copy(alpha = 0.16f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .background(fg, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
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
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            lighten = !enabled,
        )
    }
}

private fun buildWinPips(wins: Int, targetWins: Int): String = buildString {
    repeat(targetWins) { i ->
        append(if (i < wins) "●" else "○")
        if (i < targetWins - 1) append(" ")
    }
}

private fun ordinalLabel(n: Int): String = when (n) {
    1 -> "1st"
    2 -> "2nd"
    3 -> "3rd"
    else -> "${n}th"
}
