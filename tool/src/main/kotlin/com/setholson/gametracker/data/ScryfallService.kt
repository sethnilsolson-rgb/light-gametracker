package com.setholson.gametracker.data

import com.setholson.gametracker.model.MtgCardDetail
import com.setholson.gametracker.model.MtgCardRuling
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, zero-extra-dependency Scryfall API client for official MTG Oracle text & Wizards rulings.
 * No API key required; every fetched card is permanently cached offline in Room by [GameRepository].
 */
object ScryfallService {

    private const val BASE_URL = "https://api.scryfall.com"
    private const val USER_AGENT = "LightPhoneGameTracker/1.2"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class AutocompleteDto(
        val data: List<String> = emptyList(),
    )

    @Serializable
    private data class SearchCardNameDto(
        val name: String = "",
    )

    @Serializable
    private data class SearchListDto(
        val data: List<SearchCardNameDto> = emptyList(),
    )

    @Serializable
    private data class ScryfallErrorDto(
        val type: String? = null,
        val details: String? = null,
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

    fun autocomplete(query: String, limit: Int = 20): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return runCatching {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val body = httpGet("$BASE_URL/cards/autocomplete?q=$encoded")
            json.decodeFromString<AutocompleteDto>(body).data.take(limit)
        }.getOrDefault(emptyList())
    }

    /**
     * Searches Scryfall by card name tokens ordered by EDHREC popularity so partial,
     * multi-word, or out-of-order queries (e.g. "sword fire ice", "bolas", "ajani")
     * reliably return matching card names.
     */
    fun searchNamesByTokens(tokens: List<String>, limit: Int = 20): List<String> {
        val cleanTokens = tokens
            .map { it.replace(Regex("[^a-zA-Z0-9'-]"), "").trim() }
            .filter { it.length >= 2 }
        if (cleanTokens.isEmpty()) return emptyList()
        val scryfallQuery = cleanTokens.joinToString(" ") { tok ->
            if (tok.contains('-') || tok.contains('\'')) "name:\"$tok\"" else "name:$tok"
        }
        return runCatching {
            val encoded = URLEncoder.encode(scryfallQuery, "UTF-8")
            val body = httpGet("$BASE_URL/cards/search?q=$encoded&order=edhrec")
            json.decodeFromString<SearchListDto>(body).data
                .mapNotNull { it.name.takeIf(String::isNotBlank) }
                .distinctBy { it.lowercase(Locale.US) }
                .take(limit)
        }.getOrDefault(emptyList())
    }

    /**
     * Multi-stage card candidate finder for ambiguous, partial, out-of-order, or misspelled queries:
     * 1. Scryfall `/cards/autocomplete` on full query
     * 2. Scryfall `/cards/search` by name tokens (ordered by popularity)
     * 3. Typo-recovery queries on individual tokens & prefixes when exact queries yield too few matches
     * 4. Local offline cached card names + fuzzy Levenshtein & token-overlap ranking
     */
    fun suggestCardNames(
        query: String,
        localCachedNames: List<String> = emptyList(),
        limit: Int = 12,
    ): List<String> {
        val trimmed = query.trim().replace(Regex("\\s+"), " ")
        if (trimmed.length < 2) return emptyList()

        val remotePool = LinkedHashSet<String>()
        val tokens = tokenize(trimmed)

        // Stage 1: Direct Scryfall autocomplete
        autocomplete(trimmed, limit = 20).forEach { remotePool.add(it) }

        // Stage 2: Scryfall name-token search (handles multi-word partial & ambiguous queries)
        if (remotePool.size < 8 && tokens.isNotEmpty()) {
            searchNamesByTokens(tokens, limit = 20).forEach { remotePool.add(it) }
        }

        // Stage 3: Typo / partial-word recovery when direct lookups find fewer than 5 candidates
        if (remotePool.size < 5 && tokens.isNotEmpty()) {
            val probedQueries = HashSet<String>()
            probedQueries.add(trimmed.lowercase(Locale.US))

            // 3a. Try each distinctive word individually (longest first, up to 2 words)
            // e.g. "Lightening Bolt" -> searches "Bolt", finding "Lightning Bolt"
            val significantTokens = tokens
                .filter { it.length >= 3 }
                .sortedByDescending { it.length }
                .take(2)

            for (tok in significantTokens) {
                val lowerTok = tok.lowercase(Locale.US)
                if (probedQueries.add(lowerTok)) {
                    autocomplete(tok, limit = 20).forEach { remotePool.add(it) }
                    if (remotePool.size < 12) {
                        searchNamesByTokens(listOf(tok), limit = 15).forEach { remotePool.add(it) }
                    }
                }
            }

            // 3b. Try shortened prefix of the longest/first token for typos near the middle/end
            // e.g. "Doublng Season" -> "Doub", "Rhystc" -> "Rhys", "Blod" -> "Blo"
            if (remotePool.size < 10) {
                val prefixCandidates = tokens
                    .filter { it.length >= 4 }
                    .flatMap { tok ->
                        val p1 = tok.take(max(3, (tok.length * 2) / 3))
                        val p2 = tok.take(3)
                        listOf(p1, p2).distinct()
                    }
                    .distinct()
                    .take(3)

                for (prefix in prefixCandidates) {
                    val lowerP = prefix.lowercase(Locale.US)
                    if (probedQueries.add(lowerP)) {
                        autocomplete(prefix, limit = 20).forEach { remotePool.add(it) }
                        if (remotePool.size < 12) {
                            searchNamesByTokens(listOf(prefix), limit = 15).forEach { remotePool.add(it) }
                        }
                    }
                }
            }

            // 3c. If single word with typo in the opening letters (e.g. "Bood" or "Xolt"),
            // also probe its trailing 3-4 character suffix
            if (remotePool.size < 5 && tokens.size == 1 && tokens[0].length >= 4) {
                val suffix = tokens[0].takeLast(min(4, tokens[0].length - 1))
                if (probedQueries.add("suf:$suffix")) {
                    searchNamesByTokens(listOf(suffix), limit = 15).forEach { remotePool.add(it) }
                }
            }
        }

        val combined = LinkedHashSet<String>()
        remotePool.forEach { combined.add(it) }
        localCachedNames.forEach { combined.add(it) }

        return rankCardCandidates(
            query = trimmed,
            candidates = combined.toList(),
            remoteConfirmed = remotePool,
            limit = limit,
        )
    }

