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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

private sealed interface RollAction {
    data object Coin : RollAction
    data object TwoD6 : RollAction
    data class Die(val sides: Int) : RollAction
    data class FirstPlayer(val count: Int) : RollAction
}

class DiceCoinViewModel : LightViewModel<Unit>() {

    private val _headline = MutableStateFlow("Tap to Roll")
    val headline: StateFlow<String> = _headline.asStateFlow()

    private val _detail = MutableStateFlow("Coin flip, dice, or pick who goes first")
    val detail: StateFlow<String> = _detail.asStateFlow()

    private val _isRolling = MutableStateFlow(false)
    val isRolling: StateFlow<Boolean> = _isRolling.asStateFlow()

    private val _justLanded = MutableStateFlow(false)
    val justLanded: StateFlow<Boolean> = _justLanded.asStateFlow()

    private val _rollCount = MutableStateFlow(0)
    val rollCount: StateFlow<Int> = _rollCount.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private var lastAction: RollAction = RollAction.Die(20)
    private var animJob: Job? = null

    fun rerollLast(onTick: () -> Unit, onLand: () -> Unit) {
        when (val action = lastAction) {
            RollAction.Coin -> flipCoin(onTick, onLand)
            RollAction.TwoD6 -> roll2D6(onTick, onLand)
            is RollAction.Die -> rollDie(action.sides, onTick, onLand)
            is RollAction.FirstPlayer -> pickFirstPlayer(action.count, onTick, onLand)
        }
    }

    fun flipCoin(onTick: () -> Unit = {}, onLand: () -> Unit = {}) {
        lastAction = RollAction.Coin
        runAnimatedRoll(
            steps = 7,
            baseDelayMs = 55L,
            rollingLabel = "Flipping coin...",
            frameGenerator = { step -> if (step % 2 == 0) "HEADS" else "TAILS" },
            finalResult = {
                val heads = Random.nextBoolean()
                val label = if (heads) "HEADS" else "TAILS"
                Triple(label, "Coin Flip", "Coin → $label")
            },
            onTick = onTick,
            onLand = onLand,
        )
    }

    fun rollDie(sides: Int, onTick: () -> Unit = {}, onLand: () -> Unit = {}) {
        lastAction = RollAction.Die(sides)
        runAnimatedRoll(
            steps = 7,
            baseDelayMs = 55L,
            rollingLabel = "Rolling D$sides...",
            frameGenerator = { "${Random.nextInt(1, sides + 1)}" },
            finalResult = {
                val value = Random.nextInt(1, sides + 1)
                Triple("$value", "D$sides Roll", "D$sides → $value")
            },
            onTick = onTick,
            onLand = onLand,
        )
    }

    fun roll2D6(onTick: () -> Unit = {}, onLand: () -> Unit = {}) {
        lastAction = RollAction.TwoD6
        runAnimatedRoll(
            steps = 7,
            baseDelayMs = 55L,
            rollingLabel = "Rolling 2D6...",
            frameGenerator = { "${Random.nextInt(2, 13)}" },
            finalResult = {
                val a = Random.nextInt(1, 7)
                val b = Random.nextInt(1, 7)
                val sum = a + b
                Triple("$sum", "2D6 ($a + $b)", "2D6 → $sum ($a+$b)")
            },
            onTick = onTick,
            onLand = onLand,
        )
    }

    fun pickFirstPlayer(playerCount: Int, onTick: () -> Unit = {}, onLand: () -> Unit = {}) {
        lastAction = RollAction.FirstPlayer(playerCount)
        runAnimatedRoll(
            steps = 10,
            baseDelayMs = 65L,
            rollingLabel = "Deciding 1st of $playerCount...",
            frameGenerator = { step -> "Player ${(step % playerCount) + 1}" },
            finalResult = {
                val winner = Random.nextInt(1, playerCount + 1)
                Triple(
                    "Player $winner",
                    "Goes first (out of $playerCount)",
                    "1st ($playerCount P) → Player $winner",
                )
            },
            onTick = onTick,
            onLand = onLand,
        )
    }

