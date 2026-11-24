package com.customplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class LeagueOption(
    val title: String,
    val slugs: List<String>
)

data class StandingRow(
    val rank: Int,
    val groupName: String,
    val teamName: String,
    val teamLogo: String,
    val played: String,
    val won: String,
    val drawn: String,
    val lost: String,
    val gd: String,
    val points: String
)

data class FixtureRow(
    val leagueOrRound: String,
    val dateTimeText: String,
    val statusText: String,
    val isLive: Boolean,
    val isFinished: Boolean,
    val homeName: String,
    val homeLogo: String,
    val awayName: String,
    val awayLogo: String,
    val centerScore: String
)

class StatsActivity : AppCompatActivity() {

    private lateinit var btnBack: Button
    private lateinit var btnReload: Button
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var rvLeagueTabs: RecyclerView
    private lateinit var layoutStandingHeader: LinearLayout
    private lateinit var rvContent: RecyclerView

    private var mode: String = "STANDINGS"
    private var selectedLeagueIndex: Int = 0

    private val leagues = listOf(
        LeagueOption("Ngoại Hạng Anh", listOf("eng.1")),
        LeagueOption("La Liga", listOf("esp.1")),
        LeagueOption("Serie A", listOf("ita.1")),
        LeagueOption("Bundesliga", listOf("ger.1")),
        LeagueOption("Ligue 1", listOf("fra.1")),
        LeagueOption("Champions League", listOf("uefa.champions")),
        LeagueOption("Europa League", listOf("uefa.europa")),
        LeagueOption("Conference League", listOf("uefa.europa.conf")),
        LeagueOption("V-League 1", listOf("vnm.1", "vie.1"))
    )

    companion object {
        private val logoCache = LruCache<String, Bitmap>(150)

        suspend fun loadLogo(url: String): Bitmap? = withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            logoCache.get(url)?.let { return@withContext it }
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)?.also { bmp ->
                        logoCache.put(url, bmp)
                    }
                }
            }.getOrNull()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        mode = intent.getStringExtra("mode") ?: "STANDINGS"

        btnBack = findViewById(R.id.btnStatsBack)
        btnReload = findViewById(R.id.btnStatsReload)
        tvTitle = findViewById(R.id.tvStatsTitle)
        tvStatus = findViewById(R.id.tvStatsStatus)
        rvLeagueTabs = findViewById(R.id.rvLeagueTabs)
        layoutStandingHeader = findViewById(R.id.layoutStandingHeader)
        rvContent = findViewById(R.id.rvStatsContent)

        tvTitle.text = if (mode == "STANDINGS") "Bảng Xếp Hạng" else "Lịch Thi Đấu & Kết Quả"
        layoutStandingHeader.visibility = if (mode == "STANDINGS") View.VISIBLE else View.GONE

        btnBack.setOnClickListener { finish() }
        btnReload.setOnClickListener { loadSelectedLeagueData() }

        rvLeagueTabs.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvLeagueTabs.adapter = LeagueTabAdapter()

        rvContent.itemAnimator = null
        rvContent.layoutManager = if (mode == "STANDINGS") {
            LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, 3)
        }

        loadSelectedLeagueData()
        rvLeagueTabs.post {
            rvLeagueTabs.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun loadSelectedLeagueData() {
        val league = leagues[selectedLeagueIndex]
        tvStatus.text = "Đang tải dữ liệu ${league.title}..."

        lifecycleScope.launch {
            if (mode == "STANDINGS") {
                val rows = fetchStandings(league)
                if (rows.isEmpty()) {
                    tvStatus.text = "Chưa có dữ liệu BXH cho ${league.title} (Kiểm tra kết nối mạng)"
                } else {
                    tvStatus.text = "${league.title} •${rows.size} câu lạc bộ"
                }
                rvContent.adapter = StandingAdapter(rows)
            } else {
                val fixtures = fetchFixtures(league)
                if (fixtures.isEmpty()) {
                    tvStatus.text = "Chưa có dữ liệu lịch thi đấu cho ${league.title}"
                } else {
                    tvStatus.text = "${league.title} •${fixtures.size} trận đấu vòng hiện tại"
                }
                rvContent.adapter = FixtureAdapter(fixtures)
            }
        }
    }

    private suspend fun fetchJson(urlStr: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return@runCatching null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body)
        }.getOrNull()
    }

    private fun parseEntriesArray(
        entries: JSONArray,
        groupName: String,
        outList: MutableList<StandingRow>
    ) {
        for (eIdx in 0 until entries.length()) {
            val entry = entries.optJSONObject(eIdx) ?: continue
            val teamObj = entry.optJSONObject("team") ?: continue
            val teamName = teamObj.optString(
                "displayName",
                teamObj.optString("shortDisplayName", teamObj.optString("name", "Đội bóng"))
            )
            val logos = teamObj.optJSONArray("logos")
            val logoUrl = if (logos != null && logos.length() > 0) {
                logos.optJSONObject(0)?.optString("href", "") ?: ""
            } else {
                teamObj.optString("logo", "")
            }

            val statsMap = mutableMapOf<String, String>()
            val statsArr = entry.optJSONArray("stats")
            if (statsArr != null) {
                for (sIdx in 0 until statsArr.length()) {
                    val st = statsArr.optJSONObject(sIdx) ?: continue
                    val key = st.optString("name", "")
                    val abbr = st.optString("abbreviation", "")
                    val disp = st.optString("displayValue", st.optString("value", "0"))
                    if (key.isNotBlank()) statsMap[key] = disp
                    if (abbr.isNotBlank()) statsMap[abbr] = disp
                }
            }

            val rankVal = (statsMap["rank"] ?: statsMap["RK"])?.toIntOrNull() ?: (eIdx + 1)
            val gdRaw = statsMap["pointDifferential"] ?:
