package com.setholson.gametracker.data

import com.setholson.gametracker.db.CachedMtgCardEntity
import com.setholson.gametracker.db.GameDatabase
import com.setholson.gametracker.db.SavedGameStateEntity
import com.setholson.gametracker.model.MoodSwingsState
import com.setholson.gametracker.model.MtgCardDetail
import com.setholson.gametracker.model.MtgCardRuling
import com.setholson.gametracker.model.MtgTableState
import com.setholson.gametracker.model.ScoreboardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

class GameRepository(
    private val database: GameDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun normalizeCardKey(name: String): String =
        name.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    // -------------------------------------------------------------------------
    // Persistent MTG & Scoreboard States (zero battery cost, survives restarts)
    // -------------------------------------------------------------------------

    suspend fun loadMtgState(): MtgTableState? = withContext(Dispatchers.IO) {
        val raw = database.gameDao().getStateJson(KEY_MTG_STATE) ?: return@withContext null
        runCatching { json.decodeFromString<MtgTableState>(raw) }.getOrNull()
    }

    suspend fun saveMtgState(state: MtgTableState) = withContext(Dispatchers.IO) {
        runCatching {
            database.gameDao().saveState(
                SavedGameStateEntity(KEY_MTG_STATE, json.encodeToString(state))
            )
        }
    }

    suspend fun loadScoreboardState(): ScoreboardState? = withContext(Dispatchers.IO) {
        val raw = database.gameDao().getStateJson(KEY_SCOREBOARD_STATE) ?: return@withContext null
        runCatching { json.decodeFromString<ScoreboardState>(raw) }.getOrNull()
    }

    suspend fun saveScoreboardState(state: ScoreboardState) = withContext(Dispatchers.IO) {
        runCatching {
            database.gameDao().saveState(
                SavedGameStateEntity(KEY_SCOREBOARD_STATE, json.encodeToString(state))
            )
        }
    }

    suspend fun loadMoodSwingsState(): MoodSwingsState? = withContext(Dispatchers.IO) {
        val raw = database.gameDao().getStateJson(KEY_MOOD_SWINGS_STATE) ?: return@withContext null
        runCatching { json.decodeFromString<MoodSwingsState>(raw) }.getOrNull()
    }

    suspend fun saveMoodSwingsState(state: MoodSwingsState) = withContext(Dispatchers.IO) {
        runCatching {
            database.gameDao().saveState(
                SavedGameStateEntity(KEY_MOOD_SWINGS_STATE, json.encodeToString(state))
            )
        }
    }

    // -------------------------------------------------------------------------
    // MTG Card & Official Rulings (Offline Cache First -> Scryfall On Demand)
    // -------------------------------------------------------------------------

    suspend fun getRecentCachedCards(): List<MtgCardDetail> = withContext(Dispatchers.IO) {
        database.gameDao().getRecentCards().map { it.toDetail() }
    }

    suspend fun autocompleteCardNames(query: String): List<String> = withContext(Dispatchers.IO) {
        val key = normalizeCardKey(query)
        if (key.length < 2) return@withContext emptyList()
        val local = database.gameDao().searchCardsPrefix(key).map { it.displayName }
        val remote = ScryfallService.autocomplete(query)
        (local + remote).distinctBy { it.lowercase(Locale.US) }.take(10)
    }

    suspend fun lookupCard(query: String, forceRefresh: Boolean = false): Result<MtgCardDetail> =
        withContext(Dispatchers.IO) {
            val key = normalizeCardKey(query)
            if (key.isBlank()) return@withContext Result.failure(IllegalArgumentException("Enter a card name."))

            if (!forceRefresh) {
                database.gameDao().getCardExact(key)?.let { cached ->
                    return@withContext Result.success(cached.toDetail())
                }
            }

            val remote = ScryfallService.fetchCardWithRulings(query)
            remote.onSuccess { detail ->
                val entity = CachedMtgCardEntity(
                    nameKey = normalizeCardKey(detail.name),
                    displayName = detail.name,
                    manaCost = detail.manaCost,
                    typeLine = detail.typeLine,
                    oracleText = detail.oracleText,
                    powerToughness = detail.powerToughness.orEmpty(),
                    rulingsJson = json.encodeToString(detail.rulings),
                )
                database.gameDao().upsertCard(entity)
            }
            remote
        }

    private fun CachedMtgCardEntity.toDetail(): MtgCardDetail {
        val parsedRulings = runCatching {
            json.decodeFromString<List<MtgCardRuling>>(rulingsJson)
        }.getOrDefault(emptyList())
        return MtgCardDetail(
            name = displayName,
            manaCost = manaCost,
            typeLine = typeLine,
            oracleText = oracleText,
            powerToughness = powerToughness.ifBlank { null },
            rulings = parsedRulings,
            fromOfflineCache = true,
        )
    }

    companion object {
        const val DATABASE_NAME = "light_gametracker.db"
        private const val KEY_MTG_STATE = "mtg_table_state"
        private const val KEY_SCOREBOARD_STATE = "scoreboard_state"
        private const val KEY_MOOD_SWINGS_STATE = "mood_swings_state"

        @Volatile
        private var instance: GameRepository? = null

        fun getInstance(dbProvider: () -> GameDatabase): GameRepository {
            return instance ?: synchronized(this) {
                instance ?: GameRepository(dbProvider()).also { instance = it }
            }
        }
    }
}
