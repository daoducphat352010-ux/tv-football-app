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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class LeagueOption(
    val title: String,
    val espnSlugs: List<String>,
    val footballDataCode: String? = null,
    val sportsDbLeagueId: String? = null
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
    val goalsFor: String,
    val goalsAgainst: String,
    val gd: String,
    val points: String
)

data class FixtureRow(
    val leagueOrRound: String,
    val dateTimeText: String,
    val epochMillis: Long,
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

    // Tùy chọn: điền API key miễn phí từ football-data.org nếu muốn ưu tiên thêm cho các giải hỗ trợ
    private val footballDataApiKey: String = "8ae213ca347e4eae893ed3f3df740efd"

    private val leagues = listOf(
        LeagueOption("Ngoại Hạng Anh", listOf("eng.1"), footballDataCode = "PL", sportsDbLeagueId = "4328"),
        LeagueOption("La Liga", listOf("esp.1"), footballDataCode = "PD", sportsDbLeagueId = "4335"),
        LeagueOption("Serie A", listOf("ita.1"), footballDataCode = "SA", sportsDbLeagueId = "4332"),
        LeagueOption("Bundesliga", listOf("ger.1"), footballDataCode = "BL1", sportsDbLeagueId = "4331"),
        LeagueOption("Ligue 1", listOf("fra.1"), footballDataCode = "FL1", sportsDbLeagueId = "4334"),
        LeagueOption("Champions League", listOf("uefa.champions"), footballDataCode = "CL", sportsDbLeagueId = "4480"),
        LeagueOption("Europa League", listOf("uefa.europa"), sportsDbLeagueId = "4481"),
        LeagueOption("Conference League", listOf("uefa.europa.conf")),
        LeagueOption("V-League 1", listOf("vnm.1", "vie.1"), sportsDbLeagueId = "4929")
    )

    companion object {
        private val logoCache = LruCache<String, Bitmap>(160)

        suspend fun loadLogo(url: String): Bitmap? = withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            logoCache.get(url)?.let { return@withContext it }
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
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
                val rows = fetchStandingsMultiSource(league)
                tvStatus.text = if (rows.isEmpty()) {
                    "Chưa có dữ liệu bảng xếp hạng cho ${league.title}"
                } else {
                    "${league.title} • ${rows.size} câu lạc bộ"
                }
                rvContent.adapter = StandingAdapter(rows)
            } else {
                val fixtures = fetchFixturesMultiSource(league)
                tvStatus.text = if (fixtures.isEmpty()) {
                    "Chưa có dữ liệu lịch thi đấu cho ${league.title}"
                } else {
                    "${league.title} • ${fixtures.size} trận (kết quả & lịch đá)"
                }
                rvContent.adapter = FixtureAdapter(fixtures)
            }
        }
    }

    private suspend fun fetchJson(
        urlStr: String,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
            }
            if (conn.responseCode !in 200..299) return@runCatching null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body)
        }.getOrNull()
    }

    private fun formatGoalDiff(raw: String, gfStr: String, gaStr: String): String {
        val cleanRaw = raw.trim()
        val chosen = if (cleanRaw.isNotEmpty() && cleanRaw != "0") {
            cleanRaw
        } else {
            val gf = gfStr.toIntOrNull()
            val ga = gaStr.toIntOrNull()
            if (gf != null && ga != null) (gf - ga).toString() else cleanRaw.ifEmpty { "0" }
        }
        return if (!chosen.startsWith("-") && !chosen.startsWith("+") && chosen != "0") {
            "+$chosen"
        } else chosen
    }

    private fun parseEspnEntriesArray(
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
            val gfVal = statsMap["pointsFor"] ?: statsMap["GF"] ?: statsMap["F"] ?: "0"
            val gaVal = statsMap["pointsAgainst"] ?: statsMap["GA"] ?: statsMap["A"] ?: "0"
            val gdRaw = statsMap["pointDifferential"] ?: statsMap["GD"] ?: "0"

            outList.add(
                StandingRow(
                    rank = rankVal,
                    groupName = groupName,
                    teamName = teamName,
                    teamLogo = logoUrl,
                    played = statsMap["gamesPlayed"] ?: statsMap["GP"] ?: "0",
                    won = statsMap["wins"] ?: statsMap["W"] ?: "0",
                    drawn = statsMap["ties"] ?: statsMap["D"] ?: "0",
                    lost = statsMap["losses"] ?: statsMap["L"] ?: "0",
                    goalsFor = gfVal,
                    goalsAgainst = gaVal,
                    gd = formatGoalDiff(gdRaw, gfVal, gaVal),
                    points = statsMap["points"] ?: statsMap["P"] ?: "0"
                )
            )
        }
    }

    private suspend fun fetchEspnStandings(league: LeagueOption): List<StandingRow> {
        for (slug in league.espnSlugs) {
            val url = "https://site.api.espn.com/apis/v2/sports/soccer/$slug/standings"
            val root = fetchJson(url) ?: continue
            val result = mutableListOf<StandingRow>()

            val children = root.optJSONArray("children")
            if (children != null) {
                for (cIdx in 0 until children.length()) {
                    val child = children.optJSONObject(cIdx) ?: continue
                    val groupName = child.optString("name", "")
                    val entries = child.optJSONObject("standings")?.optJSONArray("entries")
                    if (entries != null) {
                        parseEspnEntriesArray(entries, groupName, result)
                    }
                }
            }

            if (result.isEmpty()) {
                val directEntries = root.optJSONObject("standings")?.optJSONArray("entries")
                if (directEntries != null) {
                    parseEspnEntriesArray(directEntries, league.title, result)
                }
            }

            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private suspend fun fetchFootballDataStandings(code: String): List<StandingRow> {
        if (footballDataApiKey.isBlank()) return emptyList()
        val url = "https://api.football-data.org/v4/competitions/$code/standings"
        val root = fetchJson(url, mapOf("X-Auth-Token" to footballDataApiKey)) ?: return emptyList()
        val standingsArr = root.optJSONArray("standings") ?: return emptyList()

        var totalObj: JSONObject? = null
        for (i in 0 until standingsArr.length()) {
            val obj = standingsArr.optJSONObject(i) ?: continue
            if (obj.optString("type") == "TOTAL") {
                totalObj = obj
                break
            }
        }
        val tableArr = (totalObj ?: standingsArr.optJSONObject(0))?.optJSONArray("table")
            ?: return emptyList()

        val result = mutableListOf<StandingRow>()
        for (i in 0 until tableArr.length()) {
            val item = tableArr.optJSONObject(i) ?: continue
            val teamObj = item.optJSONObject("team") ?: continue
            val teamName = teamObj.optString("shortName").ifBlank {
                teamObj.optString("name", "Đội bóng")
            }
            val gf = item.optInt("goalsFor", 0)
            val ga = item.optInt("goalsAgainst", 0)
            val gdVal = item.optInt("goalDifference", gf - ga)

            result.add(
                StandingRow(
                    rank = item.optInt("position", i + 1),
                    groupName = "",
                    teamName = teamName,
                    teamLogo = teamObj.optString("crest", ""),
                    played = item.optInt("playedGames", 0).toString(),
                    won = item.optInt("won", 0).toString(),
                    drawn = item.optInt("draw", 0).toString(),
                    lost = item.optInt("lost", 0).toString(),
                    goalsFor = gf.toString(),
                    goalsAgainst = ga.toString(),
                    gd = formatGoalDiff(gdVal.toString(), gf.toString(), ga.toString()),
                    points = item.optInt("points", 0).toString()
                )
            )
        }
        return result
    }

    private suspend fun fetchSportsDbStandings(leagueId: String): List<StandingRow> {
        val url = "https://www.thesportsdb.com/api/v1/json/3/lookuptable.php?l=$leagueId"
        val root = fetchJson(url) ?: return emptyList()
        val tableArr = root.optJSONArray("table") ?: return emptyList()

        val result = mutableListOf<StandingRow>()
        for (i in 0 until tableArr.length()) {
            val item = tableArr.optJSONObject(i) ?: continue
            val gf = item.optString("intGoalsFor", "0")
            val ga = item.optString("intGoalsAgainst", "0")
            val gd = item.optString("intGoalDifference", "0")
            result.add(
                StandingRow(
                    rank = item.optString("intRank", "${i + 1}").toIntOrNull() ?: (i + 1),
                    groupName = item.optString("strGroup", ""),
                    teamName = item.optString("strTeam", "Đội bóng"),
                    teamLogo = item.optString("strBadge", ""),
                    played = item.optString("intPlayed", "0"),
                    won = item.optString("intWin", "0"),
                    drawn = item.optString("intDraw", "0"),
                    lost = item.optString("intLoss", "0"),
                    goalsFor = gf,
                    goalsAgainst = ga,
                    gd = formatGoalDiff(gd, gf, ga),
                    points = item.optString("intPoints", "0")
                )
            )
        }
        return result
    }

    private suspend fun fetchStandingsMultiSource(league: LeagueOption): List<StandingRow> {
        if (league.footballDataCode != null && footballDataApiKey.isNotBlank()) {
            val fdRows = fetchFootballDataStandings(league.footballDataCode)
            if (fdRows.isNotEmpty()) return fdRows
        }
        val espnRows = fetchEspnStandings(league)
        if (espnRows.isNotEmpty()) return espnRows

        if (league.sportsDbLeagueId != null) {
            val sdbRows = fetchSportsDbStandings(league.sportsDbLeagueId)
            if (sdbRows.isNotEmpty()) return sdbRows
        }
        return emptyList()
    }

    private fun parseDateMillis(
        rawDate: String,
        isoShort: SimpleDateFormat,
        isoFull: SimpleDateFormat
    ): Long {
        if (rawDate.isBlank()) return 0L
        val parsed = runCatching { isoShort.parse(rawDate) }.getOrNull()
            ?: runCatching { isoFull.parse(rawDate) }.getOrNull()
            ?: runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(rawDate.take(10))
            }.getOrNull()
        return parsed?.time ?: 0L
    }

    private fun parseEspnEventsFromRoot(
        root: JSONObject,
        leagueTitle: String,
        isoFmtShort: SimpleDateFormat,
        isoFmtFull: SimpleDateFormat,
        outTimeFmt: SimpleDateFormat,
        seenKeys: MutableSet<String>,
        outList: MutableList<FixtureRow>
    ) {
        val events = root.optJSONArray("events") ?: return
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val eventId = ev.optString("id", "")
            val rawDate = ev.optString("date", "")
            val millis = parseDateMillis(rawDate, isoFmtShort, isoFmtFull)
            val timeStr = if (millis > 0L) outTimeFmt.format(Date(millis)) else rawDate

            val comps = ev.optJSONArray("competitions") ?: continue
            if (comps.length() == 0) continue
            val comp = comps.optJSONObject(0) ?: continue

            val statusObj = comp.optJSONObject("status")?.optJSONObject("type")
            val state = statusObj?.optString("state", "pre") ?: "pre"
            val shortDetail = statusObj?.optString("shortDetail", "") ?: ""

            val isLive = state == "in"
            val isFinished = state == "post"
            val badgeText = when {
                isLive -> shortDetail.ifBlank { "LIVE" }
                isFinished -> "KẾT THÚC"
                else -> "SẮP ĐÁ"
            }

            val competitors = comp.optJSONArray("competitors") ?: continue
            if (competitors.length() < 2) continue

            var homeObj = competitors.optJSONObject(0) ?: continue
            var awayObj = competitors.optJSONObject(1) ?: continue
            if (homeObj.optString("homeAway") == "away") {
                val tmp = homeObj
                homeObj = awayObj
                awayObj = tmp
            }

            val hTeam = homeObj.optJSONObject("team") ?: continue
            val aTeam = awayObj.optJSONObject("team") ?: continue

            val hName = hTeam.optString("shortDisplayName", hTeam.optString("displayName", "Home"))
            val aName = aTeam.optString("shortDisplayName", aTeam.optString("displayName", "Away"))
            val hLogo = hTeam.optString("logo", "")
            val aLogo = aTeam.optString("logo", "")

            val uniqueKey = if (eventId.isNotBlank()) eventId else "$hName|$aName|$rawDate"
            if (!seenKeys.add(uniqueKey)) continue

            val hScore = homeObj.optString("score", "0")
            val aScore = awayObj.optString("score", "0")
            val centerScore = if (isLive || isFinished) "$hScore - $aScore" else "VS"

            outList.add(
                FixtureRow(
                    leagueOrRound = leagueTitle,
                    dateTimeText = timeStr,
                    epochMillis = millis,
                    statusText = badgeText,
                    isLive = isLive,
                    isFinished = isFinished,
                    homeName = hName,
                    homeLogo = hLogo,
                    awayName = aName,
                    awayLogo = aLogo,
                    centerScore = centerScore
                )
            )
        }
    }

    private suspend fun parseSportsDbEventsArray(
        arr: JSONArray?,
        leagueTitle: String,
        outTimeFmt: SimpleDateFormat,
        seenKeys: MutableSet<String>,
        outList: MutableList<FixtureRow>
    ) {
        if (arr == null) return
        val utcFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateOnlyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        for (i in 0 until arr.length()) {
            val ev = arr.optJSONObject(i) ?: continue
            val idEvent = ev.optString("idEvent", "")
            val hName = ev.optString("strHomeTeam", "Home")
            val aName = ev.optString("strAwayTeam", "Away")
            val key = if (idEvent.isNotBlank()) "sdb_$idEvent" else "sdb_${hName}_${aName}_${ev.optString("dateEvent")}"
            if (!seenKeys.add(key)) continue

            val tsStr = ev.optString("strTimestamp", "").replace("T", " ").take(19)
            val dateStr = ev.optString("dateEvent", "")
            val millis = runCatching { utcFmt.parse(tsStr) }.getOrNull()?.time
                ?: runCatching { dateOnlyFmt.parse(dateStr) }.getOrNull()?.time
                ?: 0L
            val timeLabel = if (millis > 0L) outTimeFmt.format(Date(millis)) else dateStr

            val hScoreRaw = ev.optString("intHomeScore", "")
            val aScoreRaw = ev.optString("intAwayScore", "")
            val hasScore = hScoreRaw.isNotBlank() && aScoreRaw.isNotBlank() && hScoreRaw != "null" && aScoreRaw != "null"
            val isFinished = hasScore || ev.optString("strStatus", "").contains("Match Finished", true)
            val center = if (hasScore) "$hScoreRaw - $aScoreRaw" else "VS"
            val badge = if (isFinished) "KẾT THÚC" else "SẮP ĐÁ"

            outList.add(
                FixtureRow(
                    leagueOrRound = leagueTitle,
                    dateTimeText = timeLabel,
                    epochMillis = millis,
                    statusText = badge,
                    isLive = false,
                    isFinished = isFinished,
                    homeName = hName,
                    homeLogo = ev.optString("strHomeTeamBadge", ""),
                    awayName = aName,
                    awayLogo = ev.optString("strAwayTeamBadge", ""),
                    centerScore = center
                )
            )
        }
    }

    private suspend fun fetchFixturesMultiSource(league: LeagueOption): List<FixtureRow> =
        withContext(Dispatchers.IO) {
            val vnTz = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            val dateParamFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = vnTz }
            val isoFmtShort = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val isoFmtFull = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outTimeFmt = SimpleDateFormat("HH:mm • dd/MM", Locale.US).apply { timeZone = vnTz }

            val seenKeys = mutableSetOf<String>()
            val list = mutableListOf<FixtureRow>()

            // 1. Lấy từ ESPN (vòng mặc định + khoảng 3 tuần: -7 ngày đến +14 ngày)
            for (slug in league.espnSlugs) {
                val defaultUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/scoreboard?limit=150"
                fetchJson(defaultUrl)?.let {
                    parseEspnEventsFromRoot(it, league.title, isoFmtShort, isoFmtFull, outTimeFmt, seenKeys, list)
                }

                for (offset in -7..14) {
                    val cal = Calendar.getInstance(vnTz).apply { add(Calendar.DAY_OF_YEAR, offset) }
                    val dayParam = dateParamFmt.format(cal.time)
                    val dayUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/scoreboard?dates=$dayParam&limit=150"
                    fetchJson(dayUrl)?.let {
                        parseEspnEventsFromRoot(it, league.title, isoFmtShort, isoFmtFull, outTimeFmt, seenKeys, list)
                    }
                }
                if (list.isNotEmpty()) break
            }

            // 2. Bổ sung thêm các trận sắp đá & kết quả gần nhất từ TheSportsDB nếu có ID giải
            if (league.sportsDbLeagueId != null) {
                val nextRoot = fetchJson("https://www.thesportsdb.com/api/v1/json/3/eventsnextleague.php?id=${league.sportsDbLeagueId}")
                parseSportsDbEventsArray(nextRoot?.optJSONArray("events"), league.title, outTimeFmt, seenKeys, list)

                val pastRoot = fetchJson("https://www.thesportsdb.com/api/v1/json/3/eventspastleague.php?id=${league.sportsDbLeagueId}")
                parseSportsDbEventsArray(pastRoot?.optJSONArray("events"), league.title, outTimeFmt, seenKeys, list)
            }

            list.sortedBy { it.epochMillis }
        }

    inner class LeagueTabAdapter : RecyclerView.Adapter<LeagueTabAdapter.TabVH>() {
        inner class TabVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabVH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_league_tab, parent, false) as TextView
            return TabVH(tv)
        }

        override fun onBindViewHolder(holder: TabVH, position: Int) {
            val item = leagues[position]
            holder.tv.text = item.title
            val isSelected = position == selectedLeagueIndex
            holder.tv.setBackgroundResource(
                if (isSelected) R.drawable.bg_pill_primary else R.drawable.bg_pill_action
            )
            holder.tv.setOnClickListener {
                if (selectedLeagueIndex != position) {
                    val old = selectedLeagueIndex
                    selectedLeagueIndex = position
                    notifyItemChanged(old)
                    notifyItemChanged(position)
                    loadSelectedLeagueData()
                }
            }
        }

        override fun getItemCount(): Int = leagues.size
    }

    inner class StandingAdapter(private val rows: List<StandingRow>) :
        RecyclerView.Adapter<StandingAdapter.RowVH>() {

        private fun findOptionalTextView(v: View, idName: String): TextView? {
            val resId = v.resources.getIdentifier(idName, "id", v.context.packageName)
            return if (resId != 0) v.findViewById(resId) else null
        }

        inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
            val tvRank: TextView = v.findViewById(R.id.tvRank)
            val ivLogo: ImageView = v.findViewById(R.id.ivTeamLogo)
            val tvName: TextView = v.findViewById(R.id.tvTeamName)
            val tvPlayed: TextView = v.findViewById(R.id.tvPlayed)
            val tvWon: TextView = v.findViewById(R.id.tvWon)
            val tvDrawn: TextView = v.findViewById(R.id.tvDrawn)
            val tvLost: TextView = v.findViewById(R.id.tvLost)
            val tvGf: TextView? = findOptionalTextView(v, "tvGoalsFor")
            val tvGa: TextView? = findOptionalTextView(v, "tvGoalsAgainst")
            val tvGd: TextView = v.findViewById(R.id.tvGoalDiff)
            val tvPts: TextView = v.findViewById(R.id.tvPoints)
            var job: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_standing_row, parent, false)
            return RowVH(v)
        }

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            val r = rows[position]
            holder.job?.cancel()

            holder.tvRank.text = r.rank.toString()
            holder.tvRank.setTextColor(
                when {
                    r.rank <= 4 -> Color.parseColor("#38BDF8")
                    r.rank in 5..6 -> Color.parseColor("#FBBF24")
                    r.rank >= 18 -> Color.parseColor("#F87171")
                    else -> Color.WHITE
                }
            )

            holder.tvName.text = r.teamName
            holder.tvPlayed.text = r.played
            holder.tvWon.text = r.won
            holder.tvDrawn.text = r.drawn
            holder.tvLost.text = r.lost
            holder.tvGf?.text = r.goalsFor
            holder.tvGa?.text = r.goalsAgainst
            holder.tvGd.text = r.gd
            holder.tvPts.text = r.points

            holder.ivLogo.setImageDrawable(null)
            holder.job = lifecycleScope.launch {
                loadLogo(r.teamLogo)?.let { holder.ivLogo.setImageBitmap(it) }
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    inner class FixtureAdapter(private val rows: List<FixtureRow>) :
        RecyclerView.Adapter<FixtureAdapter.FixVH>() {

        inner class FixVH(v: View) : RecyclerView.ViewHolder(v) {
            val tvLeague: TextView = v.findViewById(R.id.tvMatchLeague)
            val tvKickoff: TextView = v.findViewById(R.id.tvMatchKickoff)
            val tvStatus: TextView = v.findViewById(R.id.tvMatchStatus)
            val ivHome: ImageView = v.findViewById(R.id.ivHomeLogo)
            val tvHome: TextView = v.findViewById(R.id.tvHomeName)
            val tvScore: TextView = v.findViewById(R.id.tvScore)
            val ivAway: ImageView = v.findViewById(R.id.ivAwayLogo)
            val tvAway: TextView = v.findViewById(R.id.tvAwayName)
            val tvBottom: TextView = v.findViewById(R.id.tvStreamCount)
            var job: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FixVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_match, parent, false)
            return FixVH(v)
        }

        override fun onBindViewHolder(holder: FixVH, position: Int) {
            val f = rows[position]
            holder.job?.cancel()

            holder.tvLeague.text = f.leagueOrRound
            holder.tvKickoff.text = f.dateTimeText
            holder.tvStatus.text = f.statusText
            holder.tvStatus.setBackgroundColor(
                when {
                    f.isLive -> Color.parseColor("#DC2626")
                    f.isFinished -> Color.parseColor("#334155")
                    else -> Color.parseColor("#0284C7")
                }
            )

            holder.tvHome.text = f.homeName
            holder.tvAway.text = f.awayName
            holder.tvScore.text = f.centerScore
            holder.tvBottom.text = if (f.isFinished) "Đã kết thúc" else "Giờ Việt Nam (GMT+7)"

            holder.ivHome.setImageDrawable(null)
            holder.ivAway.setImageDrawable(null)
            holder.job = lifecycleScope.launch {
                loadLogo(f.homeLogo)?.let { holder.ivHome.setImageBitmap(it) }
                loadLogo(f.awayLogo)?.let { holder.ivAway.setImageBitmap(it) }
            }

            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.03f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
            }
        }

        override fun getItemCount(): Int = rows.size
    }
}
