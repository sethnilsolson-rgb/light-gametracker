package com.setholson.gametracker

import com.setholson.gametracker.data.MtgRulesGuide
import com.setholson.gametracker.model.MtgCardDetail
import com.setholson.gametracker.model.MtgTableState
import com.setholson.gametracker.model.ScorePlayer
import com.setholson.gametracker.model.ScoreboardState
import com.setholson.gametracker.model.defaultMtgPlayers
import com.setholson.gametracker.ui.formatClockMillis
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTrackerTest {

    @Test
    fun detectInteractions_deathtouchAndTrample() {
        val cardA = MtgCardDetail(
            name = "Questing Beast",
            manaCost = "{2}{G}{G}",
            typeLine = "Legendary Creature — Beast",
            oracleText = "Vigilance, deathtouch, haste",
        )
        val cardB = MtgCardDetail(
            name = "Rancor",
            manaCost = "{G}",
            typeLine = "Enchantment — Aura",
            oracleText = "Enchanted creature gets +2/+0 and has trample.",
        )

        val topics = MtgRulesGuide.detectInteractions(cardA, cardB)
        assertTrue(topics.any { it.id == "deathtouch_trample" })
    }

    @Test
    fun detectInteractions_bloodMoonAndUrborg() {
        val bloodMoon = MtgCardDetail(
            name = "Blood Moon",
            manaCost = "{2}{R}",
            typeLine = "Enchantment",
            oracleText = "Nonbasic lands are Mountains.",
        )
        val urborg = MtgCardDetail(
            name = "Urborg, Tomb of Yawgmoth",
            manaCost = "",
            typeLine = "Legendary Land",
            oracleText = "Each land is a Swamp in addition to its other land types.",
        )

        val topics = MtgRulesGuide.detectInteractions(bloodMoon, urborg)
        assertEquals("layers_timestamps", topics.first().id)
    }

    @Test
    fun detectInteractions_indestructibleVsMinusCounters() {
        val indestructible = MtgCardDetail(
            name = "Blightsteel Colossus",
            manaCost = "{12}",
            typeLine = "Artifact Creature — Phyrexian Golem",
            oracleText = "Trample, infect, indestructible",
        )
        val toxicDeluge = MtgCardDetail(
            name = "Toxic Deluge",
            manaCost = "{2}{B}",
            typeLine = "Sorcery",
            oracleText = "As an additional cost to cast this spell, pay X life. All creatures get -X/-X until end of turn.",
        )

        val topics = MtgRulesGuide.detectInteractions(indestructible, toxicDeluge)
        assertTrue(topics.any { it.id == "indestructible" })
    }

    @Test
    fun searchTopics_findsMonarchAndProtection() {
        val monarchResults = MtgRulesGuide.searchTopics("monarch")
        assertTrue(monarchResults.any { it.id == "monarch_initiative" })

        val protectionResults = MtgRulesGuide.searchTopics("D.E.B.T.")
        assertTrue(protectionResults.any { it.id == "protection_debt" })
    }

    @Test
    fun mtgAndScoreboardStates_serializeRoundTrip() {
        val json = Json { ignoreUnknownKeys = true }
        val mtg = MtgTableState(
            playerCount = 4,
            startingLife = 40,
            players = defaultMtgPlayers(4, 40).mapIndexed { i, p ->
                if (i == 0) p.copy(life = 34, poison = 3, commanderDamage = mapOf(2 to 7)) else p
            },
            showPoison = true,
            showMonarch = true,
            showCommanderDamage = true,
            monarchPlayerId = 2,
            firstPlayerId = 3,
        )
        val encodedMtg = json.encodeToString(mtg)
        val decodedMtg = json.decodeFromString<MtgTableState>(encodedMtg)
        assertEquals(mtg, decodedMtg)

        val board = ScoreboardState(
            gameName = "Catan (10 VP)",
            targetScore = 10,
            lowestWins = false,
            players = listOf(ScorePlayer(1, "Seth", 7, 2), ScorePlayer(2, "Alex", 5, 1)),
        )
        val encodedBoard = json.encodeToString(board)
        val decodedBoard = json.decodeFromString<ScoreboardState>(encodedBoard)
        assertEquals(board, decodedBoard)
    }

    @Test
    fun formatClockMillis_formatsMinutesAndTenths() {
        assertEquals("5:00", formatClockMillis(300_000L))
        assertEquals("0:20", formatClockMillis(20_000L))
        assertEquals("0:19.5", formatClockMillis(19_500L))
        assertEquals("0:00.0", formatClockMillis(0L))
    }

    @Test
    fun customPlayersAndDayNight_preservedInModelsAndGuide() {
        val players = defaultMtgPlayers(
            count = 5,
            startingLife = 40,
            existingNames = mapOf(1 to "Seth", 2 to "Alex"),
        )
        assertEquals(5, players.size)
        assertEquals("Seth", players[0].name)
        assertEquals("Alex", players[1].name)
        assertEquals("Player 5", players[4].name)

        val dayNightResults = MtgRulesGuide.searchTopics("daybound")
        assertTrue(dayNightResults.any { it.id == "day_night" })
    }

    @Test
    fun moodSwings_resolvesTiebreakByTurnOrderAndHurtFeelingsByLatestTurn() {
        // 3-player game where Player 2 (index 1) went 1st:
        // Turn order: P2 (1st), P3 (2nd), P1 (3rd).
        // P1 and P3 tie for highest (12); P3 went earlier (2nd vs 3rd), so P3 wins the tiebreak!
        // P2 has the lowest (8), so P2 gets Hurt Feelings.
        val initial = com.setholson.gametracker.model.MoodSwingsState(
            mode = com.setholson.gametracker.model.MoodSwingsMode.THREE_PLAYER,
            roundNumber = 1,
            firstPlayerIndex = 1,
            players = listOf(
                com.setholson.gametracker.model.MoodSwingsPlayer(1, "Seth", roundWins = 0, currentRoundScore = 12),
                com.setholson.gametracker.model.MoodSwingsPlayer(2, "Alex", roundWins = 0, currentRoundScore = 8),
                com.setholson.gametracker.model.MoodSwingsPlayer(3, "Maya", roundWins = 0, currentRoundScore = 12),
            ),
        )

        val afterR1 = com.setholson.gametracker.model.resolveMoodSwingsRound(initial)
        assertEquals(2, afterR1.roundNumber)
        assertEquals(2, afterR1.firstPlayerIndex) // Maya (index 2) won -> goes 1st next round
        assertEquals(1, afterR1.players[2].roundWins)
        assertEquals(12, afterR1.players[0].currentRoundScore) // scores persist between rounds!
        assertEquals(8, afterR1.players[1].currentRoundScore)
        assertEquals(12, afterR1.players[2].currentRoundScore)
        assertEquals(2, afterR1.hurtFeelingsPlayerId) // Alex had lowest
        assertTrue(afterR1.history.first().wonByTiebreak)
        assertEquals("Alex", afterR1.history.first().hurtFeelingsName)

        // Round 2: Maya (index 2) is 1st -> order: Maya (1st), Seth (2nd), Alex (3rd).
        // Maya scores 15, Seth & Alex tie for lowest at 9 -> Alex went LATEST (3rd) so Alex gets Hurt Feelings!
        val r2Input = afterR1.copy(
            players = afterR1.players.map { p ->
                when (p.id) {
                    3 -> p.copy(currentRoundScore = 15)
                    else -> p.copy(currentRoundScore = 9)
                }
            },
        )
        val afterR2 = com.setholson.gametracker.model.resolveMoodSwingsRound(r2Input)
        assertEquals(2, afterR2.players[2].roundWins)
        assertEquals(2, afterR2.hurtFeelingsPlayerId) // Alex went 3rd (latest) among tied-for-lowest

        // Round 3: Maya wins 3rd round -> match winner!
        val r3Input = afterR2.copy(
            players = afterR2.players.map { p ->
                if (p.id == 3) p.copy(currentRoundScore = 20) else p.copy(currentRoundScore = 10)
            },
        )
        val afterR3 = com.setholson.gametracker.model.resolveMoodSwingsRound(r3Input)
        assertEquals(3, afterR3.winnerId)
        assertEquals(3, afterR3.players[2].roundWins)
        assertEquals(null, afterR3.hurtFeelingsPlayerId)
    }

    @Test
    fun moodSwings_2PlayerAnd2v2DoNotAssignHurtFeelings() {
        val twoPlayer = com.setholson.gametracker.model.MoodSwingsState(
            mode = com.setholson.gametracker.model.MoodSwingsMode.TWO_PLAYER,
            players = listOf(
                com.setholson.gametracker.model.MoodSwingsPlayer(1, "Seth", currentRoundScore = 14),
                com.setholson.gametracker.model.MoodSwingsPlayer(2, "Alex", currentRoundScore = 9),
            ),
        )
        val next = com.setholson.gametracker.model.resolveMoodSwingsRound(twoPlayer)
        assertEquals(1, next.players[0].roundWins)
        assertEquals(null, next.hurtFeelingsPlayerId)
    }
}
