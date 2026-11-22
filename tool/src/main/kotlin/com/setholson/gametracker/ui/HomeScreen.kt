package com.setholson.gametracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.gametracker.data.GameRepository
import com.setholson.gametracker.db.GameDatabase
import com.setholson.gametracker.model.MtgTableState
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
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
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: GameRepository,
) : LightViewModel<Unit>() {

    private val _savedMtg = MutableStateFlow<MtgTableState?>(null)
    val savedMtg: StateFlow<MtgTableState?> = _savedMtg.asStateFlow()

    private val _cachedCardCount = MutableStateFlow(0)
    val cachedCardCount: StateFlow<Int> = _cachedCardCount.asStateFlow()

    private var appliedDefaultOledDark = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Pure-black AMOLED dark theme by default to maximize Light Phone III battery life
        if (!appliedDefaultOledDark) {
            appliedDefaultOledDark = true
            LightThemeController.setDarkTheme()
        }
        viewModelScope.launch {
            _savedMtg.value = repository.loadMtgState()
            _cachedCardCount.value = repository.getRecentCachedCards().size
        }
    }

    fun toggleTheme() {
        if (LightThemeController.isDarkTheme) {
            LightThemeController.setLightTheme()
        } else {
            LightThemeController.setDarkTheme()
        }
    }
}

@InitialScreen
class HomeScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, HomeViewModel>(sealedActivity) {

    private val repository = GameRepository.getInstance {
        lightContext.buildDatabase(
            GameDatabase::class.java,
            GameRepository.DATABASE_NAME,
        ) {
            fallbackToDestructiveMigration(true)
        }
    }

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val savedMtg by viewModel.savedMtg.collectAsState()
        val cachedCardCount by viewModel.cachedCardCount.collectAsState()
        val isDark = LightThemeController.isDarkTheme

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        center = LightTopBarCenter.Text("Game Tracker"),
                        modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                    )

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1.5f.gridUnitsAsDp()),
                    ) {
                        val rightPad = 1.5f.gridUnitsAsDp()
                        Spacer(modifier = Modifier.height(6.dp))

                        // MAGIC: THE GATHERING Section
                        LightText(
                            text = "MAGIC: THE GATHERING",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(end = rightPad, bottom = 6.dp),
                        )

                        // Resume ongoing match if active
                        savedMtg?.let { active ->
                            val lifeSummary = active.players.joinToString(" • ") { "${it.life}" }
                            HomeActionRow(
                                title = "Resume ${active.playerCount}P Match",
                                subtitle = "Life: $lifeSummary",
                                badge = "Active",
                                onClick = {
                                    navigateTo(screenFactory = { act ->
                                        MtgTrackerScreen(act, presetPlayers = null, presetLife = null)
                                    })
                                },
                                modifier = Modifier.padding(end = rightPad),
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = LightThemeTokens.colors.content.copy(alpha = 0.18f),
                                modifier = Modifier.padding(end = rightPad),
                            )
                        }

                        HomeActionRow(
                            title = "2-Player Life Counter",
                            subtitle = "Standard / Modern / Draft • 20 life",
                            onClick = {
                                navigateTo(screenFactory = { act ->
                                    MtgTrackerScreen(act, presetPlayers = 2, presetLife = 20)
                                })
                            },
                            modifier = Modifier.padding(end = rightPad),
                        )

                        HomeActionRow(
                            title = "4-Player Commander",
                            subtitle = "40 life • Optional poison, monarch & cmd dmg",
                            onClick = {
                                navigateTo(screenFactory = { act ->
                                    MtgTrackerScreen(act, presetPlayers = 4, presetLife = 40)
                                })
                            },
                            modifier = Modifier.padding(end = rightPad),
                        )

                        HomeActionRow(
                            title = "Card & Rulings Lookup",
                            subtitle = if (cachedCardCount > 0) {
                                "Oracle text, 2-card interactions & offline guide ($cachedCardCount cached)"
                            } else {
                                "Official rulings, 2-card interactions & offline rules guide"
                            },
                            onClick = {
                                navigateTo(screenFactory = { act -> MtgRulingsScreen(act) })
                            },
                            modifier = Modifier.padding(end = rightPad),
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // OTHER TABLETOP GAMES Section
                        LightText(
                            text = "OTHER GAMES & TOOLS",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(end = rightPad, bottom = 6.dp),
                        )

                        HomeActionRow(
                            title = "Scoreboard (2–8 Players)",
                            subtitle = "Catan, Uno, Skyjo, Scrabble, Hearts & custom games",
                            onClick = {
                                navigateTo(screenFactory = { act -> ScoreboardScreen(act) })
                            },
                            modifier = Modifier.padding(end = rightPad),
                        )

                        HomeActionRow(
                            title = "Chess & Turn Clock",
                            subtitle = "2-player tabletop tap timer with increment",
                            onClick = {
                                navigateTo(screenFactory = { act -> ChessClockScreen(act) })
                            },
                            modifier = Modifier.padding(end = rightPad),
                        )

                        HomeActionRow(
                            title = "Dice, Coin & First Player",
                            subtitle = "Coin flip, D4–D20, 2D6 & random turn order",
                            onClick = {
                                navigateTo(screenFactory = { act -> DiceCoinScreen(act) })
                            },
                            modifier = Modifier.padding(end = rightPad),
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Battery-conscious OLED Theme Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad, top = 4.dp, bottom = 18.dp)
                                .lightClickable { viewModel.toggleTheme() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = if (isDark) "Display: OLED Dark (Battery Saver)" else "Display: Light Mode",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                LightText(
                                    text = if (isDark) "Light" else "Dark",
                                    variant = LightTextVariant.Detail,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionRow(
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = title,
                variant = LightTextVariant.Subheading,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = LightThemeTokens.colors.content.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    LightText(text = badge, variant = LightTextVariant.Detail)
                }
            }
        }
        LightText(
            text = subtitle,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
