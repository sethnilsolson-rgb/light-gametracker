package com.setholson.gametracker.model

import kotlinx.serialization.Serializable

@Serializable
enum class DayNightState(val label: String) {
    NONE("Set Day/Night"),
    DAY("☀ Day"),
    NIGHT("☾ Night"),
}

@Serializable
data class MtgPlayerState(
    val id: Int,
    val name: String,
    val life: Int,
    val poison: Int = 0,
    val commanderCasts: Int = 0,
    val commanderDamage: Map<Int, Int> = emptyMap(), // opponentPlayerId -> damage taken
)

@Serializable
data class MtgTableState(
    val playerCount: Int = 2,
    val startingLife: Int = 20,
    val players: List<MtgPlayerState> = defaultMtgPlayers(2, 20),
    val showPoison: Boolean = false,
    val showMonarch: Boolean = false,
    val showCommanderDamage: Boolean = false,
    val showCommanderTax: Boolean = false,
    val showDayNight: Boolean = false,
    val dayNight: DayNightState = DayNightState.NONE,
    val monarchPlayerId: Int? = null,
    val firstPlayerId: Int? = null,
)

fun defaultMtgPlayers(
    count: Int,
    startingLife: Int,
    existingNames: Map<Int, String> = emptyMap(),
): List<MtgPlayerState> =
    (1..count.coerceIn(2, 6)).map { idx ->
        MtgPlayerState(
            id = idx,
            name = existingNames[idx]?.takeIf { it.isNotBlank() } ?: "Player $idx",
            life = startingLife,
        )
    }

@Serializable
data class MtgCardRuling(
    val publishedAt: String,
    val comment: String,
)

@Serializable
data class MtgCardDetail(
    val name: String,
    val manaCost: String,
    val typeLine: String,
    val oracleText: String,
    val powerToughness: String? = null,
    val rulings: List<MtgCardRuling> = emptyList(),
    val fromOfflineCache: Boolean = false,
)

data class MtgRulesTopic(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val keyPoints: List<String>,
    val triggers: Set<String>,
)

@Serializable
data class ScorePlayer(
    val id: Int,
    val name: String,
    val score: Int = 0,
    val lastDelta: Int? = null,
)

@Serializable
data class ScoreboardState(
    val gameName: String = "Free Score",
    val targetScore: Int? = null,
    val lowestWins: Boolean = false,
    val players: List<ScorePlayer> = listOf(
        ScorePlayer(1, "Player 1"),
        ScorePlayer(2, "Player 2"),
    ),
)

enum class ScoreboardPreset(
    val label: String,
    val targetScore: Int?,
    val lowestWins: Boolean,
    val defaultPlayers: Int,
    val isNertz: Boolean = false,
) {
    FREE("Free Score", null, false, 2),
    NERTZ("Nertz (100)", 100, false, 4, isNertz = true),
    CATAN("Catan (10 VP)", 10, false, 4),
    UNO("Uno (500)", 500, false, 4),
    SKYJO("Skyjo (Low)", 100, true, 4),
    SCRABBLE("Scrabble", null, false, 2),
    HEARTS("Hearts (Low)", 100, true, 4),
    PHASE10("Phase 10", null, true, 4),
    EUCHRE("Euchre (10)", 10, false, 4),
    RUMMY("Rummy (500)", 500, false, 4),
}

enum class ChessClockPreset(
    val label: String,
    val initialSeconds: Int,
    val incrementSeconds: Int,
) {
    BLITZ_3_2("3+2", 180, 2),
    BLITZ_5_0("5+0", 300, 0),
    RAPID_10_0("10+0", 600, 0),
    RAPID_15_10("15+10", 900, 10),
}
