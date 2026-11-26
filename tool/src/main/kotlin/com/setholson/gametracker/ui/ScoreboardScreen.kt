package com.setholson.gametracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.setholson.gametracker.data.GameRepository
import com.setholson.gametracker.db.GameDatabase
import com.setholson.gametracker.model.ScorePlayer
import com.setholson.gametracker.model.ScoreboardPreset
import com.setholson.gametracker.model.ScoreboardState
import com.thelightphone.sdk.LightScreen
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScoreboardEditAction {
    data class RenamePlayer(val playerId: Int, val currentName: String) : ScoreboardEditAction
    data class CustomDelta(val playerId: Int, val playerName: String) : ScoreboardEditAction
}

class ScoreboardViewModel(
    private val repository: GameRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow(ScoreboardState())
    val state: StateFlow<ScoreboardState> = _state.asStateFlow()

    private val _editAction = MutableStateFlow<ScoreboardEditAction?>(null)
    val editAction: StateFlow<ScoreboardEditAction?> = _editAction.asStateFlow()

    private val _nertzCalcPlayerId = MutableStateFlow<Int?>(null)
    val nertzCalcPlayerId: StateFlow<Int?> = _nertzCalcPlayerId.asStateFlow()

    private val _showTableMenu = MutableStateFlow(false)
    val showTableMenu: StateFlow<Boolean> = _showTableMenu.asStateFlow()

    init {
        viewModelScope.launch {
            repository.loadScoreboardState()?.let { saved ->
                _state.value = saved
            }
        }
    }

    private fun updateAndPersist(transform: (ScoreboardState) -> ScoreboardState) {
        val next = transform(_state.value)
        _state.value = next
        viewModelScope.launch {
            repository.saveScoreboardState(next)
        }
    }

    fun toggleViewMode() {
        _showTableMenu.value = false
        updateAndPersist { current ->
            val nextTable = !current.isTableView
            // Tabletop mode supports up to 6 players cleanly
            val players = if (nextTable && current.players.size > 6) {
                current.players.take(6)
            } else {
                current.players
            }
            current.copy(isTableView = nextTable, players = players)
        }
    }

    fun setShowTableMenu(show: Boolean) {
        _showTableMenu.value = show
    }

    fun applyPreset(preset: ScoreboardPreset) {
        updateAndPersist { current ->
            val maxPlayers = if (current.isTableView) 6 else 8
            val targetCount = current.players.size.coerceAtLeast(preset.defaultPlayers).coerceAtMost(maxPlayers)
            val players = (1..targetCount).map { idx ->
                val existingName = current.players.getOrNull(idx - 1)?.name ?: "Player $idx"
                ScorePlayer(id = idx, name = existingName, score = 0, lastDelta = null)
            }
            ScoreboardState(
                gameName = preset.label,
                targetScore = preset.targetScore,
                lowestWins = preset.lowestWins,
                isTableView = current.isTableView,
                players = players,
            )
        }
        _showTableMenu.value = false
    }

    fun adjustScore(playerId: Int, delta: Int) {
        updateAndPersist { current ->
            current.copy(
                players = current.players.map { p ->
                    if (p.id == playerId) p.copy(score = p.score + delta, lastDelta = delta) else p
                },
            )
        }
    }

    fun openNertzCalc(playerId: Int?) {
        _nertzCalcPlayerId.value = playerId
    }

    fun applyNertzRound(playerId: Int, centerCardsPlayed: Int, nertzPileLeft: Int) {
        val delta = centerCardsPlayed - (nertzPileLeft * 2)
        adjustScore(playerId, delta)
        _nertzCalcPlayerId.value = null
    }

    fun addPlayer() {
        updateAndPersist { current ->
            val maxPlayers = if (current.isTableView) 6 else 8
            if (current.players.size >= maxPlayers) return@updateAndPersist current
            val nextId = (current.players.maxOfOrNull { it.id } ?: 0) + 1
            current.copy(
                players = current.players + ScorePlayer(id = nextId, name = "Player ${current.players.size + 1}"),
            )
        }
    }

    fun removeLastPlayer() {
        updateAndPersist { current ->
            if (current.players.size <= 2) return@updateAndPersist current
            current.copy(players = current.players.dropLast(1))
        }
    }

    fun resetScores() {
        _showTableMenu.value = false
        updateAndPersist { current ->
            current.copy(
                players = current.players.map { it.copy(score = 0, lastDelta = null) },
            )
        }
    }

    fun startEdit(action: ScoreboardEditAction) {
        _showTableMenu.value = false
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
            is ScoreboardEditAction.RenamePlayer -> {
                if (trimmed.isNotBlank()) {
                    updateAndPersist { current ->
                        current.copy(
                            players = current.players.map { p ->
                                if (p.id == action.playerId) p.copy(name = trimmed.take(20)) else p
                            },
                        )
                    }
                }
            }
            is ScoreboardEditAction.CustomDelta -> {
                val parsed = trimmed.toIntOrNull()
                if (parsed != null && parsed != 0) {
                    adjustScore(action.playerId, parsed)
                }
            }
        }
    }
}

class ScoreboardScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, ScoreboardViewModel>(sealedActivity) {

    private val repository = GameRepository.getInstance {
        lightContext.buildDatabase(
            GameDatabase::class.java,
            GameRepository.DATABASE_NAME,
        ) {
            fallbackToDestructiveMigration(true)
        }
    }

    private val isForegroundFlow = MutableStateFlow(true)

    override val viewModelClass: Class<ScoreboardViewModel>
        get() = ScoreboardViewModel::class.java

    override fun createViewModel() = ScoreboardViewModel(repository)

    override fun willShow() {
        super.willShow()
        isForegroundFlow.value = true
    }

    override fun willHide() {
        isForegroundFlow.value = false
        super.willHide()
    }

    override fun onAppPause() {
        isForegroundFlow.value = false
        super.onAppPause()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val editAction by viewModel.editAction.collectAsState()
        val nertzPlayerId by viewModel.nertzCalcPlayerId.collectAsState()
        val showTableMenu by viewModel.showTableMenu.collectAsState()
        val isForeground by isForegroundFlow.collectAsState()

        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val isNertzMode = state.gameName.contains("Nertz", ignoreCase = true)

        LightTheme(colors = themeColors) {
            val content = LightThemeTokens.colors.content
            val bg = LightThemeTokens.colors.background
            val sidePad = 1.5f.gridUnitsAsDp()

            val leaderScore = if (state.players.any { it.score != 0 }) {
                if (state.lowestWins) {
                    state.players.minOfOrNull { it.score }
                } else {
                    state.players.maxOfOrNull { it.score }
                }
            } else {
                null
            }

            val targetReached = state.targetScore?.let { target ->
                state.players.any { it.score >= target }
            } ?: false

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .then(
                        if (editAction == null) {
                            Modifier.swipeToBack {
                                when {
                                    nertzPlayerId != null -> viewModel.openNertzCalc(null)
                                    showTableMenu -> viewModel.setShowTableMenu(false)
                                    else -> goBack()
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (editAction != null) {
                    val title = when (val act = editAction) {
                        is ScoreboardEditAction.RenamePlayer -> "Rename ${act.currentName}"
                        is ScoreboardEditAction.CustomDelta -> "Add/Sub Points (${act.playerName})"
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
                    // Tabletop Opposite-Facing Mode (2-6 players facing outward around the table)
                    val dividerColor = content.copy(alpha = 0.28f)
                    val topCount = state.players.size / 2
                    val topPlayers = state.players.take(topCount)
                    val bottomPlayers = state.players.drop(topCount)
                    val compact = state.players.size > 2
                    val extraCompact = state.players.size > 4

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Row (rotated 180 degrees)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            topPlayers.forEachIndexed { idx, player ->
                                if (idx > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .background(dividerColor),
                                    )
                                }
                                val isLead = leaderScore != null && player.score == leaderScore
                                val badge = when {
                                    isLead && targetReached -> "WIN"
                                    isLead -> "LEAD"
                                    else -> null
                                }
                                ScoreboardTableZone(
                                    player = player,
                                    badge = badge,
                                    isNertz = isNertzMode,
                                    compact = compact,
                                    extraCompact = extraCompact,
                                    rotated = true,
                                    onAdjust = { d ->
                                        runCatching { sealedActivity.vibrator.click() }
                                        viewModel.adjustScore(player.id, d)
                                    },
                                    onCustomOrNertz = {
                                        if (isNertzMode) {
                                            viewModel.openNertzCalc(player.id)
                                        } else {
                                            textFieldState.setTextAndPlaceCursorAtEnd("")
                                            viewModel.startEdit(
                                                ScoreboardEditAction.CustomDelta(player.id, player.name),
                                            )
                                        }
                                    },
                                    onRename = {
                                        textFieldState.setTextAndPlaceCursorAtEnd(player.name)
                                        viewModel.startEdit(
                                            ScoreboardEditAction.RenamePlayer(player.id, player.name),
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        }

                        // Slim Center Strip: Back | Game Name (tap for presets/players) | List
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HorizontalDivider(thickness = 1.dp, color = dividerColor)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .background(bg)
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .lightClickable { goBack() }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    LightText(text = "←", variant = LightTextVariant.Detail)
                                }
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, content.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                                        .lightClickable { viewModel.setShowTableMenu(true) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = "${state.gameName} ▾",
                                        fontSize = 11.sp,
                                        color = content,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .lightClickable {
                                            runCatching { sealedActivity.vibrator.click() }
                                            viewModel.toggleViewMode()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    LightText(text = "List", variant = LightTextVariant.Detail)
                                }
                            }
                            HorizontalDivider(thickness = 1.dp, color = dividerColor)
                        }

                        // Bottom Row (upright 0 degrees)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            bottomPlayers.forEachIndexed { idx, player ->
                                if (idx > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .background(dividerColor),
                                    )
                                }
                                val isLead = leaderScore != null && player.score == leaderScore
                                val badge = when {
                                    isLead && targetReached -> "WIN"
                                    isLead -> "LEAD"
                                    else -> null
                                }
                                ScoreboardTableZone(
                                    player = player,
                                    badge = badge,
                                    isNertz = isNertzMode,
                                    compact = compact,
                                    extraCompact = extraCompact,
                                    rotated = false,
                                    onAdjust = { d ->
                                        runCatching { sealedActivity.vibrator.click() }
                                        viewModel.adjustScore(player.id, d)
                                    },
                                    onCustomOrNertz = {
                                        if (isNertzMode) {
                                            viewModel.openNertzCalc(player.id)
                                        } else {
                                            textFieldState.setTextAndPlaceCursorAtEnd("")
                                            viewModel.startEdit(
                                                ScoreboardEditAction.CustomDelta(player.id, player.name),
                                            )
                                        }
                                    },
                                    onRename = {
                                        textFieldState.setTextAndPlaceCursorAtEnd(player.name)
                                        viewModel.startEdit(
                                            ScoreboardEditAction.RenamePlayer(player.id, player.name),
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                    }

                    CornerBatteryBadge(
                        isForeground = isForeground,
                        showBattery = !showTableMenu && nertzPlayerId == null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 8.dp),
                    )

                    // Table Options Overlay (Game Preset, +/- Player, Reset, List View)
                    if (showTableMenu) {
                        ScoreboardTableMenuOverlay(
                            state = state,
                            onDismiss = { viewModel.setShowTableMenu(false) },
                            onSelectPreset = { p ->
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.applyPreset(p)
                            },
                            onAddPlayer = {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.addPlayer()
                            },
                            onRemovePlayer = {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.removeLastPlayer()
                            },
                            onReset = {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.resetScores()
                            },
                            onSwitchToList = { viewModel.toggleViewMode() },
                        )
                    }
                } else {
                    // Single-Scorekeeper Upright List Mode
                    Column(modifier = Modifier.fillMaxSize()) {
                        BatteryTopBar(
                            isForeground = isForeground,
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.Text("Scoreboard"),
                            rightButton = LightBarButton.Text(
                                text = "Table",
                                onClick = {
                                    runCatching { sealedActivity.vibrator.click() }
                                    viewModel.toggleViewMode()
                                },
                            ),
                            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                        )

                        // Preset Game Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = sidePad, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ScoreboardPreset.entries.forEach { preset ->
                                val selected = state.gameName == preset.label
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (selected) content else bg,
                                            RoundedCornerShape(5.dp),
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = content.copy(alpha = if (selected) 1f else 0.45f),
                                            shape = RoundedCornerShape(5.dp),
                                        )
                                        .lightClickable {
                                            runCatching { sealedActivity.vibrator.click() }
                                            viewModel.applyPreset(preset)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        text = preset.label,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) bg else content,
                                    )
                                }
                            }
                        }

                        // Game info strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = sidePad, vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ruleSummary = buildString {
                                append(if (state.lowestWins) "Lowest score wins" else "Highest score wins")
                                state.targetScore?.let { append(" • Goal: $it") }
                                if (isNertzMode) append(" • +1 played, −2 Nertz left")
                            }
                            LightText(text = ruleSummary, variant = LightTextVariant.Detail, lighten = true)
                        }

                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(start = sidePad),
                        ) {
                            Spacer(modifier = Modifier.height(4.dp))

                            state.players.forEach { player ->
                                val isLead = leaderScore != null && player.score == leaderScore
                                val statusBadge = when {
                                    isLead && targetReached -> "WINNER"
                                    isLead -> "LEAD"
                                    else -> null
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = sidePad, top = 7.dp, bottom = 7.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .lightClickable {
                                                    textFieldState.setTextAndPlaceCursorAtEnd(player.name)
                                                    viewModel.startEdit(
                                                        ScoreboardEditAction.RenamePlayer(player.id, player.name),
                                                    )
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            LightText(
                                                text = player.name,
                                                variant = LightTextVariant.Subheading,
                                            )
                                            if (statusBadge != null) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .background(content, RoundedCornerShape(3.dp))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                                ) {
                                                    Text(
                                                        text = statusBadge,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = bg,
                                                    )
                                                }
                                            }
                                        }

                                        player.lastDelta?.let { d ->
                                            val sign = if (d > 0) "+$d" else "$d"
                                            LightText(
                                                text = sign,
                                                variant = LightTextVariant.Detail,
                                                lighten = true,
                                                modifier = Modifier.padding(end = 8.dp),
                                            )
                                        }

                                        Text(
                                            text = "${player.score}",
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = content,
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (isNertzMode) {
                                            ScoreStepBtn("Nertz Round", Modifier.weight(1.9f)) {
                                                viewModel.openNertzCalc(player.id)
                                            }
                                            ScoreStepBtn("−2", Modifier.weight(0.9f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustScore(player.id, -2)
                                            }
                                            ScoreStepBtn("+1", Modifier.weight(0.9f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustScore(player.id, 1)
                                            }
                                            ScoreStepBtn("±N", Modifier.weight(0.9f)) {
                                                textFieldState.setTextAndPlaceCursorAtEnd("")
                                                viewModel.startEdit(
                                                    ScoreboardEditAction.CustomDelta(player.id, player.name),
                                                )
                                            }
                                        } else {
                                            ScoreStepBtn("−5", Modifier.weight(1f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustScore(player.id, -5)
                                            }
                                            ScoreStepBtn("−1", Modifier.weight(1f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustScore(player.id, -1)
                                            }
                                            ScoreStepBtn("+1", Modifier.weight(1f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustScore(player.id, 1)
                                            }
                                            ScoreStepBtn("+5", Modifier.weight(1f)) {
                                                runCatching { sealedActivity.vibrator.click() }
                                                viewModel.adjustScore(player.id, 5)
                                            }
                                            ScoreStepBtn("±N", Modifier.weight(1f)) {
                                                textFieldState.setTextAndPlaceCursorAtEnd("")
                                                viewModel.startEdit(
                                                    ScoreboardEditAction.CustomDelta(player.id, player.name),
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
                            LightText(
                                text = "Tap a player name to rename • Tap ±N for custom points.",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(end = sidePad, bottom = 12.dp),
                            )
                        }

                        // Bottom controls bar
                        HorizontalDivider(thickness = 0.5.dp, color = content.copy(alpha = 0.25f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = sidePad, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ScoreStepBtn("+ Player", Modifier.weight(1f)) { viewModel.addPlayer() }
                            ScoreStepBtn("− Player", Modifier.weight(1f)) { viewModel.removeLastPlayer() }
                            ScoreStepBtn("Reset", Modifier.weight(1f)) {
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.resetScores()
                            }
                        }
                    }
                }

                // Nertz Round Calculator Overlay (works in both Table and List view)
                nertzPlayerId?.let { pid ->
                    val target = state.players.find { it.id == pid }
                    if (target != null) {
                        NertzRoundOverlay(
                            player = target,
                            onApply = { played, left ->
                                runCatching { sealedActivity.vibrator.click() }
                                viewModel.applyNertzRound(pid, played, left)
                            },
                            onDismiss = { viewModel.openNertzCalc(null) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScoreboardTableZone(
    player: ScorePlayer,
    badge: String?,
    isNertz: Boolean,
    compact: Boolean,
    extraCompact: Boolean,
    rotated: Boolean,
    onAdjust: (Int) -> Unit,
    onCustomOrNertz: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = LightThemeTokens.colors.content
    val bg = LightThemeTokens.colors.background

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = if (rotated) 180f else 0f }
            .then(
                if (badge == "WIN") Modifier.border(2.5.dp, content) else Modifier,
            ),
    ) {
        // Left (-1 / long -5) and Right (+1 / long +5) touch zones
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAdjust(if (isNertz) -2 else -1) },
                        onLongClick = { onAdjust(-5) },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "−",
                    fontSize = if (extraCompact) 16.sp else if (compact) 20.sp else 28.sp,
                    color = content.copy(alpha = 0.28f),
                    modifier = Modifier.padding(start = if (extraCompact) 5.dp else if (compact) 8.dp else 18.dp),
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
                        onLongClick = { onAdjust(5) },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "+",
                    fontSize = if (extraCompact) 16.sp else if (compact) 20.sp else 28.sp,
                    color = content.copy(alpha = 0.28f),
                    modifier = Modifier.padding(end = if (extraCompact) 5.dp else if (compact) 8.dp else 18.dp),
                )
            }
        }

        // Top strip: Name + LEAD/WIN badge + last delta
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (compact) 5.dp else 10.dp, start = 4.dp, end = 4.dp)
                .lightClickable(onClick = onRename)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = player.name,
                fontSize = if (extraCompact) 10.sp else if (compact) 12.sp else 14.sp,
                color = content.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .background(content, RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = badge,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = bg,
                    )
                }
            }
            player.lastDelta?.let { d ->
                val sign = if (d > 0) "+$d" else "$d"
                Text(
                    text = sign,
                    fontSize = if (extraCompact) 9.sp else 11.sp,
                    color = content.copy(alpha = 0.7f),
                )
            }
        }

        // Giant Score Number
        Text(
            text = "${player.score}",
            fontSize = if (extraCompact) 36.sp else if (compact) 50.sp else 72.sp,
            fontWeight = FontWeight.Light,
            color = content,
            modifier = Modifier.align(Alignment.Center),
        )

        // Bottom pill: Nertz calculator or ±N custom delta
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (compact) 6.dp else 10.dp)
                .border(1.dp, content.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .lightClickable(onClick = onCustomOrNertz)
                .padding(horizontal = if (extraCompact) 6.dp else 10.dp, vertical = 3.dp),
        ) {
            Text(
                text = if (isNertz) "Nertz" else "±N",
                fontSize = if (extraCompact) 10.sp else 11.sp,
                color = content,
            )
        }
    }
}

@Composable
private fun ScoreboardTableMenuOverlay(
    state: ScoreboardState,
    onDismiss: () -> Unit,
    onSelectPreset: (ScoreboardPreset) -> Unit,
    onAddPlayer: () -> Unit,
    onRemovePlayer: () -> Unit,
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
                LightText(text = "Table Scoreboard", variant = LightTextVariant.Subheading)
                Box(
                    modifier = Modifier
                        .border(1.dp, content.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                        .lightClickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    LightText(text = "Done", variant = LightTextVariant.Detail)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScoreStepBtn("+ Player (${state.players.size})", Modifier.weight(1f), onClick = onAddPlayer)
                ScoreStepBtn("− Player", Modifier.weight(1f), onClick = onRemovePlayer)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScoreStepBtn("List View", Modifier.weight(1f), onClick = onSwitchToList)
                ScoreStepBtn("Reset Scores", Modifier.weight(1f), onClick = onReset)
            }

            Spacer(modifier = Modifier.height(14.dp))
            LightText(text = "GAME PRESET", variant = LightTextVariant.Detail, lighten = true)
            Spacer(modifier = Modifier.height(6.dp))

            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ScoreboardPreset.entries.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pair.forEach { preset ->
                            val selected = state.gameName == preset.label
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (selected) content else bg, RoundedCornerShape(5.dp))
                                    .border(
                                        1.dp,
                                        content.copy(alpha = if (selected) 1f else 0.45f),
                                        RoundedCornerShape(5.dp),
                                    )
                                    .lightClickable { onSelectPreset(preset) }
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = preset.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) bg else content,
                                )
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NertzRoundOverlay(
    player: ScorePlayer,
    onApply: (centerPlayed: Int, nertzLeft: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val bg = LightThemeTokens.colors.background
    val content = LightThemeTokens.colors.content
    var centerPlayed by remember(player.id) { mutableIntStateOf(0) }
    var nertzLeft by remember(player.id) { mutableIntStateOf(0) }
    val roundTotal = centerPlayed - (nertzLeft * 2)

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
                LightText(text = "${player.name} — Nertz Round", variant = LightTextVariant.Subheading)
                Box(
                    modifier = Modifier
                        .border(1.dp, content.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                        .lightClickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    LightText(text = "Cancel", variant = LightTextVariant.Detail)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LightText(text = "CARDS IN CENTER (+1 EACH)", variant = LightTextVariant.Detail, lighten = true)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScoreStepBtn("−5", Modifier.weight(1f)) { centerPlayed = (centerPlayed - 5).coerceAtLeast(0) }
                ScoreStepBtn("−1", Modifier.weight(1f)) { centerPlayed = (centerPlayed - 1).coerceAtLeast(0) }
                Text(
                    text = "$centerPlayed",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    color = content,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                ScoreStepBtn("+1", Modifier.weight(1f)) { centerPlayed += 1 }
                ScoreStepBtn("+5", Modifier.weight(1f)) { centerPlayed += 5 }
            }

            Spacer(modifier = Modifier.height(18.dp))

            LightText(text = "NERTZ PILE REMAINING (−2 EACH)", variant = LightTextVariant.Detail, lighten = true)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScoreStepBtn("0", Modifier.weight(1f)) { nertzLeft = 0 }
                ScoreStepBtn("−1", Modifier.weight(1f)) { nertzLeft = (nertzLeft - 1).coerceAtLeast(0) }
                Text(
                    text = "$nertzLeft",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    color = content,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                ScoreStepBtn("+1", Modifier.weight(1f)) { nertzLeft = (nertzLeft + 1).coerceAtMost(13) }
                ScoreStepBtn("+3", Modifier.weight(1f)) { nertzLeft = (nertzLeft + 3).coerceAtMost(13) }
            }

            Spacer(modifier = Modifier.height(22.dp))

            val sign = if (roundTotal >= 0) "+$roundTotal" else "$roundTotal"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(content, RoundedCornerShape(6.dp))
                    .lightClickable { onApply(centerPlayed, nertzLeft) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Add Round Score ($sign)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = bg,
                )
            }
        }
    }
}

@Composable
private fun ScoreStepBtn(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = LightThemeTokens.colors.content.copy(alpha = 0.5f),
                shape = RoundedCornerShape(5.dp),
            )
            .lightClickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Detail)
    }
}
