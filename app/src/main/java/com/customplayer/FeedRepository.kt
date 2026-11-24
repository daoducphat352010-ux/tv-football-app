package com.customplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class FeedRepository(
    private val userAgent: String =
        "Mozilla/5.0 (Linux; Android 13; Smart TV) AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36"
) {
    private val logoCache = ConcurrentHashMap<String, Bitmap>()

    suspend fun loadFromUrlOrAsset(context: Context, feedUrl: String): Result<LoadedFeed> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = feedUrl.trim()
                val rawJson = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    downloadText(trimmed)
                } else {
                    context.assets.open("sample_feed.json").bufferedReader().use { it.readText() }
                }
                parseFeedJson(rawJson)
            }
        }

    fun parseFeedJson(rawJson: String): LoadedFeed {
        val root = JSONObject(rawJson)
        val title = root.optString("app_title", "Live Matches").trim()
        val updatedAt = root.optString("updated_at", "").trim()
        val matchesArr: JSONArray = root.optJSONArray("matches") ?: JSONArray()

        val result = mutableListOf<MatchCard>()
        for (i in 0 until matchesArr.length()) {
            val obj = matchesArr.optJSONObject(i) ?: continue
            val streamsArr: JSONArray = obj.optJSONArray("streams") ?: JSONArray()
            val streams = mutableListOf<StreamOption>()

            for (j in 0 until streamsArr.length()) {
                val sObj = streamsArr.optJSONObject(j) ?: continue
                val url = sObj.optString("url", "").trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                streams.add(
                    StreamOption(
                        label = sObj.optString("label", "Server ${j + 1}").trim(),
                        url = url,
                        referer = sObj.optString("referer", "").trim().ifBlank { null }
                    )
                )
            }

            result.add(
                MatchCard(
                    id = obj.optString("id", "match_$i").trim(),
                    league = obj.optString("league", "Football").trim(),
                    status = obj.optString("status", "UPCOMING").trim().uppercase(),
                    kickoff = obj.optString("kickoff", "--:--").trim(),
                    homeName = obj.optString("home_name", "Home").trim(),
                    homeLogo = obj.optString("home_logo", "").trim().ifBlank { null },
                    awayName = obj.optString("away_name", "Away").trim(),
                    awayLogo = obj.optString("away_logo", "").trim().ifBlank { null },
                    score = obj.optString("score", "vs").trim(),
                    streams = streams
                )
            )
        }

        return LoadedFeed(title = title, updatedAt = updatedAt, matches = result)
    }

    suspend fun fetchBitmap(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        logoCache[url]?.let { return@withContext it }

        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", userAgent)
            }
            val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            conn.disconnect()
            if (bmp != null) logoCache[url] = bmp
            bmp
        }.getOrNull()
    }

    private fun downloadText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", userAgent)
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
