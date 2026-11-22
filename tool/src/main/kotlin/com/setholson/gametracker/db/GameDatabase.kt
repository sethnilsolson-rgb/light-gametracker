package com.setholson.gametracker.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "cached_mtg_cards")
data class CachedMtgCardEntity(
    @PrimaryKey
    val nameKey: String,
    val displayName: String,
    val manaCost: String,
    val typeLine: String,
    val oracleText: String,
    val powerToughness: String,
    val rulingsJson: String,
    val cachedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "saved_game_states")
data class SavedGameStateEntity(
    @PrimaryKey
    val stateKey: String,
    val payloadJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface GameDao {
    @Query("SELECT * FROM cached_mtg_cards WHERE nameKey = :nameKey LIMIT 1")
    suspend fun getCardExact(nameKey: String): CachedMtgCardEntity?

    @Query("SELECT * FROM cached_mtg_cards WHERE nameKey LIKE :prefix || '%' ORDER BY cachedAt DESC LIMIT 12")
    suspend fun searchCardsPrefix(prefix: String): List<CachedMtgCardEntity>

    @Query("SELECT * FROM cached_mtg_cards ORDER BY cachedAt DESC LIMIT 40")
    suspend fun getRecentCards(): List<CachedMtgCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCard(card: CachedMtgCardEntity)

    @Query("SELECT payloadJson FROM saved_game_states WHERE stateKey = :key LIMIT 1")
    suspend fun getStateJson(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(entity: SavedGameStateEntity)
}

@Database(
    entities = [CachedMtgCardEntity::class, SavedGameStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class GameDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
}
