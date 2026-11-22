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
import com.setholson.gametracker.data.GameRepository
import com.setholson.gametracker.db.GameDatabase
import com.setholson.gametracker.model.MtgPlayerState
import com.setholson.gametracker.model.MtgTableState
import com.setholson.gametracker.model.defaultMtgPlayers
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightScrollView
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
import kotlinx.coroutines.launch
import kotlin.random.Random

class MtgTrackerViewModel(
    private val repository: GameRepository,
    presetPlayers: Int?,
    presetLife: Int?,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow(
        MtgTableState(
            playerCount = presetPlayers ?: 2,
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

    private val _cmdDeductsLife = MutableStateFlow(true)
    val cmdDeductsLife: StateFlow<Boolean> = _cmdDeductsLife.asStateFlow()

    private val deltaClearJobs = mutableMapOf<Int, Job>()

    init {
        viewModelScope.launch {
            val saved = repository.loadMtgState()
            if (presetPlayers == null && saved != null) {
                _state.value = saved
            } else {
                val count = presetPlayers ?: 2
                val life = presetLife ?: 20
                val fresh = MtgTableState(
                    playerCount = count,
                    startingLife = life,
                    players = defaultMtgPlayers(count, life),
                    showPoison = saved?.showPoison ?: false,
                    showMonarch = saved?.showMonarch ?: false,
                    showCommanderDamage = saved?.showCommanderDamage ?: false,
                )
                _state.value = fresh
                repository.saveMtgState(fresh)
            }
        }
    }

    private fun updateAndPersist(transform: (MtgTableState) -> MtgTableState) {
        val updated = transform(_state.value)
        _state.value = updated
        viewModelScope.launch {
            repository.saveMtgState(updated)
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

    fun claimMonarch(playerId: Int) {
        updateAndPersist { current ->
            val next = if (current.monarchPlayerId == playerId) null else playerId
            current.copy(monarchPlayerId = next)
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

    fun pickFirstPlayer() {
        val count = _state.value.playerCount.coerceAtLeast(2)
        val winner = Random.nextInt(1, count + 1)
        updateAndPersist { current ->
            current.copy(firstPlayerId = winner)
        }
    }

    fun resetGame() {
        deltaClearJobs.values.forEach { it.cancel() }
        deltaClearJobs.clear()
        _deltas.value = emptyMap()
        updateAndPersist { current ->
            current.copy(
                players = defaultMtgPlayers(current.playerCount, current.startingLife),
                monarchPlayerId = null,
                firstPlayerId = null,
            )
        }
        _showMenu.value = false
    }

    fun setLayout(playerCount: Int, startingLife: Int) {
        deltaClearJobs.values.forEach { it.cancel() }
        deltaClearJobs.clear()
        _deltas.value = emptyMap()
        updateAndPersist { current ->
            current.copy(
                playerCount = playerCount,
                startingLife = startingLife,
                players = defaultMtgPlayers(playerCount, startingLife),
                monarchPlayerId = null,
                firstPlayerId = null,
            )
        }
    }

    fun toggleShowPoison() {
        updateAndPersist { it.copy(showPoison = !it.showPoison) }
    }

    fun toggleShowMonarch() {
        updateAndPersist { current ->
            val next = !current.showMonarch
            current.copy(
                showMonarch = next,
                monarchPlayerId = if (next) current.monarchPlayerId else null,
            )
        }
    }

    fun toggleShowCommanderDamage() {
        updateAndPersist { it.copy(showCommanderDamage = !it.showCommanderDamage) }
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
        val cmdDeductsLife by viewModel.cmdDeductsLife.collectAsState()
        val haptics = LocalHapticFeedback.current

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val dividerColor = LightThemeTokens.colors.content.copy(alpha = 0.28f)
                val players = state.players

                Column(modifier = Modifier.fillMaxSize()) {
                    if (state.playerCount <= 2 && players.size >= 2) {
                        // Top Player (Player 2, rotated 180° for opponent across the table)
                        MtgPlayerZone(
                            player = players[1],
                            delta = deltas[players[1].id],
                            isMonarch = state.monarchPlayerId == players[1].id,
                            isFirst = state.firstPlayerId == players[1].id,
                            showPoison = state.showPoison,
                            showMonarch = state.showMonarch,
                            showCommanderDamage = state.showCommanderDamage,
                            compact = false,
                            rotated = true,
                            onAdjustLife = { d ->
                                haptics.performHapticFeedback(
                                    if (d == 5 || d == -5) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                                )
                                viewModel.adjustLife(players[1].id, d)
                            },
                            onAdjustPoison = { d ->
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.adjustPoison(players[1].id, d)
                            },
                            onToggleMonarch = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.claimMonarch(players[1].id)
                            },
                            onOpenCmd = { viewModel.openCommanderModal(players[1].id) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )

                        CenterControlStrip(
                            onBack = { goBack() },
                            onMenu = { viewModel.setShowMenu(true) },
                            onFirst = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.pickFirstPlayer()
                            },
                            onRules = { navigateTo(screenFactory = { act -> MtgRulingsScreen(act) }) },
                        )

                        // Bottom Player (Player 1, upright)
                        MtgPlayerZone(
                            player = players[0],
                            delta = deltas[players[0].id],
                            isMonarch = state.monarchPlayerId == players[0].id,
                            isFirst = state.firstPlayerId == players[0].id,
                            showPoison = state.showPoison,
                            showMonarch = state.showMonarch,
                            showCommanderDamage = state.showCommanderDamage,
                            compact = false,
                            rotated = false,
                            onAdjustLife = { d ->
                                haptics.performHapticFeedback(
                                    if (d == 5 || d == -5) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                                )
                                viewModel.adjustLife(players[0].id, d)
                            },
                            onAdjustPoison = { d ->
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.adjustPoison(players[0].id, d)
                            },
                            onToggleMonarch = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.claimMonarch(players[0].id)
                            },
                            onOpenCmd = { viewModel.openCommanderModal(players[0].id) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    } else if (players.size >= 4) {
                        // 4-Player Commander Tabletop Grid: P2 & P3 rotated on top, P1 & P4 upright on bottom
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            MtgPlayerZone(
                                player = players[1],
                                delta = deltas[players[1].id],
                                isMonarch = state.monarchPlayerId == players[1].id,
                                isFirst = state.firstPlayerId == players[1].id,
                                showPoison = state.showPoison,
                                showMonarch = state.showMonarch,
                                showCommanderDamage = state.showCommanderDamage,
                                compact = true,
                                rotated = true,
                                onAdjustLife = { d ->
                                    haptics.performHapticFeedback(
                                        if (d == 5 || d == -5) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                                    )
                                    viewModel.adjustLife(players[1].id, d)
                                },
                                onAdjustPoison = { d ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.adjustPoison(players[1].id, d)
                                },
                                onToggleMonarch = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.claimMonarch(players[1].id)
                                },
                                onOpenCmd = { viewModel.openCommanderModal(players[1].id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .background(dividerColor)
                            )
                            MtgPlayerZone(
                                player = players[2],
                                delta = deltas[players[2].id],
                                isMonarch = state.monarchPlayerId == players[2].id,
                                isFirst = state.firstPlayerId == players[2].id,
                                showPoison = state.showPoison,
                                showMonarch = state.showMonarch,
                                showCommanderDamage = state.showCommanderDamage,
                                compact = true,
                                rotated = true,
                                onAdjustLife = { d ->
                                    haptics.performHapticFeedback(
                                        if (d == 5 || d == -5) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                                    )
                                    viewModel.adjustLife(players[2].id, d)
                                },
                                onAdjustPoison = { d ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.adjustPoison(players[2].id, d)
                                },
                                onToggleMonarch = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.claimMonarch(players[2].id)
                                },
                                onOpenCmd = { viewModel.openCommanderModal(players[2].id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }

                        CenterControlStrip(
                            onBack = { goBack() },
                            onMenu = { viewModel.setShowMenu(true) },
                            onFirst = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.pickFirstPlayer()
                            },
                            onRules = { navigateTo(screenFactory = { act -> MtgRulingsScreen(act) }) },
                        )

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            MtgPlayerZone(
                                player = players[0],
                                delta = deltas[players[0].id],
                                isMonarch = state.monarchPlayerId == players[0].id,
                                isFirst = state.firstPlayerId == players[0].id,
                                showPoison = state.showPoison,
                                showMonarch = state.showMonarch,
                                showCommanderDamage = state.showCommanderDamage,
                                compact = true,
                                rotated = false,
                                onAdjustLife = { d ->
                                    haptics.performHapticFeedback(
                                        if (d == 5 || d == -5) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                                    )
                                    viewModel.adjustLife(players[0].id, d)
                                },
                                onAdjustPoison = { d ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.adjustPoison(players[0].id, d)
                                },
                                onToggleMonarch = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.claimMonarch(players[0].id)
                                },
                                onOpenCmd = { viewModel.openCommanderModal(players[0].id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .background(dividerColor)
                            )
                            MtgPlayerZone(
                                player = players[3],
                                delta = deltas[players[3].id],
                                isMonarch = state.monarchPlayerId == players[3].id,
                                isFirst = state.firstPlayerId == players[3].id,
                                showPoison = state.showPoison,
                                showMonarch = state.showMonarch,
                                showCommanderDamage = state.showCommanderDamage,
                                compact = true,
                                rotated = false,
                                onAdjustLife = { d ->
                                    haptics.performHapticFeedback(
                                        if (d == 5 || d == -5) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                                    )
                                    viewModel.adjustLife(players[3].id, d)
                                },
                                onAdjustPoison = { d ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.adjustPoison(players[3].id, d)
                                },
                                onToggleMonarch = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.claimMonarch(players[3].id)
                                },
                                onOpenCmd = { viewModel.openCommanderModal(players[3].id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }

                // Clean Tabletop Menu Overlay (keeps life screen clutter-free)
                if (showMenu) {
                    MtgMenuOverlay(
                        state = state,
                        onDismiss = { viewModel.setShowMenu(false) },
                        onSelectLayout = { count, life -> viewModel.setLayout(count, life) },
                        onTogglePoison = { viewModel.toggleShowPoison() },
                        onToggleMonarch = { viewModel.toggleShowMonarch() },
                        onToggleCmd = { viewModel.toggleShowCommanderDamage() },
                        onPickFirst = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.pickFirstPlayer()
                            viewModel.setShowMenu(false)
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

@Composable
private fun CenterControlStrip(
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onFirst: () -> Unit,
    onRules: () -> Unit,
) {
    val lineColor = LightThemeTokens.colors.content.copy(alpha = 0.28f)
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = lineColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(LightThemeTokens.colors.background)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CenterStripButton("←", onBack)
            CenterStripButton("≡ Options", onMenu)
            CenterStripButton("1st?", onFirst)
            CenterStripButton("? Rules", onRules)
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
    showPoison: Boolean,
    showMonarch: Boolean,
    showCommanderDamage: Boolean,
    compact: Boolean,
    rotated: Boolean,
    onAdjustLife: (Int) -> Unit,
    onAdjustPoison: (Int) -> Unit,
    onToggleMonarch: () -> Unit,
    onOpenCmd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = LightThemeTokens.colors.content
    val bgColor = LightThemeTokens.colors.background
    val maxCmd = player.commanderDamage.values.maxOrNull() ?: 0
    val hasExtras = showPoison || showMonarch || showCommanderDamage

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = if (rotated) 180f else 0f }
            .then(
                if (isFirst) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = contentColor.copy(alpha = 0.75f),
                    )
                } else {
                    Modifier
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
                    fontSize = if (compact) 22.sp else 28.sp,
                    color = contentColor.copy(alpha = 0.28f),
                    modifier = Modifier.padding(start = if (compact) 10.dp else 18.dp),
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
                    fontSize = if (compact) 22.sp else 28.sp,
                    color = contentColor.copy(alpha = 0.28f),
                    modifier = Modifier.padding(end = if (compact) 10.dp else 18.dp),
                )
            }
        }

        // Top subtle player label & 1ST badge
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LightText(
                text = if (compact) "P${player.id}" else player.name,
                variant = LightTextVariant.Detail,
                lighten = true,
            )
            if (isFirst) {
                Box(
                    modifier = Modifier
                        .background(contentColor, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "1ST",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = bgColor,
                    )
                }
            }
            if (delta != null && delta != 0) {
                val sign = if (delta > 0) "+$delta" else "$delta"
                Text(
                    text = sign,
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor.copy(alpha = 0.85f),
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
                fontSize = if (compact) 46.sp else 66.sp,
                fontWeight = FontWeight.Light,
                color = contentColor,
            )
            if (player.life <= 0) {
                Text(
                    text = "0 LIFE",
                    fontSize = 10.sp,
                    color = contentColor.copy(alpha = 0.65f),
                )
            }
        }

        // Optional Extras Strip (hidden by default for clean minimalist look!)
        if (hasExtras) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (compact) 6.dp else 10.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = if (lethalPoison) "☠ ${player.poison}!" else "☠ ${player.poison}",
                            fontSize = 11.sp,
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
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = if (isMonarch) "♛ Monarch" else "♛",
                            fontSize = 11.sp,
                            fontWeight = if (isMonarch) FontWeight.Bold else FontWeight.Normal,
                            color = if (isMonarch) bgColor else contentColor,
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
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = if (lethalCmd) "CMD $maxCmd!" else "CMD $maxCmd",
                            fontSize = 11.sp,
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
    onDismiss: () -> Unit,
    onSelectLayout: (Int, Int) -> Unit,
    onTogglePoison: () -> Unit,
    onToggleMonarch: () -> Unit,
    onToggleCmd: () -> Unit,
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
            .background(bg.copy(alpha = 0.96f))
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LightText(text = "MTG Table Options", variant = LightTextVariant.Subheading)
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OptionChip(
                        label = "2 Players",
                        selected = state.playerCount == 2,
                        onClick = { onSelectLayout(2, state.startingLife) },
                        modifier = Modifier.weight(1f),
                    )
                    OptionChip(
                        label = "4 Players",
                        selected = state.playerCount == 4,
                        onClick = { onSelectLayout(4, state.startingLife) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                LightText(text = "STARTING LIFE", variant = LightTextVariant.Detail, lighten = true)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(20, 30, 40).forEach { life ->
                        OptionChip(
                            label = "$life",
                            selected = state.startingLife == life,
                            onClick = { onSelectLayout(state.playerCount, life) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                LightText(
                    text = "OPTIONAL COUNTERS (HIDE TO KEEP CLEAN)",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
                Spacer(modifier = Modifier.height(6.dp))

                ToggleOptionRow(
                    title = "Poison Counters (☠)",
                    subtitle = "Tap +1, long-press −1 • Lethal at 10",
                    enabled = state.showPoison,
                    onClick = onTogglePoison,
                )
                ToggleOptionRow(
                    title = "The Monarch (♛)",
                    subtitle = "Tap crown to pass Monarch between players",
                    enabled = state.showMonarch,
                    onClick = onToggleMonarch,
                )
                ToggleOptionRow(
                    title = "Commander Damage (CMD)",
                    subtitle = "Track combat damage per opponent • Lethal at 21",
                    enabled = state.showCommanderDamage,
                    onClick = onToggleCmd,
                )

                Spacer(modifier = Modifier.height(14.dp))
                LightText(text = "TABLE ACTIONS", variant = LightTextVariant.Detail, lighten = true)
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
                    OptionChip(
                        label = "Dice & Coin",
                        selected = false,
                        onClick = onOpenDice,
                        modifier = Modifier.weight(1f),
                    )
                    OptionChip(
                        label = "Card & Rulings",
                        selected = false,
                        onClick = onOpenRules,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                LightText(
                    text = "Tip: Tap left/right of life for −1/+1, long-press for −5/+5.",
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
            .background(bg.copy(alpha = 0.96f))
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
                subtitle = "Automatically adjusts ${victim.name}'s life when cmd dmg changes",
                enabled = deductsLife,
                onClick = onToggleDeductsLife,
            )

            Spacer(modifier = Modifier.height(10.dp))

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
            .padding(vertical = 9.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) bg else content,
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