    /**
     * Ranks candidate MTG card names against [query] using exact/prefix/substring bonuses,
     * token-level Levenshtein matching, and full-string similarity.
     * Exposed as `internal` for unit testing.
     */
    internal fun rankCardCandidates(
        query: String,
        candidates: List<String>,
        remoteConfirmed: Set<String> = emptySet(),
        limit: Int = 12,
    ): List<String> {
        val normQuery = normalizeForMatch(query)
        if (normQuery.length < 2 || candidates.isEmpty()) return emptyList()
        val queryTokens = tokenize(normQuery)

        data class Scored(val name: String, val score: Double, val index: Int)

        val seenKeys = HashSet<String>()
        val scored = ArrayList<Scored>(candidates.size)

        candidates.forEachIndexed { idx, candidate ->
            val key = candidate.trim().lowercase(Locale.US)
            if (key.isBlank() || !seenKeys.add(key)) return@forEachIndexed

            val normCand = normalizeForMatch(candidate)
            val candTokens = tokenize(normCand)

            var score = 0.0
            var hasStrongSignal = false

            if (normCand == normQuery) {
                score += 1000.0
                hasStrongSignal = true
            } else if (normCand.startsWith(normQuery)) {
                score += 520.0 - min(40.0, (normCand.length - normQuery.length) * 0.5)
                hasStrongSignal = true
            } else if (normCand.contains(normQuery)) {
                score += 360.0 - min(30.0, normCand.indexOf(normQuery) * 0.8)
                hasStrongSignal = true
            }

            // Token-level exact / prefix / typo matching
            if (queryTokens.isNotEmpty() && candTokens.isNotEmpty()) {
                var matchedTokens = 0
                var tokenScoreSum = 0.0
                for (qt in queryTokens) {
                    var bestTok = 0.0
                    for (ct in candTokens) {
                        val ts = when {
                            ct == qt -> 150.0
                            ct.startsWith(qt) -> 115.0
                            qt.startsWith(ct) && ct.length >= 3 -> 95.0
                            ct.contains(qt) && qt.length >= 3 -> 85.0
                            else -> {
                                val dist = levenshtein(qt, ct)
                                val maxLen = max(qt.length, ct.length)
                                val allowedDist = when {
                                    maxLen <= 3 -> 1
                                    maxLen <= 6 -> 2
                                    else -> 3
                                }
                                if (dist <= allowedDist) {
                                    val sim = 1.0 - (dist.toDouble() / maxLen.toDouble())
                                    if (sim >= 0.5) sim * 110.0 else 0.0
                                } else {
                                    0.0
                                }
                            }
                        }
                        if (ts > bestTok) bestTok = ts
                    }
                    if (bestTok >= 55.0) matchedTokens++
                    tokenScoreSum += bestTok
                }

                val coverage = matchedTokens.toDouble() / queryTokens.size.toDouble()
                score += tokenScoreSum / queryTokens.size.toDouble() * 1.4
                if (coverage >= 1.0) {
                    score += 160.0
                    hasStrongSignal = true
                } else if (coverage >= 0.5) {
                    score += 65.0
                    hasStrongSignal = true
                }
            }

            // Full-string edit similarity (especially helpful for single-word or spaced typos)
            val fullDist = levenshtein(normQuery, normCand)
            val maxFull = max(normQuery.length, normCand.length).coerceAtLeast(1)
            val fullSim = (1.0 - fullDist.toDouble() / maxFull.toDouble()).coerceAtLeast(0.0)
            score += fullSim * 180.0
            if (fullSim >= 0.55) hasStrongSignal = true

            // Keep remote Scryfall matches even if they matched via prefix/alternate wording,
            // while filtering out unrelated offline-cached cards
            val isRemote = candidate in remoteConfirmed
            if (hasStrongSignal || (isRemote && score >= 35.0) || score >= 90.0) {
                // Small popularity tie-breaker favoring earlier Scryfall results
                val popBonus = if (isRemote) max(0.0, 22.0 - idx * 0.6) else 0.0
                scored.add(Scored(candidate, score + popBonus, idx))
            }
        }

        return scored
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.index })
            .take(limit)
            .map { it.name }
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    private fun normalizeForMatch(s: String): String =
        s.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokenize(s: String): List<String> =
        normalizeForMatch(s)
            .split(' ')
            .filter { it.isNotBlank() }

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
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
                val errDto = errBody?.let {
                    runCatching { json.decodeFromString<ScryfallErrorDto>(it) }.getOrNull()
                }
                if (errDto?.type == "ambiguous") {
                    throw IllegalStateException("Multiple cards match. Pick a card below:")
                }
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
