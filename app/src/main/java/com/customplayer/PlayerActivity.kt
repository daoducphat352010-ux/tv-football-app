package com.customplayer

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class PlayerActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var videoView: VideoView? = null

    private val tvUserAgent =
        "Mozilla/5.0 (Linux; Android 13; Smart TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = intent.getStringExtra("title") ?: "Trực Tiếp Bóng Đá"
        val streamUrl = intent.getStringExtra("url").orEmpty().trim()
        val referer = intent.getStringExtra("referer").orEmpty().trim()

        if (streamUrl.isBlank()) {
            Toast.makeText(this, "Không tìm thấy đường dẫn phát", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

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

        val isDirectStream = streamUrl.contains(".m3u8", ignoreCase = true) ||
            streamUrl.contains(".mpd", ignoreCase = true)

        if (isDirectStream) {
            setupDirectVideoPlayer(root, progress, titleBanner, streamUrl, referer)
        } else {
            setupWebPlayer(root, progress, titleBanner, streamUrl, referer)
        }

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView?.canGoBack() == true) {
                    webView?.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupDirectVideoPlayer(
        root: FrameLayout,
        progress: ProgressBar,
        titleBanner: TextView,
        url: String,
        referer: String
    ) {
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

        val headers = mutableMapOf("User-Agent" to tvUserAgent)
        if (referer.isNotBlank()) headers["Referer"] = referer

        vv.setVideoURI(Uri.parse(url), headers)
        vv.setOnPreparedListener { mp ->
            progress.visibility = View.GONE
            mp.isLooping = false
            vv.start()
            titleBanner.postDelayed({ titleBanner.visibility = View.GONE }, 4500)
        }
        vv.setOnErrorListener { _, _, _ ->
            progress.visibility = View.GONE
            Toast.makeText(this, "Luồng HD lỗi, hãy thử chọn nguồn Web Player", Toast.LENGTH_LONG).show()
            true
        }

        root.addView(vv)
        root.addView(progress)
        root.addView(titleBanner)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebPlayer(
        root: FrameLayout,
        progress: ProgressBar,
        titleBanner: TextView,
        url: String,
        referer: String
    ) {
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = tvUserAgent
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    progress.visibility = View.GONE
                    titleBanner.postDelayed({ titleBanner.visibility = View.GONE }, 4500)
                }
            }
        }
        webView = wv

        val headers = mutableMapOf<String, String>()
        if (referer.isNotBlank()) headers["Referer"] = referer
        wv.loadUrl(url, headers)

        root.addView(wv)
        root.addView(progress)
        root.addView(titleBanner)
    }

    override fun onDestroy() {
        videoView?.stopPlayback()
        webView?.destroy()
        super.onDestroy()
    }
}
