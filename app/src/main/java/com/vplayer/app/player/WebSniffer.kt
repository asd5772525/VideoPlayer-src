package com.vplayer.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.vplayer.app.data.net.DEFAULT_UA
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 隐藏 WebView 嗅探：加载解析页，拦截页面发出的请求，捕获第一个 m3u8 / mp4 / flv 地址。
 */
object WebSniffer {
    private val mediaRegex = Regex("""\.(m3u8|mp4|flv)(\?|$|#)""", RegexOption.IGNORE_CASE)

    fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".m3u8") || mediaRegex.containsMatchIn(lower)) {
            // 排除明显是广告 / 统计的地址
            return !(lower.contains("/ad/") || lower.contains("adservice"))
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(context: Context, pageUrl: String, timeoutMs: Long = 15_000, referer: String? = null, userAgent: String? = null): String? =
        withContext(Dispatchers.Main) {
            val result = CompletableDeferred<String>()
            val web = WebView(context.applicationContext)
            try {
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    @Suppress("DEPRECATION")
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        if (!result.isCompleted && looksLikeMedia(u)) result.complete(u)
                        return null
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (url != null && !result.isCompleted && looksLikeMedia(url)) result.complete(url)
                    }
                }
                val headers = if (referer != null) mapOf("Referer" to referer) else emptyMap()
                web.loadUrl(pageUrl, headers)
                withTimeoutOrNull(timeoutMs) { result.await() }
            } finally {
                web.stopLoading()
                web.destroy()
            }
        }
}
