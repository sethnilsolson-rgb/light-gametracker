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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.setholson.gametracker.data.GameRepository
import com.setholson.gametracker.db.GameDatabase
import com.setholson.gametracker.model.DayNightState
import com.setholson.gametracker.model.MtgPlayerState
import com.setholson.gametracker.model.MtgTableState
import com.setholson.gametracker.model.defaultMtgPlayers
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
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
import kotlinx.coroutines.launch
import kotlin.random.Random

class MtgTrackerViewModel(
    private val repository: GameRepository,
    presetPlayers: Int?,
    presetLife: Int?,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow(
        MtgTableState(
            playerCount = (presetPlayers ?: 2).coerceIn(2, 6),
            startingLife = presetLife ?: 20,
            players = defaultMtgPlayers(presetPlayers ?: 2, presetLife ?: 20),
        )
    )
    val state: StateFlow<MtgTableState> = _state.asStateFlow()

    private val _deltas = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val deltas: StateFlow<Map<Int, Int>> = _deltas.asStateFlow()

    private val _showMenu = MutableStateFlow(false)
    val showMenu: StateFlow<Boolean> = _showMenu.asStateFlow()

    private val _commanderModalPlayerId = MutableStateFlow<Int?>(null)
    val commanderModalPlayerId: StateFlow<Int?> = _commanderModalPlayerId.asStateFlow()

    private val _renamingPlayerId = MutableStateFlow<Int?>(null)
    val renamingPlayerId: StateFlow<Int?> = _renamingPlayerId.asStateFlow()

    private val _cmdDeductsLife = MutableStateFlow(true)
    val cmdDeductsLife: StateFlow<Boolean> = _cmdDeductsLife.asStateFlow()

    // Roulette highlight state while deciding who goes first
    private val _pickingHighlightPlayerId = MutableStateFlow<Int?>(null)
    val pickingHighlightPlayerId: StateFlow<Int?> = _pickingHighlightPlayerId.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val undoStack = ArrayDeque<MtgTableState>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val deltaClearJobs = mutableMapOf<Int, Job>()
    private var pickFirstJob: Job? = null
    private var bannerJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = repository.loadMtgState()
            if (presetPlayers == null && saved != null) {
                _state.value = saved
            } else {
                val count = (presetPlayers ?: 2).coerceIn(2, 6)
                val life = presetLife ?: 20
                val savedNames = saved?.players?.associate { it.id to it.name }.orEmpty()
                val fresh = MtgTableState(
                    playerCount = count,
                    startingLife = life,
                    players = defaultMtgPlayers(count, life, savedNames),
                    showPoison = saved?.showPoison ?: false,
                    showMonarch = saved?.showMonarch ?: false,
                    showCommanderDamage = saved?.showCommanderDamage ?: false,
                    showCommanderTax = saved?.showCommanderTax ?: false,
                    showDayNight = saved?.showDayNight ?: false,
                )
                _state.value = fresh
                repository.saveMtgState(fresh)
            }
        }
    }

    private fun updateAndPersist(
        recordUndo: Boolean = true,
        transform: (MtgTableState) -> MtgTableState,
    ) {
        val prev = _state.value
        val updated = transform(prev)
        if (updated == prev) return
        if (recordUndo) {
            undoStack.addLast(prev)
            while (undoStack.size > 20) undoStack.removeFirst()
            _canUndo.value = undoStack.isNotEmpty()
        }
        _state.value = updated
        viewModelScope.launch {
            repository.saveMtgState(updated)
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        _canUndo.value = undoStack.isNotEmpty()
        _state.value = prev
        viewModelScope.launch { repository.saveMtgState(prev) }
        showBanner("Undid last change")
    }

    private fun showBanner(message: String, durationMs: Long = 2400L) {
        _statusBanner.value = message
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(durationMs)
            _statusBanner.value = null
        }
    }

    fun adjustLife(playerId: Int, delta: Int) {
        updateAndPersist { current ->
            current.copy(
                players = current.players.map { p ->
                    if (p.id == playerId) p.copy(life = p.life + delta) else p
                }
            )
        }
        val nextDelta = (_deltas.value[playerId] ?: 0) + delta
        _deltas.value = _deltas.value + (playerId to nextDelta)
        deltaClearJobs[playerId]?.cancel()
        deltaClearJobs[playerId] = viewModelScope.launch {
            delay(2200L)
            _deltas.value = _deltas.value - playerId
        }
    }

    fun adjustPoison(playerId: Int, delta: Int) {
        updateAndPersist { current ->
            current.copy(
                players = current.players.map { p ->
                    if (p.id == playerId) p.copy(poison = (p.poison + delta).coerceAtLeast(0)) else p
                }
            )
        }
    }

    fun adjustCommanderCasts(playerId: Int, delta: Int) {
        updateAndPersist { current ->
            current.copy(
                players = current.players.map { p ->
                    if (p.id == playerId) {
                        p.copy(commanderCasts = (p.commanderCasts + delta).coerceAtLeast(0))
                    } else {
                        p
                    }
                }
            )
        }
    }

    fun claimMonarch(playerId: Int) {
        updateAndPersist { current ->
            val next = if (current.monarchPlayerId == playerId) null else playerId
            current.copy(monarchPlayerId = next)
        }
    }

    fun cycleDayNight() {
        updateAndPersist { current ->
            val next = when (current.dayNight) {
                DayNightState.NONE -> DayNightState.DAY
                DayNightState.DAY -> DayNightState.NIGHT
                DayNightState.NIGHT -> DayNightState.DAY
            }
            current.copy(dayNight = next)
        }
    }

    fun adjustCommanderDamage(victimId: Int, attackerId: Int, delta: Int) {
        val deductLife = _cmdDeductsLife.value
        updateAndPersist { current ->
            current.copy(
                players = current.players.map { p ->
                    if (p.id == victimId) {
                        val oldDmg = p.commanderDamage[attackerId] ?: 0
                        val newDmg = (oldDmg + delta).coerceAtLeast(0)
                        val appliedDelta = newDmg - oldDmg
                        p.copy(
                            life = if (deductLife) p.life - appliedDelta else p.life,
                            commanderDamage = p.commanderDamage + (attackerId to newDmg),
                        )
                    } else {
                        p
                    }
                }
            )
        }
    }

    fun toggleCmdDeductsLife() {
        _cmdDeductsLife.value = !_cmdDeductsLife.value
    }

    fun pickFirstPlayer(onTickHaptic: () -> Unit = {}, onWinnerHaptic: () -> Unit = {}) {
        val players = _state.value.players
        if (players.isEmpty()) return
        pickFirstJob?.cancel()
        pickFirstJob = viewModelScope.launch {
            _statusBanner.value = "Choosing 1st player..."
            val steps = 9
            var lastIdx = -1
            for (i in 0 until steps) {
                var idx = Random.nextInt(players.size)
                if (players.size > 1 && idx == lastIdx) {
                    idx = (idx + 1) % players.size
                }
                lastIdx = idx
                _pickingHighlightPlayerId.value = players[idx].id
                onTickHaptic()
                delay(70L + (i * 18L))
            }
            val winner = players[Random.nextInt(players.size)]
            _pickingHighlightPlayerId.value = null
            updateAndPersist(recordUndo = false) { current ->
                current.copy(firstPlayerId = winner.id)
            }
            onWinnerHaptic()
            showBanner("${winner.name} goes first!", durationMs = 2800L)
        }
    }

    fun renamePlayer(playerId: Int, newName: String) {
        val clean = newName.trim().take(18)
        _renamingPlayerId.value = null
        if (clean.isBlank()) return
        updateAndPersist(recordUndo = false) { current ->
            current.copy(
                players = current.players.map { p ->
                    if (p.id == playerId) p.copy(name = clean) else p
                }
            )
        }
    }

    fun startRenamingPlayer(playerId: Int?) {
        _renamingPlayerId.value = playerId
    }

    fun resetGame() {
        deltaClearJobs.values.forEach { it.cancel() }
        deltaClearJobs.clear()
        _deltas.value = emptyMap()
        val existingNames = _state.value.players.associate { it.id to it.name }
        updateAndPersist { current ->
            current.copy(
                players = defaultMtgPlayers(current.playerCount, current.startingLife, existingNames),
                monarchPlayerId = null,
                firstPlayerId = null,
                dayNight = DayNightState.NONE,
            )
        }
        _showMenu.value = false
    }

    fun setLayout(playerCount: Int, startingLife: Int) {
        val safeCount = playerCount.coerceIn(2, 6)
        val safeLife = startingLife.coerceIn(5, 999)
        deltaClearJobs.values.forEach { it.cancel() }
        deltaClearJobs.clear()
        _deltas.value = emptyMap()
        val existingNames = _state.value.players.associate { it.id to it.name }
        updateAndPersist { current ->
            current.copy(
                playerCount = safeCount,
                startingLife = safeLife,
                players = defaultMtgPlayers(safeCount, safeLife, existingNames),
                monarchPlayerId = null,
                firstPlayerId = null,
                dayNight = DayNightState.NONE,
            )
        }
    }

    fun toggleShowPoison() {
        updateAndPersist(recordUndo = false) { it.copy(showPoison = !it.showPoison) }
    }

    fun toggleShowMonarch() {
        updateAndPersist(recordUndo = false) { current ->
            val next = !current.showMonarch
            current.copy(
                showMonarch = next,
                monarchPlayerId = if (next) current.monarchPlayerId else null,
            )
        }
    }

    fun toggleShowCommanderDamage() {
        updateAndPersist(recordUndo = false) { it.copy(showCommanderDamage = !it.showCommanderDamage) }
    }

    fun toggleShowCommanderTax() {
        updateAndPersist(recordUndo = false) { it.copy(showCommanderTax = !it.showCommanderTax) }
    }

    fun toggleShowDayNight() {
        updateAndPersist(recordUndo = false) { current ->
            val next = !current.showDayNight
            current.copy(
                showDayNight = next,
                dayNight = if (next && current.dayNight == DayNightState.NONE) DayNightState.DAY else if (!next) DayNightState.NONE else current.dayNight,
            )
        }
    }

    fun setShowMenu(visible: Boolean) {
        _showMenu.value = visible
    }

    fun openCommanderModal(playerId: Int?) {
        _commanderModalPlayerId.value = playerId
    }
}

