package com.setholson.gametracker.data

import com.setholson.gametracker.model.MtgCardDetail
import com.setholson.gametracker.model.MtgCardRuling
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Lightweight, zero-extra-dependency Scryfall API client for official MTG Oracle text & Wizards rulings.
 * No API key required; every fetched card is permanently cached offline in Room by [GameRepository].
 */
object ScryfallService {

    private const val BASE_URL = "https://api.scryfall.com"
    private const val USER_AGENT = "LightPhoneGameTracker/1.0"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class AutocompleteDto(
        val data: List<String> = emptyList(),
    )

    @Serializable
    private data class CardFaceDto(
        val name: String = "",
        @SerialName("mana_cost") val manaCost: String = "",
        @SerialName("type_line") val typeLine: String = "",
        @SerialName("oracle_text") val oracleText: String = "",
        val power: String? = null,
        val toughness: String? = null,
    )

    @Serializable
    private data class CardDto(
        val id: String = "",
        val name: String = "",
        @SerialName("mana_cost") val manaCost: String? = null,
        @SerialName("type_line") val typeLine: String = "",
        @SerialName("oracle_text") val oracleText: String? = null,
        val power: String? = null,
        val toughness: String? = null,
        @SerialName("card_faces") val cardFaces: List<CardFaceDto> = emptyList(),
        @SerialName("rulings_uri") val rulingsUri: String? = null,
    )

    @Serializable
    private data class RulingDto(
        @SerialName("published_at") val publishedAt: String = "",
        val comment: String = "",
    )

    @Serializable
    private data class RulingsListDto(
        val data: List<RulingDto> = emptyList(),
    )

    fun autocomplete(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return runCatching {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val body = httpGet("$BASE_URL/cards/autocomplete?q=$encoded")
            json.decodeFromString<AutocompleteDto>(body).data.take(10)
        }.getOrDefault(emptyList())
    }

    fun fetchCardWithRulings(cardName: String): Result<MtgCardDetail> = runCatching {
        val encoded = URLEncoder.encode(cardName.trim(), "UTF-8")
        val cardJson = httpGet("$BASE_URL/cards/named?fuzzy=$encoded")
        val dto = json.decodeFromString<CardDto>(cardJson)

        val manaCost = dto.manaCost?.takeIf { it.isNotBlank() }
            ?: dto.cardFaces.mapNotNull { it.manaCost.takeIf(String::isNotBlank) }.joinToString(" // ")

        val oracleText = dto.oracleText?.takeIf { it.isNotBlank() }
            ?: dto.cardFaces.joinToString("\n—\n") { face ->
                buildString {
                    if (face.name.isNotBlank()) append(face.name)
                    if (face.manaCost.isNotBlank()) append(" ${face.manaCost}")
                    if (face.typeLine.isNotBlank()) append("\n${face.typeLine}")
                    if (face.oracleText.isNotBlank()) append("\n${face.oracleText}")
                    if (!face.power.isNullOrBlank() && !face.toughness.isNullOrBlank()) {
                        append("\n(${face.power}/${face.toughness})")
                    }
                }
            }

        val pt = if (!dto.power.isNullOrBlank() && !dto.toughness.isNullOrBlank()) {
            "${dto.power}/${dto.toughness}"
        } else null

        val rulings = runCatching {
            val uri = dto.rulingsUri?.takeIf { it.isNotBlank() }
                ?: if (dto.id.isNotBlank()) "$BASE_URL/cards/${dto.id}/rulings" else null
            if (uri != null) {
                val rJson = httpGet(uri)
                json.decodeFromString<RulingsListDto>(rJson).data
                    .filter { it.comment.isNotBlank() }
                    .map { MtgCardRuling(publishedAt = it.publishedAt, comment = it.comment) }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        MtgCardDetail(
            name = dto.name,
            manaCost = manaCost,
            typeLine = dto.typeLine,
            oracleText = oracleText,
            powerToughness = pt,
            rulings = rulings,
            fromOfflineCache = false,
        )
    }

    private fun httpGet(urlString: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URI(urlString).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == 404) {
                throw IllegalStateException("Card not found. Check spelling or pick from suggestions.")
            }
            if (code !in 200..299) {
                throw IllegalStateException("Scryfall lookup failed (HTTP $code). Check internet connection.")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }
}
