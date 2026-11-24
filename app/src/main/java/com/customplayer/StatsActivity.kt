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
    val goalsFor: String,
    val goalsAgainst: String,
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
                    tvStatus.text = "Chưa có dữ liệu bảng xếp hạng cho ${league.title}"
                } else {
                    tvStatus.text = "${league.title} • ${rows.size} câu lạc bộ"
                }
                rvContent.adapter = StandingAdapter(rows)
            } else {
                val fixtures = fetchFixtures(league)
                if (fixtures.isEmpty()) {
                    tvStatus.text = "Chưa có dữ liệu lịch thi đấu cho ${league.title}"
                } else {
                    tvStatus.text = "${league.title} • ${fixtures.size} trận (3 tuần)"
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
            val gfVal = statsMap["pointsFor"] ?: statsMap["GF"] ?: statsMap["F"] ?: "0"
            val gaVal = statsMap["pointsAgainst"] ?: statsMap["GA"] ?: statsMap["A"] ?: "0"

            val gdRaw = statsMap["pointDifferential"] ?: statsMap["GD"] ?: run {
                val gfNum = gfVal.toIntOrNull()
                val gaNum = gaVal.toIntOrNull()
                if (gfNum != null && gaNum != null) (gfNum - gaNum).toString() else "0"
            }
            val gdFormatted = if (!gdRaw.startsWith("-") && !gdRaw.startsWith("+") && gdRaw != "0") {
                "+$gdRaw"
            } else gdRaw

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
                    gd = gdFormatted,
                    points = statsMap["points"] ?: statsMap["P"] ?: "0"
                )
            )
        }
    }

    private suspend fun fetchStandings(league: LeagueOption): List<StandingRow> {
        for (slug in league.slugs) {
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
                        parseEntriesArray(entries, groupName, result)
                    }
                }
            }

            if (result.isEmpty()) {
                val directEntries = root.optJSONObject("standings")?.optJSONArray("entries")
                if (directEntries != null) {
                    parseEntriesArray(directEntries, league.title, result)
                }
            }

            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun parseEventsFromRoot(
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
            val parsedDate = runCatching { isoFmtShort.parse(rawDate) }.getOrNull()
                ?: runCatching { isoFmtFull.parse(rawDate) }.getOrNull()
            val timeStr = if (parsedDate != null) outTimeFmt.format(parsedDate) else rawDate

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

    private suspend fun fetchFixtures(league: LeagueOption): List<FixtureRow> {
        val vnTz = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val dateParamFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = vnTz }
        val calStart = Calendar.getInstance(vnTz).apply { add(Calendar.DAY_OF_YEAR, -5) }
        val calEnd = Calendar.getInstance(vnTz).apply { add(Calendar.DAY_OF_YEAR, 16) }
        val datesRange = "${dateParamFmt.format(calStart.time)}-${dateParamFmt.format(calEnd.time)}"

        val isoFmtShort = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val isoFmtFull = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val outTimeFmt = SimpleDateFormat("HH:mm • dd/MM", Locale.US).apply { timeZone = vnTz }

        for (slug in league.slugs) {
            val seenKeys = mutableSetOf<String>()
            val list = mutableListOf<FixtureRow>()

            val rangeUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/scoreboard?dates=$datesRange&limit=150"
            fetchJson(rangeUrl)?.let {
                parseEventsFromRoot(it, league.title, isoFmtShort, isoFmtFull, outTimeFmt, seenKeys, list)
            }

            if (list.isEmpty()) {
                val defaultUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/scoreboard?limit=150"
                fetchJson(defaultUrl)?.let {
                    parseEventsFromRoot(it, league.title, isoFmtShort, isoFmtFull, outTimeFmt, seenKeys, list)
                }
            }

            if (list.isNotEmpty()) return list
        }
        return emptyList()
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

        inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
            val tvRank: TextView = v.findViewById(R.id.tvRank)
            val ivLogo: ImageView = v.findViewById(R.id.ivTeamLogo)
            val tvName: TextView = v.findViewById(R.id.tvTeamName)
            val tvPlayed: TextView = v.findViewById(R.id.tvPlayed)
            val tvWon: TextView = v.findViewById(R.id.tvWon)
            val tvDrawn: TextView = v.findViewById(R.id.tvDrawn)
            val tvLost: TextView = v.findViewById(R.id.tvLost)
            val tvGf: TextView? = v.findViewById(
                v.resources.getIdentifier("tvGoalsFor", "id", v.context.packageName)
            )
            val tvGa: TextView? = v.findViewById(
                v.resources.getIdentifier("tvGoalsAgainst", "id", v.context.packageName)
            )
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
