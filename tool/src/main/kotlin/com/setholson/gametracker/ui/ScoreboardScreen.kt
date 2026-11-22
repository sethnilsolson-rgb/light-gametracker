package com.setholson.gametracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
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

    fun applyPreset(preset: ScoreboardPreset) {
        updateAndPersist { current ->
            val targetCount = current.players.size.coerceAtLeast(preset.defaultPlayers).coerceAtMost(8)
            val players = (1..targetCount).map { idx ->
                val existingName = current.players.getOrNull(idx - 1)?.name ?: "Player $idx"
                ScorePlayer(id = idx, name = existingName, score = 0, lastDelta = null)
            }
            ScoreboardState(
                gameName = preset.label,
                targetScore = preset.targetScore,
                lowestWins = preset.lowestWins,
                players = players,
            )
        }
    }

    fun adjustScore(playerId: Int, delta: Int) {
        updateAndPersist { current ->
            current.copy(
                players = current.players.map { p ->
                    if (p.id == playerId) p.copy(score = p.score + delta, lastDelta = delta) else p
                }
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
            if (current.players.size >= 8) return@updateAndPersist current
            val nextId = (current.players.maxOfOrNull { it.id } ?: 0) + 1
            current.copy(
                players = current.players + ScorePlayer(id = nextId, name = "Player ${current.players.size + 1}")
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
        updateAndPersist { current ->
            current.copy(
                players = current.players.map { it.copy(score = 0, lastDelta = null) }
            )
        }
    }

    fun startEdit(action: ScoreboardEditAction) {
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
                            }
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
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, ScoreboardViewModel>(sealedActivity) {

    private val repository = GameRepository.getInstance {
        lightContext.buildDatabase(
            GameDatabase::class.java,
            GameRepository.DATABASE_NAME,
        ) {
            fallbackToDestructiveMigration(true)
        }
    }

    override val viewModelClass: Class<ScoreboardViewModel>
        get() = ScoreboardViewModel::class.java

    override fun createViewModel() = ScoreboardViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val editAction by viewModel.editAction.collectAsState()
        val nertzPlayerId by viewModel.nertzCalcPlayerId.collectAsState()
        val haptics = LocalHapticFeedback.current

        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val isNertzMode = state.gameName.contains("Nertz", ignoreCase = true)

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .swipeToBack {
                        when {
                            nertzPlayerId != null -> viewModel.openNertzCalc(null)
                            editAction != null -> viewModel.cancelEdit()
                            else -> goBack()
                        }
                    },
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
                        submitIcon = LightIcons.ACCEPT,
                        singleLine = true,
                        onSubmit = { seq -> viewModel.submitEdit(seq.toString()) },
                        onBack = { viewModel.cancelEdit() },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
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

                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.Text("Scoreboard"),
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
                                        .lightClickable { viewModel.applyPreset(preset) }
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
                                                        ScoreboardEditAction.RenamePlayer(player.id, player.name)
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
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.adjustScore(player.id, -2)
                                            }
                                            ScoreStepBtn("+1", Modifier.weight(0.9f)) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.adjustScore(player.id, 1)
                                            }
                                            ScoreStepBtn("±N", Modifier.weight(0.9f)) {
                                                textFieldState.setTextAndPlaceCursorAtEnd("")
                                                viewModel.startEdit(
                                                    ScoreboardEditAction.CustomDelta(player.id, player.name)
                                                )
                                            }
                                        } else {
                                            ScoreStepBtn("−5", Modifier.weight(1f)) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.adjustScore(player.id, -5)
                                            }
                                            ScoreStepBtn("−1", Modifier.weight(1f)) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.adjustScore(player.id, -1)
                                            }
                                            ScoreStepBtn("+1", Modifier.weight(1f)) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.adjustScore(player.id, 1)
                                            }
                                            ScoreStepBtn("+5", Modifier.weight(1f)) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.adjustScore(player.id, 5)
                                            }
                                            ScoreStepBtn("±N", Modifier.weight(1f)) {
                                                textFieldState.setTextAndPlaceCursorAtEnd("")
                                                viewModel.startEdit(
                                                    ScoreboardEditAction.CustomDelta(player.id, player.name)
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
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.resetScores()
                            }
                        }
                    }

                    // Nertz Round Calculator Overlay
                    nertzPlayerId?.let { pid ->
                        val target = state.players.find { it.id == pid }
                        if (target != null) {
                            NertzRoundOverlay(
                                player = target,
                                onApply = { played, left ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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

            // Cards played to center (+1 each)
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

            // Cards left in Nertz pile (-2 each)
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

            // Preview + Apply
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
