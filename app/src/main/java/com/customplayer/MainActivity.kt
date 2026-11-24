package com.customplayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("tv_football_prefs", MODE_PRIVATE) }
    private val repo = FeedRepository()

    private lateinit var btnBackSources: Button
    private lateinit var btnAddSource: Button
    private lateinit var tvHeaderTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var rvMainGrid: RecyclerView

    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var matchAdapter: MatchAdapter

    private var allMatches: List<MatchCard> = emptyList()
    private var lastUpdatedText: String = ""
    private var isShowingMatches: Boolean = false

    private val defaultFeedUrl =
        "https://raw.githubusercontent.com/daoducphat352010-ux/bongda-tv/refs/heads/main/feed.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnBackSources = findViewById(R.id.btnBackSources)
        btnAddSource = findViewById(R.id.btnAddSource)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        tvStatus = findViewById(R.id.tvStatus)
        rvMainGrid = findViewById(R.id.rvMainGrid)

        rvMainGrid.layoutManager = GridLayoutManager(this, 3)
        rvMainGrid.setHasFixedSize(false)
        rvMainGrid.itemAnimator = null

        sourceAdapter = SourceAdapter(
            mutableListOf(),
            onClick = { item -> handleSourceCardClick(item) },
            onLongClick = { item -> handleSourceCardLongClick(item) }
        )

        matchAdapter = MatchAdapter(repo) { match ->
            showStreamDialog(match)
        }

        btnBackSources.setOnClickListener { showSourceManagerScreen() }
        btnAddSource.setOnClickListener { showAddSourceDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isShowingMatches) {
                    showSourceManagerScreen()
                } else {
                    finish()
                }
            }
        })

        val activeUrl = getSavedSources().firstOrNull()?.second ?: defaultFeedUrl
        loadFeedFromUrl(activeUrl, "GaVang33 TV")
    }

    private fun getSavedSources(): MutableList<Pair<String, String>> {
        val raw = prefs.getString("saved_sources_json", null)
        val list = mutableListOf<Pair<String, String>>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name", "Nguồn ${i + 1}")
                    val url = o.optString("url", "")
                    if (url.startsWith("http")) list.add(name to url)
                }
            }
        }
        if (list.isEmpty()) {
            list.add("GaVang33 TV" to defaultFeedUrl)
            saveSources(list)
        }
        return list
    }

    private fun saveSources(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((name, url) in list) {
            arr.put(JSONObject().apply {
                put("name", name)
                put("url", url)
            })
        }
        prefs.edit().putString("saved_sources_json", arr.toString()).apply()
    }

    private fun loadFeedFromUrl(url: String, sourceTitle: String) {
        tvStatus.text = "Đang tải dữ liệu từ $sourceTitle..."
        lifecycleScope.launch {
            val res = repo.loadFromUrlOrAsset(this@MainActivity, url)
            res.onSuccess { feed ->
                allMatches = feed.matches
                lastUpdatedText = feed.updatedAt.ifBlank { feed.title }
                tvStatus.text = "Cập nhật lúc $lastUpdatedText • ${allMatches.size} trận đấu"
                showSourceManagerScreen()
            }.onFailure { err ->
                tvStatus.text = "Lỗi tải nguồn: ${err.message ?: "Kiểm tra kết nối mạng"}"
                showSourceManagerScreen()
            }
        }
    }

    private fun showSourceManagerScreen() {
        isShowingMatches = false
        tvHeaderTitle.text = "Quản lý nguồn"
        btnBackSources.visibility = View.GONE
        btnAddSource.visibility = View.VISIBLE

        val cards = mutableListOf<SourceCardItem>()

        // 1. Các link nguồn đã lưu (thẻ màu xanh ngọc & cam nổi bật giống ảnh mẫu)
        val saved = getSavedSources()
        saved.forEachIndexed { idx, (name, url) ->
            val style = when (idx) {
                0 -> 0 // Mint
                1 -> 1 // Orange
                else -> 3 // Dark
            }
            val desc = if (idx == 0 && allMatches.isNotEmpty()) {
                "$name - Trực tiếp bóng đá HD ($lastUpdatedText • ${allMatches.size} trận)"
            } else {
                "Bấm để tải danh sách trận đấu từ nguồn $name"
            }
            cards.add(
                SourceCardItem(
                    id = "src_$idx",
                    title = name,
                    subtitle = desc,
                    iconText = "⚽",
                    styleType = style,
                    feedUrl = url
                )
            )
        }

        // 2. Thẻ Đang Trực Tiếp (LIVE)
        val liveCount = allMatches.count { it.status.equals("LIVE", ignoreCase = true) }
        cards.add(
            SourceCardItem(
                id = "cat_live",
                title = "Đang Trực Tiếp",
                subtitle = "Hiện có $liveCount trận đang diễn ra trực tiếp với bình luận viên",
                iconText = "▶",
                styleType = 2, // Red
                onlyLive = true
            )
        )

        // 3. Tự động tạo thẻ theo từng Giải Đấu có trong dữ liệu
        val leagueGroups = allMatches
            .groupBy { it.league.ifBlank { "Bóng Đá Trực Tiếp" } }
            .entries
            .sortedByDescending { it.value.size }

        for ((leagueName, list) in leagueGroups) {
            cards.add(
                SourceCardItem(
                    id = "lg_$leagueName",
                    title = leagueName,
                    subtitle = "Danh sách ${list.size} trận đấu thuộc giải $leagueName",
                    iconText = "🏆",
                    styleType = 3, // Dark
                    filterLeague = leagueName
                )
            )
        }

        // 4. Thẻ "+ Thêm link nguồn" ở cuối lưới
        cards.add(
            SourceCardItem(
                id = "add_new",
                title = "Thêm nguồn mới",
                subtitle = "Dán đường dẫn raw feed.json mới để xem thêm kênh và trận đấu",
                iconText = "+",
                styleType = 3,
                isAddAction = true
            )
        )

        rvMainGrid.adapter = sourceAdapter
        sourceAdapter.submitList(cards)
        rvMainGrid.post {
            rvMainGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun showMatchListScreen(title: String, filtered: List<MatchCard>) {
        isShowingMatches = true
        tvHeaderTitle.text = title
        btnBackSources.visibility = View.VISIBLE
        btnAddSource.visibility = View.GONE

        tvStatus.text = "Hiển thị ${filtered.size} trận đấu • Cập nhật lúc $lastUpdatedText"
        rvMainGrid.adapter = matchAdapter
        matchAdapter.submitMatches(filtered)
        rvMainGrid.post {
            rvMainGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun handleSourceCardClick(item: SourceCardItem) {
        if (item.isAddAction) {
            showAddSourceDialog()
            return
        }

        if (item.onlyLive) {
            val liveList = allMatches.filter { it.status.equals("LIVE", ignoreCase = true) }
            if (liveList.isEmpty()) {
                Toast.makeText(this, "Hiện chưa có trận nào đang diễn ra", Toast.LENGTH_SHORT).show()
                return
            }
            showMatchListScreen("Đang Trực Tiếp (${liveList.size} trận)", liveList)
            return
        }

        if (!item.filterLeague.isNullOrBlank()) {
            val byLeague = allMatches.filter { it.league == item.filterLeague }
            showMatchListScreen(item.filterLeague, byLeague)
            return
        }

        // Bấm vào thẻ nguồn chính (ví dụ GaVang33 TV) -> mở toàn bộ trận đấu của nguồn đó
        if (!item.feedUrl.isNullOrBlank()) {
            val firstSavedUrl = getSavedSources().firstOrNull()?.second
            if (item.feedUrl == firstSavedUrl && allMatches.isNotEmpty()) {
                showMatchListScreen("${item.title} (${allMatches.size} trận)", allMatches)
            } else {
                tvStatus.text = "Đang tải nguồn ${item.title}..."
                lifecycleScope.launch {
                    val res = repo.loadFromUrlOrAsset(this@MainActivity, item.feedUrl)
                    res.onSuccess { feed ->
                        allMatches = feed.matches
                        lastUpdatedText = feed.updatedAt.ifBlank { feed.title }
                        showMatchListScreen("${item.title} (${allMatches.size} trận)", allMatches)
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Không tải được link nguồn này", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun handleSourceCardLongClick(item: SourceCardItem) {
        val url = item.feedUrl ?: return
        val sources = getSavedSources()
        if (sources.size <= 1) {
            Toast.makeText(this, "Không thể xóa nguồn mặc định duy nhất", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Xóa nguồn \"${item.title}\"?")
            .setPositiveButton("Xóa") { _, _ ->
                sources.removeAll { it.second == url }
                saveSources(sources)
                showSourceManagerScreen()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showAddSourceDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 12)
        }

        val etName = EditText(this).apply {
            hint = "Tên nguồn (ví dụ: Bóng Đá 2)"
            setSingleLine(true)
        }
        val etUrl = EditText(this).apply {
            hint = "Dán đường dẫn raw feed.json..."
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }

        layout.addView(etName)
        layout.addView(etUrl)

        AlertDialog.Builder(this)
            .setTitle("Thêm link nguồn mới")
            .setView(layout)
            .setPositiveButton("Lưu & Tải") { _, _ ->
                val url = etUrl.text.toString().trim()
                if (!url.startsWith("http")) {
                    Toast.makeText(this, "Đường dẫn phải bắt đầu bằng http/https", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val name = etName.text.toString().trim().ifBlank { "Nguồn ${getSavedSources().size + 1}" }
                val sources = getSavedSources()
                sources.add(name to url)
                saveSources(sources)
                loadFeedFromUrl(url, name)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showStreamDialog(match: MatchCard) {
        if (match.streams.isEmpty()) {
            Toast.makeText(this, "Trận đấu chưa có nguồn phát", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = match.streams.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${match.homeName} vs ${match.awayName}")
            .setItems(labels) { _, which ->
                val selected = match.streams[which]
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("title", "${match.homeName} vs ${match.awayName} - ${selected.label}")
                    putExtra("url", selected.url)
                    putExtra("referer", selected.referer ?: "")
                }
                startActivity(intent)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}
