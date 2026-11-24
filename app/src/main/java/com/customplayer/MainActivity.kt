package com.customplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = FeedRepository()

    private lateinit var lobbyView: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var infoText: TextView
    private lateinit var gridView: RecyclerView
    private lateinit var adapter: MatchGridAdapter

    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView

    private var loadJob: Job? = null
    private var currentStream: StreamOption? = null

    private val prefs by lazy {
        getSharedPreferences("custom_link_player_prefs", Context.MODE_PRIVATE)
    }

    private val tvUserAgent =
        "Mozilla/5.0 (Linux; Android 13; Smart TV) AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B111A"))
        }

        lobbyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val savedUrl = prefs.getString("feed_url", "asset://sample_feed.json") ?: "asset://sample_feed.json"
        urlInput = EditText(this).apply {
            setText(savedUrl)
            hint = "Nhập link JSON nguồn (ví dụ https://.../feed.json)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(16, 12, 16, 12)
            singleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val loadBtn = Button(this).apply {
            text = "Lưu & Tải"
            setOnClickListener {
                val entered = urlInput.text.toString().trim()
                val effective = if (entered.isBlank()) "asset://sample_feed.json" else entered
                prefs.edit().putString("feed_url", effective).apply()
                loadFeed(effective)
            }
        }

        inputRow.addView(urlInput)
        inputRow.addView(loadBtn)

        infoText = TextView(this).apply {
            text = "Đang nạp nguồn..."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
        }

        adapter = MatchGridAdapter(scope, repository) { match ->
            openStreamChooser(match)
        }

        gridView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        lobbyView.addView(inputRow)
        lobbyView.addView(infoText)
        lobbyView.addView(gridView)

        playerView = PlayerView(this).apply {
            useController = true
            visibility = View.GONE
        }

        webView = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = tvUserAgent
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }

        root.addView(lobbyView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(playerView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)

        setupPlayer()
        loadFeed(savedUrl)
    }

    private fun setupPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val fallback = currentStream
                    if (fallback != null) {
                        Toast.makeText(
                            this@MainActivity,
                            "Lỗi luồng (${error.errorCodeName}), mở bằng WebView...",
                            Toast.LENGTH_SHORT
                        ).show()
                        startWeb(fallback)
                    }
                }
            })
        }
        playerView.player = exoPlayer
    }

    private fun loadFeed(sourceUrl: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            infoText.text = "Đang tải: $sourceUrl"
            repository.loadFromUrlOrAsset(this@MainActivity, sourceUrl)
                .onSuccess { feed ->
                    adapter.submit(feed.matches)
                    infoText.text = "${feed.title} • ${feed.matches.size} trận đấu"
                    if (feed.matches.isNotEmpty()) gridView.requestFocus()
                }
                .onFailure { err ->
                    infoText.text = "Lỗi tải nguồn: ${err.message}"
                }
        }
    }

    private fun openStreamChooser(match: MatchCard) {
        if (match.streams.isEmpty()) {
            Toast.makeText(this, "Trận này chưa có luồng phát", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = match.streams.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${match.homeName} vs ${match.awayName}")
            .setItems(labels) { dialog, idx ->
                dialog.dismiss()
                val chosen = match.streams.getOrNull(idx) ?: return@setItems
                startPlayback(chosen)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun startPlayback(stream: StreamOption) {
        currentStream = stream
        lobbyView.visibility = View.GONE
        val lower = stream.url.lowercase()
        if (lower.contains(".m3u8") || lower.contains(".mpd")) {
            startExo(stream)
        } else {
            startWeb(stream)
        }
    }

    private fun startExo(stream: StreamOption) {
        webView.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        val headers = mutableMapOf<String, String>()
        stream.referer?.let { headers["Referer"] = it }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(tvUserAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val source = DefaultMediaSourceFactory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(stream.url)))

        exoPlayer?.apply {
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    private fun startWeb(stream: StreamOption) {
        exoPlayer?.stop()
        playerView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        val headers = stream.referer?.let { mapOf("Referer" to it) } ?: emptyMap()
        webView.loadUrl(stream.url, headers)
    }

    private fun backToLobby() {
        exoPlayer?.stop()
        playerView.visibility = View.GONE
        webView.visibility = View.GONE
        lobbyView.visibility = View.VISIBLE
        gridView.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (playerView.visibility == View.VISIBLE || webView.visibility == View.VISIBLE) {
                backToLobby()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        scope.cancel()
        playerView.player = null
        exoPlayer?.release()
        exoPlayer = null
        webView.destroy()
        super.onDestroy()
    }
}