    private fun runAnimatedRoll(
        steps: Int,
        baseDelayMs: Long,
        rollingLabel: String,
        frameGenerator: (Int) -> String,
        finalResult: () -> Triple<String, String, String>,
        onTick: () -> Unit,
        onLand: () -> Unit,
    ) {
        animJob?.cancel()
        animJob = viewModelScope.launch {
            _isRolling.value = true
            _justLanded.value = false
            _detail.value = rollingLabel

            for (i in 0 until steps) {
                _headline.value = frameGenerator(i)
                onTick()
                delay(baseDelayMs + (i * 12L))
            }

            val (result, sub, logEntry) = finalResult()
            val nextNum = _rollCount.value + 1
            _rollCount.value = nextNum
            _headline.value = result
            _detail.value = "$sub • Roll #$nextNum (tap box to re-roll)"
            _history.value = (listOf("#$nextNum  $logEntry") + _history.value).take(14)
            _isRolling.value = false
            _justLanded.value = true
            onLand()

            delay(450L)
            _justLanded.value = false
        }
    }
}

class DiceCoinScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, DiceCoinViewModel>(sealedActivity) {

    override val viewModelClass: Class<DiceCoinViewModel>
        get() = DiceCoinViewModel::class.java

    override fun createViewModel() = DiceCoinViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val headline by viewModel.headline.collectAsState()
        val detail by viewModel.detail.collectAsState()
        val isRolling by viewModel.isRolling.collectAsState()
        val justLanded by viewModel.justLanded.collectAsState()
        val history by viewModel.history.collectAsState()
        val haptics = LocalHapticFeedback.current

        val tickHaptic = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        val landHaptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

        LightTheme(colors = themeColors) {
            val content = LightThemeTokens.colors.content
            val bg = LightThemeTokens.colors.background

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .swipeToBack { goBack() },
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text("Dice & Coin"),
                        modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                    )

                    // High-contrast interactive Result Card (flashes inverted on land; tap to re-roll!)
                    val boxBg = if (justLanded) content else bg
                    val boxFg = if (justLanded) bg else content
                    val borderAlpha = when {
                        justLanded -> 1f
                        isRolling -> 0.9f
                        else -> 0.45f
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 8.dp)
                            .background(boxBg, RoundedCornerShape(8.dp))
                            .border(
                                width = if (isRolling || justLanded) 2.dp else 1.dp,
                                color = content.copy(alpha = borderAlpha),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .lightClickable { viewModel.rerollLast(tickHaptic, landHaptic) }
                            .padding(vertical = 20.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = headline,
                                fontSize = 36.sp,
                                fontWeight = if (justLanded) FontWeight.Bold else FontWeight.Light,
                                color = boxFg,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = detail,
                                fontSize = 12.sp,
                                color = boxFg.copy(alpha = if (justLanded) 0.9f else 0.7f),
                            )
                        }
                    }

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1.5f.gridUnitsAsDp()),
                    ) {
                        val rightPad = 1.5f.gridUnitsAsDp()
                        Spacer(modifier = Modifier.height(6.dp))

                        LightText(
                            text = "COIN & DICE",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(end = rightPad, bottom = 6.dp),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DiceButton("Coin", Modifier.weight(1f)) {
                                viewModel.flipCoin(tickHaptic, landHaptic)
                            }
                            DiceButton("2D6", Modifier.weight(1f)) {
                                viewModel.roll2D6(tickHaptic, landHaptic)
                            }
                            DiceButton("D20", Modifier.weight(1f)) {
                                viewModel.rollDie(20, tickHaptic, landHaptic)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(4, 6, 8, 10, 12).forEach { sides ->
                                DiceButton("D$sides", Modifier.weight(1f)) {
                                    viewModel.rollDie(sides, tickHaptic, landHaptic)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        LightText(
                            text = "WHO GOES FIRST",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(end = rightPad, bottom = 6.dp),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(2, 3, 4, 5, 6).forEach { count ->
                                DiceButton("${count}P", Modifier.weight(1f)) {
                                    viewModel.pickFirstPlayer(count, tickHaptic, landHaptic)
                                }
                            }
                        }

                        if (history.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(18.dp))
                            LightText(
                                text = "RECENT ROLLS",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(end = rightPad, bottom = 6.dp),
                            )
                            history.forEach { entry ->
                                LightText(
                                    text = entry,
                                    variant = LightTextVariant.Detail,
                                    modifier = Modifier.padding(end = rightPad, top = 3.dp, bottom = 3.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiceButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = LightThemeTokens.colors.content.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
            )
            .lightClickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Paragraph)
    }
}
