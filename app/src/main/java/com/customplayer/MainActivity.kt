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

    private var activeSourceUrl: String = ""
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

        val firstSource = getSavedSources().first()
        showSourceManagerScreen()
        prefetchDefaultSource(firstSource.second, firstSource.first)
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

    private fun saveSourceMeta(url: String, updatedAt: String, count: Int) {
        val raw = prefs.getString("source_meta_json", "{}") ?: "{}"
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        root.put(url, JSONObject().apply {
            put("updated", updatedAt)
            put("count", count)
        })
        prefs.edit().putString("source_meta_json", root.toString()).apply()
    }

    private fun getSourceMeta(url: String): Pair<String, Int>? {
        val raw = prefs.getString("source_meta_json", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw).optJSONObject(url) ?: return null
            val updated = obj.optString("updated", "")
            val count = obj.optInt("count", 0)
            updated to count
        }.getOrNull()
    }

    private fun prefetchDefaultSource(url: String, name: String) {
        tvStatus.text = "Đang đồng bộ dữ liệu từ $name..."
        lifecycleScope.launch {
            val res = repo.loadFromUrlOrAsset(this@MainActivity, url)
            res.onSuccess { feed ->
                activeSourceUrl = url
                allMatches = feed.matches
                lastUpdatedText = feed.updatedAt.ifBlank { feed.title }
                saveSourceMeta(url, lastUpdatedText, allMatches.size)
                if (!isShowingMatches) {
                    showSourceManagerScreen()
                }
            }.onFailure {
                tvStatus.text = "Chọn một nguồn bên dưới hoặc bấm + Thêm link"
            }
        }
    }

    private fun openSourceMatches(url: String, sourceTitle: String, forceReload: Boolean = false) {
        if (!forceReload && url == activeSourceUrl && allMatches.isNotEmpty()) {
            showMatchListScreen("$sourceTitle (${allMatches.size} trận)", allMatches)
            return
        }

        tvStatus.text = "Đang tải danh sách trận từ $sourceTitle..."
        lifecycleScope.launch {
            val res = repo.loadFromUrlOrAsset(this@MainActivity, url)
            res.onSuccess { feed ->
                activeSourceUrl = url
                allMatches = feed.matches
                lastUpdatedText = feed.updatedAt.ifBlank { feed.title }
                saveSourceMeta(url, lastUpdatedText, allMatches.size)
                showMatchListScreen("$sourceTitle (${allMatches.size} trận)", allMatches)
            }.onFailure { err ->
                tvStatus.text = "Lỗi tải nguồn: ${err.message ?: "Kiểm tra lại đường dẫn"}"
                Toast.makeText(this@MainActivity, "Không tải được dữ liệu từ nguồn này", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSourceManagerScreen() {
        isShowingMatches = false
        tvHeaderTitle.text = "Quản lý nguồn"
        btnBackSources.visibility = View.GONE
        btnAddSource.visibility = View.VISIBLE

        val saved = getSavedSources()
        tvStatus.text = "Hiện có ${saved.size} nguồn phát • Bấm OK để xem, giữ OK để tùy chỉnh"

        val cards = mutableListOf<SourceCardItem>()
        val icons = listOf("⚽", "▶", "📺", "🏆", "🔥")

        saved.forEachIndexed { idx, (name, url) ->
            val style = when (idx) {
                0 -> 0 // Xanh ngọc (Mint) giống thẻ đầu tiên
                1 -> 1 // Cam nổi bật giống thẻ thứ 2
                else -> 3 // Nền tối sang trọng cho các nguồn tiếp theo
            }
            val meta = getSourceMeta(url)
            val subtitle = if (meta != null && meta.second > 0) {
                "$name - Trực tiếp bóng đá HD (${meta.first} • ${meta.second} trận)"
            } else {
                "$name - Nguồn phát trực tiếp bóng đá & sự kiện thể thao"
            }

            cards.add(
                SourceCardItem(
                    id = "src_$idx",
                    title = name,
                    subtitle = subtitle,
                    iconText = icons[idx % icons.size],
                    styleType = style,
                    feedUrl = url
                )
            )
        }

        // Thẻ "+ Thêm nguồn mới" nằm ngay sau danh sách các nguồn đã thêm
        cards.add(
            SourceCardItem(
                id = "add_new",
                title = "Thêm nguồn mới",
                subtitle = "Dán đường dẫn raw JSON mới để thêm kênh và giải đấu vào danh sách",
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

    private fun showMatchListScreen(title: String, matches: List<MatchCard>) {
        isShowingMatches = true
        tvHeaderTitle.text = title
        btnBackSources.visibility = View.VISIBLE
        btnAddSource.visibility = View.GONE

        tvStatus.text = "Cập nhật lúc $lastUpdatedText • ${matches.size} trận đấu"
        rvMainGrid.adapter = matchAdapter
        matchAdapter.submitMatches(matches)
        rvMainGrid.post {
            rvMainGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun handleSourceCardClick(item: SourceCardItem) {
        if (item.isAddAction) {
            showAddSourceDialog()
            return
        }
        val url = item.feedUrl ?: return
        openSourceMatches(url, item.title, forceReload = false)
    }

    private fun handleSourceCardLongClick(item: SourceCardItem) {
        val url = item.feedUrl ?: return
        val options = arrayOf("Tải lại dữ liệu mới nhất", "Đổi tên nguồn", "Xóa nguồn này")
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openSourceMatches(url, item.title, forceReload = true)
                    1 -> showRenameSourceDialog(url, item.title)
                    2 -> confirmDeleteSource(url, item.title)
                }
            }
            .show()
    }

    private fun showRenameSourceDialog(url: String, oldName: String) {
        val et = EditText(this).apply {
            setText(oldName)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Đổi tên nguồn")
            .setView(et)
            .setPositiveButton("Lưu") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val list = getSavedSources().map {
                        if (it.second == url) newName to url else it
                    }
                    saveSources(list)
                    showSourceManagerScreen()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun confirmDeleteSource(url: String, title: String) {
        val sources = getSavedSources()
        if (sources.size <= 1) {
            Toast.makeText(this, "Cần giữ lại ít nhất 1 nguồn phát", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Xóa nguồn \"$title\"?")
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
            hint = "Tên nguồn (ví dụ: Bóng Đá 2, Kênh Thể Thao...)"
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
            .setPositiveButton("Lưu & Mở") { _, _ ->
                val url = etUrl.text.toString().trim()
                if (!url.startsWith("http")) {
                    Toast.makeText(this, "Đường dẫn phải bắt đầu bằng http/https", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val name = etName.text.toString().trim().ifBlank { "Nguồn ${getSavedSources().size + 1}" }
                val sources = getSavedSources()
                sources.removeAll { it.second == url }
                sources.add(name to url)
                saveSources(sources)
                openSourceMatches(url, name, forceReload = true)
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
