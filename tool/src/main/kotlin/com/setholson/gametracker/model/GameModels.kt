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
    val isTableView: Boolean = false,
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

// -----------------------------------------------------------------------------
// Mood Swings (2-4 Players or 2v2 Teams, First to 3 Round Wins)
// -----------------------------------------------------------------------------

@Serializable
enum class MoodSwingsMode(
    val label: String,
    val playerCount: Int,
    val isTeams: Boolean = false,
) {
    TWO_PLAYER("2P", 2, false),
    THREE_PLAYER("3P", 3, false),
    FOUR_PLAYER("4P", 4, false),
    TWO_VS_TWO("2v2", 2, true),
}

@Serializable
data class MoodSwingsPlayer(
    val id: Int,
    val name: String,
    val roundWins: Int = 0,
    val currentRoundScore: Int = 0,
)

@Serializable
data class MoodSwingsRoundRecord(
    val roundNumber: Int,
    val winnerName: String,
    val scoresSummary: String,
    val wonByTiebreak: Boolean = false,
    val hurtFeelingsName: String? = null,
)

@Serializable
data class MoodSwingsState(
    val mode: MoodSwingsMode = MoodSwingsMode.TWO_PLAYER,
    val roundNumber: Int = 1,
    val targetWins: Int = 3,
    val firstPlayerIndex: Int = 0,
    val isTableView: Boolean = true,
    val players: List<MoodSwingsPlayer> = defaultMoodSwingsPlayers(MoodSwingsMode.TWO_PLAYER),
    val hurtFeelingsPlayerId: Int? = null,
    val winnerId: Int? = null,
    val lastRoundBanner: String = "",
    val history: List<MoodSwingsRoundRecord> = emptyList(),
)

fun defaultMoodSwingsPlayers(
    mode: MoodSwingsMode,
    existingNames: Map<Int, String> = emptyMap(),
): List<MoodSwingsPlayer> =
    (1..mode.playerCount).map { idx ->
        val fallback = if (mode.isTeams) "Team $idx" else "Player $idx"
        val custom = existingNames[idx]?.takeIf { name ->
            name.isNotBlank() &&
                !name.matches(Regex("^(Player|Team) \\d+$"))
        }
        MoodSwingsPlayer(
            id = idx,
            name = custom ?: fallback,
        )
    }

/**
 * Returns 0-based turn order rank for [playerIndex] given [firstPlayerIndex] (0 = 1st, 1 = 2nd, ...).
 */
fun moodSwingsTurnRank(playerIndex: Int, firstPlayerIndex: Int, playerCount: Int): Int {
    if (playerCount <= 0) return 0
    val safeFirst = ((firstPlayerIndex % playerCount) + playerCount) % playerCount
    return (playerIndex - safeFirst + playerCount) % playerCount
}

/**
 * Resolves a Mood Swings round according to official rules:
 * - Highest total mood score wins the round.
 * - Tiebreaker for highest: earliest player in turn order this round wins.
 * - Losers draw 1 card (winner does not draw).
 * - In 3-4 player FFA: lowest score receives Hurt Feelings (extra mood next turn);
 *   tiebreaker for lowest goes to the player who went LATEST in turn order.
 * - Round winner goes 1st next round; first to [MoodSwingsState.targetWins] (3) wins the match.
 */
fun resolveMoodSwingsRound(state: MoodSwingsState): MoodSwingsState {
    if (state.winnerId != null || state.players.isEmpty()) return state
    val count = state.players.size

    val indexed = state.players.mapIndexed { idx, player ->
        Triple(idx, player, moodSwingsTurnRank(idx, state.firstPlayerIndex, count))
    }

    // Winner: highest score, ties broken by earliest turn rank (lowest rank)
    val winnerTriple = indexed.minWith(
        compareByDescending<Triple<Int, MoodSwingsPlayer, Int>> { it.second.currentRoundScore }
            .thenBy { it.third },
    )
    val winnerIdx = winnerTriple.first
    val winner = winnerTriple.second
    val wonByTiebreak = state.players.count { it.currentRoundScore == winner.currentRoundScore } > 1

    // Hurt Feelings (3-4p FFA only): lowest score, ties broken by latest turn rank (highest rank)
    val hurtPlayer: MoodSwingsPlayer? = if (!state.mode.isTeams && count >= 3) {
        indexed
            .filter { it.first != winnerIdx }
            .minWithOrNull(
                compareBy<Triple<Int, MoodSwingsPlayer, Int>> { it.second.currentRoundScore }
                    .thenByDescending { it.third },
            )
            ?.second
    } else {
        null
    }

    val scoresSummary = state.players.joinToString(" · ") { "${it.name.take(8)} ${it.currentRoundScore}" }
    // Keep each player's mood score between rounds (moods remain on the board round-to-round)
    val updatedPlayers = state.players.map { p ->
        val nextWins = if (p.id == winner.id) p.roundWins + 1 else p.roundWins
        p.copy(roundWins = nextWins)
    }

    val newWinnerWins = updatedPlayers[winnerIdx].roundWins
    val matchWinnerId = if (newWinnerWins >= state.targetWins) winner.id else null

    val banner = if (matchWinnerId != null) {
        "${winner.name} wins the match!"
    } else {
        buildString {
            append("R${state.roundNumber}: ${winner.name} wins")
            if (wonByTiebreak) append(" (tiebreak)")
            append(" · Others draw 1")
            if (hurtPlayer != null) {
                append(" · ${hurtPlayer.name}: Hurt Feelings")
            }
        }
    }

    val record = MoodSwingsRoundRecord(
        roundNumber = state.roundNumber,
        winnerName = winner.name,
        scoresSummary = scoresSummary,
        wonByTiebreak = wonByTiebreak,
        hurtFeelingsName = hurtPlayer?.name,
    )

    return state.copy(
        roundNumber = if (matchWinnerId != null) state.roundNumber else state.roundNumber + 1,
        firstPlayerIndex = winnerIdx,
        players = updatedPlayers,
        hurtFeelingsPlayerId = if (matchWinnerId != null) null else hurtPlayer?.id,
        winnerId = matchWinnerId,
        lastRoundBanner = banner,
        history = (listOf(record) + state.history).take(12),
    )
}
