package com.customplayer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
    private lateinit var btnReloadMatches: Button
    private lateinit var tvHeaderTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var rvMainGrid: RecyclerView

    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var matchAdapter: MatchAdapter

    private var activeSourceUrl: String = ""
    private var activeSourceTitle: String = "GaVang33 TV"
    private var allMatches: List<MatchCard> = emptyList()
    private var lastUpdatedText: String = ""
    private var isShowingMatches: Boolean = false
    private var lastAutoSyncTimeMs: Long = 0L

    private val defaultFeedUrl =
        "https://raw.githubusercontent.com/daoducphat352010-ux/bongda-tv/refs/heads/main/feed.json"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createFocusCardDrawable(
        radiusDp: Int = 16,
        normalStart: String = "#11192C",
        normalEnd: String = "#0B1120",
        focusStart: String = "#182947",
        focusEnd: String = "#0F172A",
        strokeNormal: String = "#1E293B",
        strokeFocus: String = "#38BDF8"
    ): Drawable {
        val focused = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(focusStart), Color.parseColor(focusEnd))
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(2), Color.parseColor(strokeFocus))
        }

        val normal = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(normalStart), Color.parseColor(normalEnd))
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(strokeNormal))
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun createCrestCircleDrawable(): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1A253C"))
            setStroke(dp(1), Color.parseColor("#2B3B5C"))
        }
    }

    private fun createBadgeDrawable(
        fillColor: String,
        borderColor: String? = null,
        radiusDp: Int = 8
    ): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillColor))
            if (borderColor != null) {
                setStroke(dp(1), Color.parseColor(borderColor))
            }
        }
    }

    private fun styleGridCardView(cardView: View) {
        val ivHome = cardView.findViewById<ImageView?>(R.id.ivHomeLogo)
        val ivAway = cardView.findViewById<ImageView?>(R.id.ivAwayLogo)
        val tvScore = cardView.findViewById<TextView?>(R.id.tvScore)
        val tvKickoff = cardView.findViewById<TextView?>(R.id.tvMatchKickoff)

        if (ivHome != null && ivAway != null) {
            // Thẻ trận đấu (item_match)
            cardView.background = createFocusCardDrawable()
            ivHome.background = createCrestCircleDrawable()
            ivHome.setPadding(dp(6), dp(6), dp(6), dp(6))
            ivAway.background = createCrestCircleDrawable()
            ivAway.setPadding(dp(6), dp(6), dp(6), dp(6))
            tvScore?.background = createBadgeDrawable("#1E293B", "#334155", 10)
            tvScore?.setPadding(dp(10), dp(4), dp(10), dp(4))
            tvKickoff?.setTextColor(Color.parseColor("#38BDF8"))
        }

        val existingFocusListener = cardView.onFocusChangeListener
        cardView.setOnFocusChangeListener { v, hasFocus ->
            existingFocusListener?.onFocusChange(v, hasFocus)
            val scale = if (hasFocus) 1.035f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnBackSources = findViewById(R.id.btnBackSources)
        btnAddSource = findViewById(R.id.btnAddSource)
        btnReloadMatches = findViewById(R.id.btnReloadMatches)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        tvStatus = findViewById(R.id.tvStatus)
        rvMainGrid = findViewById(R.id.rvMainGrid)

        (window.decorView.findViewById<View>(android.R.id.content) as? ViewGroup)
            ?.getChildAt(0)
            ?.setBackgroundColor(Color.parseColor("#070B14"))

        rvMainGrid.layoutManager = GridLayoutManager(this, 3)
        rvMainGrid.setHasFixedSize(false)
        rvMainGrid.itemAnimator = null
        rvMainGrid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                styleGridCardView(view)
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                view.scaleX = 1.0f
                view.scaleY = 1.0f
            }
        })

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
        btnReloadMatches.setOnClickListener {
            val targetUrl = activeSourceUrl.ifBlank { getSavedSources().first().second }
            val targetTitle = activeSourceTitle.ifBlank { getSavedSources().first().first }
            openSourceMatches(targetUrl, targetTitle, forceReload = true)
        }

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
        activeSourceUrl = firstSource.second
        activeSourceTitle = firstSource.first

        showSourceManagerScreen()
        autoSyncLatestFeed(force = true)
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - lastAutoSyncTimeMs > 60_000L) {
            autoSyncLatestFeed(force = false)
        }
    }

    private fun buildFreshUrl(rawUrl: String): String {
        val sep = if (rawUrl.contains("?")) "&" else "?"
        return "$rawUrl${sep}t=${System.currentTimeMillis()}"
    }

    private fun autoSyncLatestFeed(force: Boolean) {
        val targetUrl = activeSourceUrl.ifBlank { getSavedSources().first().second }
        val targetTitle = activeSourceTitle.ifBlank { getSavedSources().first().first }
        if (!force && targetUrl.isBlank()) return

        lastAutoSyncTimeMs = System.currentTimeMillis()
        tvStatus.text = "Đang tự động cập nhật dữ liệu mới nhất từ $targetTitle..."

        lifecycleScope.launch {
            val res = repo.loadFromUrlOrAsset(this@MainActivity, buildFreshUrl(targetUrl))
            res.onSuccess { feed ->
                activeSourceUrl = targetUrl
                activeSourceTitle = targetTitle
                allMatches = feed.matches
                lastUpdatedText = feed.updatedAt.ifBlank { feed.title }
                saveSourceMeta(targetUrl, lastUpdatedText, allMatches.size)

                if (isShowingMatches) {
                    showMatchListScreen("$targetTitle (${allMatches.size} trận)", allMatches)
                } else {
                    showSourceManagerScreen()
                }
            }.onFailure {
                if (!isShowingMatches) {
                    tvStatus.text = "Chọn một nguồn bên dưới hoặc bấm + Thêm nguồn"
                } else {
                    tvStatus.text = "Cập nhật lúc $lastUpdatedText • ${allMatches.size} trận đấu"
                }
            }
        }
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

    private fun openSourceMatches(url: String, sourceTitle: String, forceReload: Boolean = false) {
        if (!forceReload && url == activeSourceUrl && allMatches.isNotEmpty()) {
            showMatchListScreen("$sourceTitle (${allMatches.size} trận)", allMatches)
            return
        }

        btnReloadMatches.isEnabled = false
        tvStatus.text = "Đang tải dữ liệu mới nhất từ $sourceTitle..."

        lifecycleScope.launch {
            val res = repo.loadFromUrlOrAsset(this@MainActivity, buildFreshUrl(url))
            btnReloadMatches.isEnabled = true

            res.onSuccess { feed ->
                activeSourceUrl = url
                activeSourceTitle = sourceTitle
                allMatches = feed.matches
                lastUpdatedText = feed.updatedAt.ifBlank { feed.title }
                lastAutoSyncTimeMs = System.currentTimeMillis()
                saveSourceMeta(url, lastUpdatedText, allMatches.size)

                showMatchListScreen("$sourceTitle (${allMatches.size} trận)", allMatches)
                if (forceReload) {
                    Toast.makeText(
                        this@MainActivity,
                        "Đã cập nhật (${allMatches.size} trận • $lastUpdatedText)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { err ->
                tvStatus.text = "Lỗi tải nguồn: ${err.message ?: "Kiểm tra lại kết nối mạng"}"
                Toast.makeText(this@MainActivity, "Không tải được dữ liệu mới", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSourceManagerScreen() {
        isShowingMatches = false
        tvHeaderTitle.text = "Quản lý nguồn"
        btnBackSources.visibility = View.GONE
        btnReloadMatches.visibility = View.GONE
        btnAddSource.visibility = View.VISIBLE

        val saved = getSavedSources()
        tvStatus.text = "Hiện có ${saved.size} nguồn phát • Bấm OK để mở, giữ OK để tùy chỉnh"

        val cards = mutableListOf<SourceCardItem>()
        val icons = listOf("⚽", "▶", "📺", "🔥")

        saved.forEachIndexed { idx, (name, url) ->
            val style = when (idx) {
                0 -> 0
                1 -> 1
                else -> 3
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

        // 2 mục tích hợp sẵn: BXH & Lịch thi đấu 8 giải đấu
        cards.add(
            SourceCardItem(
                id = "builtin_standings",
                title = "Bảng Xếp Hạng",
                subtitle = "5 giải VĐQG châu Âu & 3 cúp châu Âu cập nhật trực tiếp",
                iconText = "🏆",
                styleType = 4,
                builtInMode = "STANDINGS"
            )
        )

        cards.add(
            SourceCardItem(
                id = "builtin_fixtures",
                title = "Lịch Thi Đấu",
                subtitle = "Lịch & kết quả 5 giải châu Âu & 3 cúp châu Âu (giờ Việt Nam)",
                iconText = "📅",
                styleType = 5,
                builtInMode = "FIXTURES"
            )
        )

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
        btnReloadMatches.visibility = View.VISIBLE

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
        if (!item.builtInMode.isNullOrBlank()) {
            val intent = Intent(this, StatsActivity::class.java).apply {
                putExtra("mode", item.builtInMode)
            }
            startActivity(intent)
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
                    if (activeSourceUrl == url) activeSourceTitle = newName
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
            hint = "Tên nguồn (ví dụ: GioVang, Kênh Thể Thao...)"
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

        val dialog = AlertDialog.Builder(this).create()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createBadgeDrawable("#0F172A", "#38BDF8", 18)
            setPadding(dp(24), dp(20), dp(24), dp(18))
        }

        val topMetaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(this).apply {
            text = match.league.uppercase()
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val timeChip = TextView(this).apply {
            text = "${match.kickoff} • ${match.status}"
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            background = createBadgeDrawable(
                if (match.status.equals("LIVE", true)) "#EF4444" else "#0284C7",
                radiusDp = 6
            )
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }

        topMetaRow.addView(badge)
        topMetaRow.addView(timeChip)

        val titleView = TextView(this).apply {
            text = "${match.homeName}  vs  ${match.awayName}"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }

        val subView = TextView(this).apply {
            text = "Chọn phòng bình luận viên (${match.streams.size} phòng khả dụng)"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 0, 0, dp(14))
        }

        container.addView(topMetaRow)
        container.addView(titleView)
        container.addView(subView)

        match.streams.forEachIndexed { idx, stream ->
            val btn = Button(this).apply {
                text = "🎙   ${stream.label}     •     HD Trực tiếp ›"
                isAllCaps = false
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = createFocusCardDrawable(
                    radiusDp = 12,
                    normalStart = "#162035",
                    normalEnd = "#11192C",
                    focusStart = "#0284C7",
                    focusEnd = "#1D4ED8",
                    strokeNormal = "#293754",
                    strokeFocus = "#FFFFFF"
                )
                stateListAnimator = null
                isFocusable = true

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
                layoutParams = lp

                setOnClickListener {
                    dialog.dismiss()
                    val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        putExtra("title", "${match.homeName} vs ${match.awayName} • ${stream.label}")
                        putExtra("url", stream.url)
                        putExtra("referer", stream.referer ?: "")
                    }
                    startActivity(intent)
                }
            }
            container.addView(btn)
            if (idx == 0) btn.post { btn.requestFocus() }
        }

        dialog.setView(container)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
}