class MtgTrackerScreen(
    sealedActivity: SealedLightActivity,
    private val presetPlayers: Int? = null,
    private val presetLife: Int? = null,
) : LightScreen<Unit, MtgTrackerViewModel>(sealedActivity) {

    private val repository = GameRepository.getInstance {
        lightContext.buildDatabase(
            GameDatabase::class.java,
            GameRepository.DATABASE_NAME,
        ) {
            fallbackToDestructiveMigration(true)
        }
    }

    override val viewModelClass: Class<MtgTrackerViewModel>
        get() = MtgTrackerViewModel::class.java

    override fun createViewModel() = MtgTrackerViewModel(repository, presetPlayers, presetLife)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val deltas by viewModel.deltas.collectAsState()
        val showMenu by viewModel.showMenu.collectAsState()
        val cmdModalPlayerId by viewModel.commanderModalPlayerId.collectAsState()
        val renamingPlayerId by viewModel.renamingPlayerId.collectAsState()
        val cmdDeductsLife by viewModel.cmdDeductsLife.collectAsState()
        val pickingHighlightId by viewModel.pickingHighlightPlayerId.collectAsState()
        val statusBanner by viewModel.statusBanner.collectAsState()
        val canUndo by viewModel.canUndo.collectAsState()
        val haptics = LocalHapticFeedback.current

        val renameFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .swipeToBack(sideOnly = true, edgeThresholdPx = 110f) {
                        when {
                            renamingPlayerId != null -> viewModel.startRenamingPlayer(null)
                            cmdModalPlayerId != null -> viewModel.openCommanderModal(null)
                            showMenu -> viewModel.setShowMenu(false)
                            else -> goBack()
                        }
                    },
            ) {
                if (renamingPlayerId != null) {
                    val target = state.players.find { it.id == renamingPlayerId }
                    LightTextInputEditor(
                        title = "Name ${target?.name ?: "Player"}",
                        state = renameFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        submitIcon = LightIcons.ACCEPT,
                        singleLine = true,
                        onSubmit = { seq -> viewModel.renamePlayer(renamingPlayerId!!, seq.toString()) },
                        onBack = { viewModel.startRenamingPlayer(null) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val dividerColor = LightThemeTokens.colors.content.copy(alpha = 0.28f)
                    val players = state.players
                    val bottomCount = when (players.size) {
                        2 -> 1
                        3 -> 1
                        4 -> 2
                        5 -> 2
                        else -> 3
                    }
                    val bottomPlayers = players.take(bottomCount)
                    val topPlayers = players.drop(bottomCount)
                    val compact = players.size >= 3
                    val extraCompact = players.size >= 5

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Row (rotated 180° for players across the table)
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
                                            .background(dividerColor)
                                    )
                                }
                                MtgPlayerZone(
                                    player = player,
                                    delta = deltas[player.id],
                                    isMonarch = state.monarchPlayerId == player.id,
                                    isFirst = state.firstPlayerId == player.id,
                                    isRouletteHighlighted = pickingHighlightId == player.id,
                                    showPoison = state.showPoison,
                                    showMonarch = state.showMonarch,
                                    showCommanderDamage = state.showCommanderDamage,
                                    showCommanderTax = state.showCommanderTax,
                                    compact = compact,
                                    extraCompact = extraCompact,
                                    rotated = true,
                                    onAdjustLife = { d ->
                                        haptics.performHapticFeedback(
                                            if (d == 5 || d == -5) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                                        )
                                        viewModel.adjustLife(player.id, d)
                                    },
                                    onAdjustPoison = { d ->
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.adjustPoison(player.id, d)
                                    },
                                    onAdjustCommanderCasts = { d ->
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.adjustCommanderCasts(player.id, d)
                                    },
                                    onToggleMonarch = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.claimMonarch(player.id)
                                    },
                                    onOpenCmd = { viewModel.openCommanderModal(player.id) },
                                    onRename = {
                                        renameFieldState.setTextAndPlaceCursorAtEnd(player.name)
                                        viewModel.startRenamingPlayer(player.id)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        }

                        CenterControlStrip(
                            showDayNight = state.showDayNight,
                            dayNight = state.dayNight,
                            statusBanner = statusBanner,
                            onBack = { goBack() },
                            onMenu = { viewModel.setShowMenu(true) },
                            onToggleDayNight = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.cycleDayNight()
                            },
                            onFirst = {
                                viewModel.pickFirstPlayer(
                                    onTickHaptic = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                    onWinnerHaptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                )
                            },
                            onRules = { navigateTo(screenFactory = { act -> MtgRulingsScreen(act) }) },
                        )

                        // Bottom Row (upright)
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
                                            .background(dividerColor)
                                    )
                                }
                                MtgPlayerZone(
                                    player = player,
                                    delta = deltas[player.id],
                                    isMonarch = state.monarchPlayerId == player.id,
                                    isFirst = state.firstPlayerId == player.id,
                                    isRouletteHighlighted = pickingHighlightId == player.id,
                                    showPoison = state.showPoison,
                                    showMonarch = state.showMonarch,
                                    showCommanderDamage = state.showCommanderDamage,
                                    showCommanderTax = state.showCommanderTax,
                                    compact = compact,
                                    extraCompact = extraCompact,
                                    rotated = false,
                                    onAdjustLife = { d ->
                                        haptics.performHapticFeedback(
                                            if (d == 5 || d == -5) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                                        )
                                        viewModel.adjustLife(player.id, d)
                                    },
                                    onAdjustPoison = { d ->
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.adjustPoison(player.id, d)
                                    },
                                    onAdjustCommanderCasts = { d ->
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.adjustCommanderCasts(player.id, d)
                                    },
                                    onToggleMonarch = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.claimMonarch(player.id)
                                    },
                                    onOpenCmd = { viewModel.openCommanderModal(player.id) },
                                    onRename = {
                                        renameFieldState.setTextAndPlaceCursorAtEnd(player.name)
                                        viewModel.startRenamingPlayer(player.id)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                    }

                    // Clean Tabletop Options Overlay
                    if (showMenu) {
                        MtgMenuOverlay(
                            state = state,
                            canUndo = canUndo,
                            onDismiss = { viewModel.setShowMenu(false) },
                            onSelectLayout = { count, life -> viewModel.setLayout(count, life) },
                            onTogglePoison = { viewModel.toggleShowPoison() },
                            onToggleMonarch = { viewModel.toggleShowMonarch() },
                            onToggleCmd = { viewModel.toggleShowCommanderDamage() },
                            onToggleCmdTax = { viewModel.toggleShowCommanderTax() },
                            onToggleDayNight = { viewModel.toggleShowDayNight() },
                            onRenamePlayer = { player ->
                                viewModel.setShowMenu(false)
                                renameFieldState.setTextAndPlaceCursorAtEnd(player.name)
                                viewModel.startRenamingPlayer(player.id)
                            },
                            onUndo = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.undo()
                                viewModel.setShowMenu(false)
                            },
                            onPickFirst = {
                                viewModel.setShowMenu(false)
                                viewModel.pickFirstPlayer(
                                    onTickHaptic = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                    onWinnerHaptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                )
                            },
                            onReset = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.resetGame()
                            },
                            onOpenDice = {
                                viewModel.setShowMenu(false)
                                navigateTo(screenFactory = { act -> DiceCoinScreen(act) })
                            },
                            onOpenRules = {
                                viewModel.setShowMenu(false)
                                navigateTo(screenFactory = { act -> MtgRulingsScreen(act) })
                            },
                        )
                    }

                    // Commander Damage Modal
                    cmdModalPlayerId?.let { victimId ->
                        val victim = state.players.find { it.id == victimId }
                        if (victim != null) {
                            CommanderDamageOverlay(
                                victim = victim,
                                opponents = state.players.filter { it.id != victimId },
                                deductsLife = cmdDeductsLife,
                                onToggleDeductsLife = { viewModel.toggleCmdDeductsLife() },
                                onAdjust = { attackerId, delta ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.adjustCommanderDamage(victimId, attackerId, delta)
                                },
                                onDismiss = { viewModel.openCommanderModal(null) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterControlStrip(
    showDayNight: Boolean,
    dayNight: DayNightState,
    statusBanner: String?,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onToggleDayNight: () -> Unit,
    onFirst: () -> Unit,
    onRules: () -> Unit,
) {
    val lineColor = LightThemeTokens.colors.content.copy(alpha = 0.28f)
    val content = LightThemeTokens.colors.content
    val bg = LightThemeTokens.colors.background

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = lineColor)
        if (statusBanner != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(content),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = statusBanner,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = bg,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(bg)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CenterStripButton("←", onBack)
                CenterStripButton("≡", onMenu)
                if (showDayNight) {
                    val isNight = dayNight == DayNightState.NIGHT
                    Box(
                        modifier = Modifier
                            .background(
                                if (isNight) bg else content,
                                RoundedCornerShape(4.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = content.copy(alpha = 0.75f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .lightClickable(onClick = onToggleDayNight)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = if (dayNight == DayNightState.NONE) "☀ Day" else dayNight.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isNight) content else bg,
                        )
                    }
                }
                CenterStripButton("1st?", onFirst)
                CenterStripButton("? Rules", onRules)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = lineColor)
    }
}

@Composable
private fun CenterStripButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Detail)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MtgPlayerZone(
    player: MtgPlayerState,
    delta: Int?,
    isMonarch: Boolean,
    isFirst: Boolean,
    isRouletteHighlighted: Boolean,
    showPoison: Boolean,
    showMonarch: Boolean,
    showCommanderDamage: Boolean,
    showCommanderTax: Boolean,
    compact: Boolean,
    extraCompact: Boolean,
    rotated: Boolean,
    onAdjustLife: (Int) -> Unit,
    onAdjustPoison: (Int) -> Unit,
    onAdjustCommanderCasts: (Int) -> Unit,
    onToggleMonarch: () -> Unit,
    onOpenCmd: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = LightThemeTokens.colors.content
    val bgColor = LightThemeTokens.colors.background
    val maxCmd = player.commanderDamage.values.maxOrNull() ?: 0
    val hasExtras = showPoison || showMonarch || showCommanderDamage || showCommanderTax

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = if (rotated) 180f else 0f }
            .then(
                when {
                    isRouletteHighlighted -> Modifier
                        .background(contentColor.copy(alpha = 0.18f))
                        .border(width = 2.5.dp, color = contentColor)
                    isFirst -> Modifier.border(
                        width = 1.5.dp,
                        color = contentColor.copy(alpha = 0.75f),
                    )
                    else -> Modifier
                }
            ),
    ) {
        // Left (-1 / long -5) and Right (+1 / long +5) full-area touch zones
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAdjustLife(-1) },
                        onLongClick = { onAdjustLife(-5) },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "−",
                    fontSize = if (extraCompact) 16.sp else if (compact) 20.sp else 28.sp,
                    color = contentColor.copy(alpha = 0.28f),
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
                        onClick = { onAdjustLife(1) },
                        onLongClick = { onAdjustLife(5) },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "+",
                    fontSize = if (extraCompact) 16.sp else if (compact) 20.sp else 28.sp,
                    color = contentColor.copy(alpha = 0.28f),
                    modifier = Modifier.padding(end = if (extraCompact) 5.dp else if (compact) 8.dp else 18.dp),
                )
            }
        }

        // Top tappable player name + 1ST badge + temporary delta
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (compact) 5.dp else 10.dp, start = 4.dp, end = 4.dp)
                .lightClickable(onClick = onRename)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = player.name,
                fontSize = if (extraCompact) 11.sp else 13.sp,
                color = contentColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isFirst) {
                Box(
                    modifier = Modifier
                        .background(contentColor, RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "1ST",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = bgColor,
                    )
                }
            }
            if (delta != null && delta != 0) {
                val sign = if (delta > 0) "+$delta" else "$delta"
                Text(
                    text = sign,
                    fontSize = if (extraCompact) 11.sp else if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor.copy(alpha = 0.9f),
                )
            }
        }

        // Center crisp life total
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${player.life}",
                fontSize = if (extraCompact) 36.sp else if (compact) 46.sp else 66.sp,
                fontWeight = FontWeight.Light,
                color = contentColor,
            )
            if (player.life <= 0) {
                Text(
                    text = "0 LIFE",
                    fontSize = 9.sp,
                    color = contentColor.copy(alpha = 0.65f),
                )
            }
        }

        // Optional Counters Strip (only rendered when toggled ON in Options)
        if (hasExtras) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (compact) 5.dp else 10.dp, start = 3.dp, end = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showPoison) {
                    val lethalPoison = player.poison >= 10
                    Box(
                        modifier = Modifier
                            .background(
                                if (lethalPoison) contentColor else bgColor,
                                RoundedCornerShape(4.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = contentColor.copy(alpha = if (player.poison > 0) 0.85f else 0.4f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onAdjustPoison(1) },
                                onLongClick = { onAdjustPoison(-1) },
                            )
                            .padding(horizontal = if (extraCompact) 4.dp else 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (lethalPoison) "☠${player.poison}!" else "☠${player.poison}",
                            fontSize = if (extraCompact) 10.sp else 11.sp,
                            fontWeight = if (lethalPoison) FontWeight.Bold else FontWeight.Normal,
                            color = if (lethalPoison) bgColor else contentColor,
                        )
                    }
                }

                if (showMonarch) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (isMonarch) contentColor else bgColor,
                                RoundedCornerShape(4.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = contentColor.copy(alpha = if (isMonarch) 1f else 0.4f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .lightClickable(onClick = onToggleMonarch)
                            .padding(horizontal = if (extraCompact) 4.dp else 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (isMonarch && !extraCompact) "♛ Monarch" else "♛",
                            fontSize = if (extraCompact) 10.sp else 11.sp,
                            fontWeight = if (isMonarch) FontWeight.Bold else FontWeight.Normal,
                            color = if (isMonarch) bgColor else contentColor,
                        )
                    }
                }

                if (showCommanderTax) {
                    val tax = player.commanderCasts * 2
                    Box(
                        modifier = Modifier
                            .background(bgColor, RoundedCornerShape(4.dp))
                            .border(
                                width = 1.dp,
                                color = contentColor.copy(alpha = if (player.commanderCasts > 0) 0.85f else 0.4f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onAdjustCommanderCasts(1) },
                                onLongClick = { onAdjustCommanderCasts(-1) },
                            )
                            .padding(horizontal = if (extraCompact) 4.dp else 6.dp, vertical = 2.dp),
                    ) {
                        val label = if (player.commanderCasts == 0) {
                            "Cast 0"
                        } else if (extraCompact) {
                            "C${player.commanderCasts}(+$tax)"
                        } else {
                            "Cast ${player.commanderCasts} (+$tax)"
                        }
                        Text(
                            text = label,
                            fontSize = if (extraCompact) 10.sp else 11.sp,
                            color = contentColor,
                        )
                    }
                }

                if (showCommanderDamage) {
                    val lethalCmd = maxCmd >= 21
                    Box(
                        modifier = Modifier
                            .background(
                                if (lethalCmd) contentColor else bgColor,
                                RoundedCornerShape(4.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = contentColor.copy(alpha = if (maxCmd > 0) 0.85f else 0.4f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .lightClickable(onClick = onOpenCmd)
                            .padding(horizontal = if (extraCompact) 4.dp else 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (lethalCmd) "CMD $maxCmd!" else "CMD $maxCmd",
                            fontSize = if (extraCompact) 10.sp else 11.sp,
                            fontWeight = if (lethalCmd) FontWeight.Bold else FontWeight.Normal,
                            color = if (lethalCmd) bgColor else contentColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MtgMenuOverlay(
    state: MtgTableState,
    canUndo: Boolean,
    onDismiss: () -> Unit,
    onSelectLayout: (Int, Int) -> Unit,
    onTogglePoison: () -> Unit,
    onToggleMonarch: () -> Unit,
    onToggleCmd: () -> Unit,
    onToggleCmdTax: () -> Unit,
    onToggleDayNight: () -> Unit,
    onRenamePlayer: (MtgPlayerState) -> Unit,
    onUndo: () -> Unit,
    onPickFirst: () -> Unit,
    onReset: () -> Unit,
    onOpenDice: () -> Unit,
    onOpenRules: () -> Unit,
) {
    val bg = LightThemeTokens.colors.background
    val content = LightThemeTokens.colors.content

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg.copy(alpha = 0.97f))
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LightText(text = "Table Options", variant = LightTextVariant.Subheading)
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

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LightText(text = "PLAYERS", variant = LightTextVariant.Detail, lighten = true)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (2..6).forEach { count ->
                        OptionChip(
                            label = "${count}P",
                            selected = state.playerCount == count,
                            onClick = { onSelectLayout(count, state.startingLife) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                LightText(text = "STARTING LIFE", variant = LightTextVariant.Detail, lighten = true)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(20, 30, 40).forEach { life ->
                        OptionChip(
                            label = "$life",
                            selected = state.startingLife == life,
                            onClick = { onSelectLayout(state.playerCount, life) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OptionChip(
                        label = "−5",
                        selected = false,
                        onClick = { onSelectLayout(state.playerCount, (state.startingLife - 5).coerceAtLeast(5)) },
                        modifier = Modifier.weight(0.75f),
                    )
                    OptionChip(
                        label = "+5",
                        selected = false,
                        onClick = { onSelectLayout(state.playerCount, state.startingLife + 5) },
                        modifier = Modifier.weight(0.75f),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                LightText(text = "COUNTERS & TRACKERS", variant = LightTextVariant.Detail, lighten = true)
                Spacer(modifier = Modifier.height(6.dp))

                ToggleOptionRow(
                    title = "Poison Counters (☠)",
                    subtitle = "Tap +1, hold −1 • Lethal at 10",
                    enabled = state.showPoison,
                    onClick = onTogglePoison,
                )
                ToggleOptionRow(
                    title = "The Monarch (♛)",
                    subtitle = "Tap crown to claim or transfer",
                    enabled = state.showMonarch,
                    onClick = onToggleMonarch,
                )
                ToggleOptionRow(
                    title = "Commander Casts & Tax",
                    subtitle = "Tap +1 cast (+2 tax), hold −1",
                    enabled = state.showCommanderTax,
                    onClick = onToggleCmdTax,
                )
                ToggleOptionRow(
                    title = "Commander Damage (CMD)",
                    subtitle = "Per-opponent combat damage • Lethal at 21",
                    enabled = state.showCommanderDamage,
                    onClick = onToggleCmd,
                )
                ToggleOptionRow(
                    title = "Day / Night (☀ / ☾)",
                    subtitle = "Toggle Day & Night from center bar",
                    enabled = state.showDayNight,
                    onClick = onToggleDayNight,
                )

                Spacer(modifier = Modifier.height(14.dp))
                LightText(text = "PLAYER NAMES", variant = LightTextVariant.Detail, lighten = true)
                Spacer(modifier = Modifier.height(6.dp))
                state.players.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { p ->
                            OptionChip(
                                label = p.name,
                                selected = false,
                                onClick = { onRenamePlayer(p) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                LightText(text = "ACTIONS", variant = LightTextVariant.Detail, lighten = true)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OptionChip(
                        label = "Who Goes First",
                        selected = false,
                        onClick = onPickFirst,
                        modifier = Modifier.weight(1f),
                    )
                    OptionChip(
                        label = "Reset Match",
                        selected = false,
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canUndo) {
                        OptionChip(
                            label = "↶ Undo",
                            selected = false,
                            onClick = onUndo,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OptionChip(
                        label = "Dice & Coin",
                        selected = false,
                        onClick = onOpenDice,
                        modifier = Modifier.weight(1f),
                    )
                    OptionChip(
                        label = "Rulings",
                        selected = false,
                        onClick = onOpenRules,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                LightText(
                    text = "Tap left/right for −1/+1 • Hold for −5/+5 • Tap any name to rename.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun CommanderDamageOverlay(
    victim: MtgPlayerState,
    opponents: List<MtgPlayerState>,
    deductsLife: Boolean,
    onToggleDeductsLife: () -> Unit,
    onAdjust: (attackerId: Int, delta: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val bg = LightThemeTokens.colors.background
    val content = LightThemeTokens.colors.content

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg.copy(alpha = 0.97f))
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    LightText(text = "${victim.name} — Cmd Dmg", variant = LightTextVariant.Subheading)
                    LightText(
                        text = "Life: ${victim.life} • 21 from one commander is lethal",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                }
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

            ToggleOptionRow(
                title = "Also subtract from Life total",
                subtitle = "Updates ${victim.name}'s life when cmd dmg changes",
                enabled = deductsLife,
                onClick = onToggleDeductsLife,
            )

            Spacer(modifier = Modifier.height(10.dp))

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                opponents.forEach { opp ->
                    val dmg = victim.commanderDamage[opp.id] ?: 0
                    val lethal = dmg >= 21
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            LightText(
                                text = "From ${opp.name}",
                                variant = LightTextVariant.Paragraph,
                            )
                            if (lethal) {
                                LightText(text = "21+ LETHAL", variant = LightTextVariant.Detail)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SmallStepButton("−1") { onAdjust(opp.id, -1) }
                            Text(
                                text = "$dmg",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Medium,
                                color = content,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                            SmallStepButton("+1") { onAdjust(opp.id, 1) }
                            SmallStepButton("+5") { onAdjust(opp.id, 5) }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = content.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun SmallStepButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = LightThemeTokens.colors.content.copy(alpha = 0.55f),
                shape = RoundedCornerShape(5.dp),
            )
            .lightClickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Detail)
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = LightThemeTokens.colors.content
    val bg = LightThemeTokens.colors.background
    Box(
        modifier = modifier
            .background(
                if (selected) content else bg,
                RoundedCornerShape(6.dp),
            )
            .border(
                width = 1.dp,
                color = content.copy(alpha = if (selected) 1f else 0.5f),
                shape = RoundedCornerShape(6.dp),
            )
            .lightClickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) bg else content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToggleOptionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val content = LightThemeTokens.colors.content
    val bg = LightThemeTokens.colors.background
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = title, variant = LightTextVariant.Paragraph)
            LightText(text = subtitle, variant = LightTextVariant.Detail, lighten = true)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(if (enabled) content else bg, RoundedCornerShape(4.dp))
                .border(1.dp, content.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = if (enabled) "ON" else "OFF",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) bg else content,
            )
        }
    }
}
