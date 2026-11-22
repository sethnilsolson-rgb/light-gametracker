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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

class DiceCoinViewModel : LightViewModel<Unit>() {

    private val _headline = MutableStateFlow("Tap to Roll")
    val headline: StateFlow<String> = _headline.asStateFlow()

    private val _detail = MutableStateFlow("Coin flip, dice, or pick who goes first")
    val detail: StateFlow<String> = _detail.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private fun record(result: String, sub: String, logEntry: String) {
        _headline.value = result
        _detail.value = sub
        _history.value = (listOf(logEntry) + _history.value).take(12)
    }

    fun flipCoin() {
        val heads = Random.nextBoolean()
        val label = if (heads) "HEADS" else "TAILS"
        record(label, "Coin Flip", "Coin → $label")
    }

    fun rollDie(sides: Int) {
        val value = Random.nextInt(1, sides + 1)
        record("$value", "D$sides Roll", "D$sides → $value")
    }

    fun roll2D6() {
        val a = Random.nextInt(1, 7)
        val b = Random.nextInt(1, 7)
        val sum = a + b
        record("$sum", "2D6 ($a + $b)", "2D6 → $sum ($a+$b)")
    }

    fun pickFirstPlayer(playerCount: Int) {
        val winner = Random.nextInt(1, playerCount + 1)
        record("Player $winner", "Goes first (out of $playerCount)", "First ($playerCount P) → Player $winner")
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
        val history by viewModel.history.collectAsState()
        val haptics = LocalHapticFeedback.current

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
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

                    // Big Result Display Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 8.dp)
                            .border(
                                width = 1.dp,
                                color = LightThemeTokens.colors.content.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(vertical = 18.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LightText(text = headline, variant = LightTextVariant.Title)
                            Spacer(modifier = Modifier.height(4.dp))
                            LightText(text = detail, variant = LightTextVariant.Detail, lighten = true)
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
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.flipCoin()
                            }
                            DiceButton("2D6", Modifier.weight(1f)) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.roll2D6()
                            }
                            DiceButton("D20", Modifier.weight(1f)) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.rollDie(20)
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
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.rollDie(sides)
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
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.pickFirstPlayer(count)
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
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Paragraph)
    }
}
