package org.audienzz.bta.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import org.audienzz.bta.sdk.analytics.BtaEvent
import org.audienzz.bta.sdk.analytics.BtaEventTracker
import org.audienzz.bta.sdk.analytics.BtaEventType
import org.audienzz.bta.sdk.model.AdClickPayload
import org.audienzz.bta.sdk.model.ArticleClickPayload
import org.json.JSONObject

/**
 * JavaScript interface exposed to the BTA feed as `AndroidBridge`.
 *
 * NOTE: All @JavascriptInterface methods are invoked on a background thread by the WebView.
 * Every method posts work to the main thread before touching any UI or listener callbacks.
 */
internal class BtaJsBridge(
    private val context: Context,
    private val btaFeedId: String,
    private val listener: BtaFeedListener?,
    private val onHeightChanged: (Int) -> Unit,
    private val onFeedLoaded: () -> Unit,
    private val onFeedError: (String) -> Unit,
    /** Invoked on the main thread just before [BtaWebViewActivity] is started (article or ad). */
    private val onWillOpenWebView: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Article click ─────────────────────────────────────────────────────────

    @JavascriptInterface
    fun onArticleClick(json: String) {
        val payload = try {
            ArticleClickPayload.fromJson(json)
        } catch (e: Exception) {
            mainHandler.post { onFeedError("Failed to parse article click payload: ${e.message}") }
            return
        }
        trackEvent(BtaEventType.ARTICLE_CLICK, index = payload.index)
        mainHandler.post {
            val handled = listener?.onArticleClick(payload) ?: false
            if (!handled) {
                openInWebView(payload.url)
            }
        }
    }

    // ── Ad click (display ad) ─────────────────────────────────────────────────

    @JavascriptInterface
    fun onAdClick(json: String) {
        handleAdClick(json)
    }

    // ── Native ad click — routed to the same listener method as onAdClick ─────

    @JavascriptInterface
    fun onNativeAdClick(json: String) {
        handleAdClick(json)
    }

    // ── Ad impression (fired by JS when an ad enters the viewport) ────────────
    // Wired and ready; will fire once the BTA JS team adds the onAdImpression callback.

    @JavascriptInterface
    fun onAdImpression(json: String) {
        val index = try { JSONObject(json).optInt("index", -1) } catch (e: Exception) { -1 }
        trackEvent(BtaEventType.AD_IMPRESSION, index = index.takeIf { it >= 0 })
    }

    // ── Article impression (fired by JS when an article enters the viewport) ──
    // Wired and ready; will fire once the BTA JS team adds the onArticleImpression callback.

    @JavascriptInterface
    fun onArticleImpression(json: String) {
        val index = try { JSONObject(json).optInt("index", -1) } catch (e: Exception) { -1 }
        trackEvent(BtaEventType.ARTICLE_IMPRESSION, index = index.takeIf { it >= 0 })
    }

    // ── Height reporting ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun onContentHeightChanged(height: Int) {
        mainHandler.post { onHeightChanged(height) }
    }

    // ── Feed ready ────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun onFeedReady() {
        mainHandler.post { onFeedLoaded() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun handleAdClick(json: String) {
        // Log the raw JSON so we can verify the shape coming from the BTA JS SDK.
        Log.d(TAG, "Ad click raw JSON: $json")
        val payload = try {
            AdClickPayload.fromJson(json)
        } catch (e: Exception) {
            mainHandler.post { onFeedError("Failed to parse ad click payload: ${e.message}") }
            return
        }
        trackEvent(BtaEventType.AD_CLICK, index = payload.index)
        mainHandler.post {
            val handled = listener?.onAdClick(payload) ?: false
            if (!handled) {
                openInWebView(payload.url)
            }
        }
    }

    private companion object {
        private const val TAG = "BtaJsBridge"
    }

    private fun trackEvent(type: BtaEventType, index: Int? = null) {
        BtaEventTracker.track(
            BtaEvent(
                type = type,
                publisherId = BtaSdk.publisherId,
                btaFeedId = btaFeedId,
                visitorId = "",  // BtaEventTracker.track() overwrites this with the persisted ID
                sessionId = BtaEventTracker.sessionId,
                index = index,
            )
        )
    }

    private fun openInWebView(url: String) {
        if (url.isBlank()) return
        onWillOpenWebView()
        val intent = BtaWebViewActivity.createIntent(context, url).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to system browser
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            } catch (ignored: Exception) {
                // Nothing we can do
            }
        }
    }
}
