package com.customplayer

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class PlayerActivity : AppCompatActivity() {

    private var snifferWebView: WebView? = null
    private var videoView: VideoView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var streamStarted = false

    private val tvUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = intent.getStringExtra("title") ?: "Trực Tiếp Bóng Đá"
        val rawUrl = intent.getStringExtra("url").orEmpty().trim()
        val referer = intent.getStringExtra("referer").orEmpty().trim().ifBlank { "https://gavang33.live/" }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val vv = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        videoView = vv

        val controller = MediaController(this)
        controller.setAnchorView(vv)
        vv.setMediaController(controller)

        // Khung hiển thị trạng thái kết nối hoặc thông báo chưa tới giờ bóng lăn
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 28, 40, 28)
            setBackgroundColor(Color.parseColor("#CC0F172A"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val progress = ProgressBar(this)
        val statusText = TextView(this).apply {
            text = "Đang kết nối luồng phát trực tiếp..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        statusBox.addView(progress)
        statusBox.addView(statusText)

        val titleBanner = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            textSize = 14f
            setPadding(28, 14, 28, 14)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        root.addView(vv)
        root.addView(statusBox)
        root.addView(titleBanner)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        if (rawUrl.isBlank()) {
            progress.visibility = View.GONE
            statusText.text = "Trận đấu chưa có nguồn phát"
            return
        }

        val isDirectStream = rawUrl.contains(".m3u8", ignoreCase = true) ||
            rawUrl.contains(".mpd", ignoreCase = true)

        if (isDirectStream) {
            playNativeStream(rawUrl, referer, statusBox, progress, statusText, titleBanner, fallbackRoomUrl = referer)
        } else {
            resolveAndPlayLiveStream(rawUrl, statusBox, progress, statusText, titleBanner)
        }
    }

    private fun playNativeStream(
        m3u8Url: String,
        referer: String,
        statusBox: LinearLayout,
        progress: ProgressBar,
        statusText: TextView,
        titleBanner: TextView,
        fallbackRoomUrl: String?
    ) {
        if (streamStarted) return
        streamStarted = true
        destroySniffer()

        val vv = videoView ?: return
        val headers = mutableMapOf(
            "User-Agent" to tvUserAgent,
            "Referer" to referer,
            "Origin" to "https://gavang33.live"
        )

        vv.setVideoURI(Uri.parse(m3u8Url), headers)
        vv.setOnPreparedListener { mp ->
            statusBox.visibility = View.GONE
            mp.isLooping = false
            vv.start()
            titleBanner.postDelayed({ titleBanner.visibility = View.GONE }, 4500)
        }
        vv.setOnErrorListener { _, _, _ ->
            // Nếu link .m3u8 lưu sẵn đã hết hạn token, tự động dò lại link mới từ phòng BLV
            if (!fallbackRoomUrl.isNullOrBlank() && fallbackRoomUrl != m3u8Url) {
                streamStarted = false
                resolveAndPlayLiveStream(fallbackRoomUrl, statusBox, progress, statusText, titleBanner)
            } else {
                progress.visibility = View.GONE
                statusBox.visibility = View.VISIBLE
                statusText.text = "Trận đấu chưa bắt đầu hoặc BLV chưa lên sóng.\nBấm Quay lại để chọn phòng khác."
            }
            true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveAndPlayLiveStream(
        roomUrl: String,
        statusBox: LinearLayout,
        progress: ProgressBar,
        statusText: TextView,
        titleBanner: TextView
    ) {
        statusBox.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        statusText.text = "Đang kiểm tra tín hiệu trực tiếp từ phòng BLV..."

        val timeoutRunnable = Runnable {
            if (!streamStarted) {
                destroySniffer()
                progress.visibility = View.GONE
                statusText.text = "Trận đấu chưa bắt đầu hoặc chưa có tín hiệu phát.\nVui lòng quay lại khi đến giờ bóng lăn."
            }
        }
        mainHandler.postDelayed(timeoutRunnable, 11_000)

        val wv = WebView(this).apply {
            visibility = View.GONE
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = tvUserAgent
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString().orEmpty()
                    if (!streamStarted && (reqUrl.contains(".m3u8", ignoreCase = true) || reqUrl.contains(".mpd", ignoreCase = true))) {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        mainHandler.post {
                            playNativeStream(
                                m3u8Url = reqUrl,
                                referer = roomUrl,
                                statusBox = statusBox,
                                progress = progress,
                                statusText = statusText,
                                titleBanner = titleBanner,
                                fallbackRoomUrl = null
                            )
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
        snifferWebView = wv
        wv.loadUrl(roomUrl, mapOf("Referer" to "https://gavang33.live/"))
    }

    private fun destroySniffer() {
        snifferWebView?.stopLoading()
        snifferWebView?.destroy()
        snifferWebView = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        videoView?.stopPlayback()
        destroySniffer()
        super.onDestroy()
    }
}
