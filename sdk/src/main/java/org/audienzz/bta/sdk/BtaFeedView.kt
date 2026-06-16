package org.audienzz.bta.sdk

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.ViewTreeObserver
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.audienzz.bta.sdk.analytics.BtaEvent
import org.audienzz.bta.sdk.analytics.BtaEventTracker
import org.audienzz.bta.sdk.analytics.BtaEventType

/**
 * BTA (Below The Article) Feed View.
 *
 * Embeds the Audienzz BTA feed widget in a WebView, auto-resizes to fit its content,
 * and automatically tracks analytics events.
 *
 * ## Basic usage
 *
 * ```xml
 * <org.audienzz.bta.sdk.BtaFeedView
 *     android:id="@+id/btaFeedView"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content" />
 * ```
 *
 * ```kotlin
 * // Application.onCreate() — initialise SDK once
 * BtaSdk.init(companyId = "your-publisher-id")
 *
 * // Set listener once (e.g. in Activity.onCreate)
 * btaFeedView.setListener(object : BtaFeedListener {
 *     override fun onArticleClick(payload: ArticleClickPayload) { /* handle */ }
 *     override fun onAdClick(payload: AdClickPayload): Boolean = false
 * })
 *
 * // Call every time the screen is entered (e.g. in onResume)
 * btaFeedView.load("your-bta-feed-id")
 *
 * // Lifecycle
 * override fun onResume()  { super.onResume();  btaFeedView.onResume() }
 * override fun onPause()   { super.onPause();   btaFeedView.onPause() }
 * override fun onDestroy() { super.onDestroy(); btaFeedView.destroy() }
 * ```
 */
class BtaFeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val webView: WebView = WebView(context)
    private var listener: BtaFeedListener? = null

    /** Feed ID from the most recent [load] call. */
    private var currentFeedId: String? = null

    /** Prevents duplicate viewable impression events per [load] call. */
    private var viewableImpressionFired = false

    /** Prevents duplicate [BtaFeedListener.onFeedLoaded] calls per [load] call. */
    private var feedLoadedFired = false

    /**
     * When `true`, the next [load] call with the same feed ID will be a no-op (aside from
     * resuming the WebView). Set to `true` just before [BtaWebViewActivity] opens so that
     * the inevitable `onResume → load()` cycle when the user presses Back does not reload
     * the feed unnecessarily.
     */
    private var suppressNextLoad = false

    /** Registered on the ViewTreeObserver to detect viewport entry after scroll. */
    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
        if (!viewableImpressionFired) checkViewabilityAndTrack()
    }

    init {
        BtaEventTracker.init(context)
        setupWebView()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Set the listener for feed events. Can be called at any time.
     * Replaces any previously set listener.
     */
    fun setListener(listener: BtaFeedListener) {
        this.listener = listener
    }

    /**
     * Load the BTA feed. Call this every time the publisher's screen is entered
     * (e.g. from `onResume`). Resets the view height and reloads the feed HTML.
     *
     * Automatically fires a `btafeed.pageview` analytics event.
     *
     * @param btaFeedId             The feed identifier provided by Audienzz.
     * @param pageUrl               The canonical URL of the article/page hosting the feed.
     *                              Used by the feed widget for contextual recommendations.
     * @param debug                 Enable feed debug logging (**do not use in production**).
     * @param mockRecommendations   Show mock recommendations instead of real ones
     *                              (**do not use in production**).
     * @param isDarkMode            Override the color scheme: `true` forces dark theme,
     *                              `false` forces light theme, `null` (default) auto-detects
     *                              from the system via `prefers-color-scheme`.
     */
    @JvmOverloads
    fun load(
        btaFeedId: String,
        pageUrl: String,
        debug: Boolean = false,
        mockRecommendations: Boolean = false,
        isDarkMode: Boolean? = null,
    ) {
        // If we're returning from BtaWebViewActivity for the same feed, skip the reload.
        // The WebView already has the content; just resume it and clear the flag.
        if (suppressNextLoad && btaFeedId == currentFeedId) {
            suppressNextLoad = false
            webView.onResume()
            return
        }
        suppressNextLoad = false

        currentFeedId = btaFeedId
        viewableImpressionFired = false
        feedLoadedFired = false
        updateWebViewHeight(0)

        // Fire page view event.
        fireEvent(BtaEventType.PAGEVIEW, btaFeedId)

        // Register scroll listener for viewable impression detection.
        viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
        viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
        // Check immediately in case view is already on screen.
        post { checkViewabilityAndTrack() }

        // Remove any previously registered bridge before adding the new one.
        webView.removeJavascriptInterface(JS_BRIDGE_NAME)

        val bridge = BtaJsBridge(
            context = context,
            btaFeedId = btaFeedId,
            listener = listener,
            onHeightChanged = { px ->
                val scaledPx = (px * resources.displayMetrics.density).toInt()
                updateWebViewHeight(scaledPx)
                // Fire onFeedLoaded the first time real content appears (height > 0).
                if (scaledPx > 0 && !feedLoadedFired) {
                    feedLoadedFired = true
                    listener?.onFeedLoaded()
                }
                // After content renders, re-check viewport visibility.
                if (!viewableImpressionFired) post { checkViewabilityAndTrack() }
            },
            onFeedLoaded = { /* JS SDK initialised — onFeedLoaded fires on first non-zero height */ },
            onFeedError = { error -> listener?.onFeedError(error) },
            onWillOpenWebView = { suppressNextLoad = true },
        )
        webView.addJavascriptInterface(bridge, JS_BRIDGE_NAME)

        val html = buildHtml(btaFeedId, pageUrl, debug, mockRecommendations, isDarkMode)
        webView.loadDataWithBaseURL(CDN_BASE_URL, html, "text/html", "UTF-8", null)
    }

    /** Call from `Activity.onResume()` or `Fragment.onResume()`. */
    fun onResume() = webView.onResume()

    /** Call from `Activity.onPause()` or `Fragment.onPause()`. */
    fun onPause() = webView.onPause()

    /** Call from `Activity.onDestroy()` or `Fragment.onDestroyView()`. */
    fun destroy() {
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
        }
        webView.stopLoading()
        webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        webView.destroy()
    }

    // ── Viewable impression ────────────────────────────────────────────────────

    /**
     * Fires `btafeed.viewable_impression` the first time ≥50% of the view is visible
     * in the device window. Resets on each [load] call.
     */
    private fun checkViewabilityAndTrack() {
        val feedId = currentFeedId ?: return
        if (viewableImpressionFired) return
        val visibleRect = Rect()
        if (!getLocalVisibleRect(visibleRect)) return
        val visibleArea = visibleRect.width().toLong() * visibleRect.height()
        val totalArea = width.toLong() * height
        if (totalArea > 0 && visibleArea * 2 >= totalArea) {
            viewableImpressionFired = true
            fireEvent(BtaEventType.VIEWABLE_IMPRESSION, feedId)
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun fireEvent(type: BtaEventType, btaFeedId: String) {
        BtaEventTracker.track(
            BtaEvent(
                type = type,
                companyId = BtaSdk.companyId,
                btaFeedId = btaFeedId,
                visitorId = "",  // BtaEventTracker.track() replaces this with the persisted ID
                sessionId = BtaEventTracker.sessionId,
            )
        )
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // Disable internal scrolling — the parent ScrollView handles all scrolling.
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = OVER_SCROLL_NEVER

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // All clicks are handled by the JS bridge; suppress any unexpected navigation.
                return true
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String,
            ) {
                Log.e(TAG, "WebView error $errorCode ($description) for $failingUrl")
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val level = msg.messageLevel()
                val text = "[JS:${msg.lineNumber()}] ${msg.message()}"
                when (level) {
                    ConsoleMessage.MessageLevel.ERROR   -> Log.e(TAG, text)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, text)
                    else                                -> Log.d(TAG, text)
                }
                return true
            }
        }
    }

    private fun updateWebViewHeight(heightPx: Int) {
        val lp = webView.layoutParams
        if (lp.height != heightPx) {
            lp.height = heightPx
            webView.layoutParams = lp
            requestLayout()
        }
    }

    private fun buildHtml(
        feedId: String,
        pageUrl: String,
        debug: Boolean,
        mockRecommendations: Boolean,
        isDarkMode: Boolean?,
    ): String {
        val debugLine = if (debug) "debug: true," else ""
        val mockLine = if (mockRecommendations) "mockRecommendations: true," else ""
        val darkLine = when (isDarkMode) {
            true  -> "forceDarkTheme: true,"
            false -> ""
            null  -> "isDarkThemeSupported: true,"
        }

        // Uses plain function() syntax for API 24 WebView compatibility.
        // onAdImpression and onArticleImpression handlers are wired and ready —
        // they will fire once the BTA JS team implements those callbacks.
        return """
            <!doctype html>
            <html lang="en">
            <head>
                <base target="_parent" />
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0" />
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    html, body { overflow: hidden; width: 100%; }
                </style>
            </head>
            <body>
                <script type="text/javascript">
                    window.adnzBtaFeed = window.adnzBtaFeed || {};
                    window.adnzBtaFeed.queue = window.adnzBtaFeed.queue || [];
                    window.adnzBtaFeed.queue.push(function() {
                        try {
                            window.adnzBtaFeed.start({
                                btaFeedId: '$feedId',
                                url: '$pageUrl',
                                webview: true,
                                $debugLine
                                $mockLine
                                $darkLine
                                onArticleClick: function(payload) {
                                    payload.event.preventDefault();
                                    AndroidBridge.onArticleClick(JSON.stringify({
                                        article: payload.article,
                                        btaFeedId: payload.btaFeedId,
                                        index: payload.index
                                    }));
                                },
                                onAdClick: function(payload) {
                                    payload.event.preventDefault();
                                    var unit = payload.adUnit || {};
                                    var ad   = unit.ad || {};
                                    var url = ad.clickUrl || ad.url
                                           || unit.clickUrl || unit.url || unit.destinationUrl
                                           || unit.targetUrl || unit.href
                                           || payload.clickUrl || payload.url || '';
                                    AndroidBridge.onAdClick(JSON.stringify({
                                        adUnit: unit,
                                        url: url,
                                        btaFeedId: payload.btaFeedId,
                                        index: payload.index
                                    }));
                                },
                                onNativeAdClick: function(payload) {
                                    payload.event.preventDefault();
                                    var unit = payload.adUnit || {};
                                    var ad   = unit.ad || {};
                                    var url = ad.clickUrl || ad.url
                                           || unit.clickUrl || unit.url || unit.destinationUrl
                                           || unit.targetUrl || unit.href
                                           || payload.clickUrl || payload.url || '';
                                    AndroidBridge.onNativeAdClick(JSON.stringify({
                                        adUnit: unit,
                                        url: url,
                                        btaFeedId: payload.btaFeedId,
                                        index: payload.index
                                    }));
                                },
                                onAdImpression: function(payload) {
                                    AndroidBridge.onAdImpression(JSON.stringify({
                                        adUnit: payload.adUnit,
                                        btaFeedId: payload.btaFeedId,
                                        index: payload.index
                                    }));
                                },
                                onArticleImpression: function(payload) {
                                    AndroidBridge.onArticleImpression(JSON.stringify({
                                        article: payload.article,
                                        btaFeedId: payload.btaFeedId,
                                        index: payload.index
                                    }));
                                },
                                onError: function(error) {
                                    var msg = error && error.message ? error.message
                                            : (typeof error === 'string' ? error : 'Feed error');
                                    AndroidBridge.onFeedError(msg);
                                }
                            });
                        } catch (e) {
                            AndroidBridge.onFeedError('Failed to start feed: ' + (e && e.message ? e.message : String(e)));
                            return;
                        }

                        function reportHeight() {
                            var h = document.documentElement.scrollHeight;
                            AndroidBridge.onContentHeightChanged(h);
                        }

                        if (window.ResizeObserver) {
                            new ResizeObserver(function() { reportHeight(); })
                                .observe(document.documentElement);
                        } else {
                            new MutationObserver(function() { reportHeight(); })
                                .observe(document.body, {
                                    childList: true,
                                    subtree: true,
                                    attributes: true
                                });
                        }
                        reportHeight();
                    });
                </script>
                <script async src="${CDN_BASE_URL}bta-feed/index.js"
                    onerror="AndroidBridge.onFeedError('Failed to load BTA feed script')"></script>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "BtaFeedView"
        private const val JS_BRIDGE_NAME = "AndroidBridge"

        internal const val CDN_BASE_URL = "https://cdn.adnz.co/"
    }
}
