package org.audienzz.bta.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.ViewTreeObserver
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
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
    private val progressBar: ProgressBar = ProgressBar(context)
    private var listener: BtaFeedListener? = null

    /** True while the initial-load spinner is shown (height reserved, not 0). */
    private var loading = false

    /** Bumped per load so a stale loading-timeout can't collapse a newer load. */
    private var loadGeneration = 0

    /** Feed ID from the most recent [load] call. */
    private var currentFeedId: String? = null

    /** Parameters from the most recent real load, replayed by [reload]. */
    private data class LoadParams(
        val btaFeedId: String,
        val pageUrl: String,
        val debug: Boolean,
        val mockRecommendations: Boolean,
        val isDarkMode: Boolean?,
        val isLoadingHolderEnabled: Boolean,
        val fontStyleUrls: List<String>,
        val mockAds: Boolean,
    )
    private var lastLoadParams: LoadParams? = null

    /**
     * While > 0, a [reload] is in progress and the view height must not shrink below this
     * value — it holds the pre-reload height so the feed doesn't collapse to ~0 while the
     * new (blank-then-growing) content loads. Released once the new content grows back to
     * this height, or by [releaseReloadFloor] after [RELOAD_FLOOR_TIMEOUT_MS].
     */
    private var reloadHeightFloorPx = 0

    /** Set in [destroy]; blocks pending delayed callbacks from touching the destroyed WebView. */
    private var destroyed = false

    /** When true (debug loads), every WebView request and every fetch/XHR is logged to logcat. */
    private var debugLogging = false

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

    /** Default SharedPreferences a TCF CMP writes `IABTCF_*` consent to. */
    private val consentPrefs: SharedPreferences? = try {
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
    } catch (e: Exception) {
        null
    }

    /** Debounced re-read + push of consent to the live bridge after the CMP writes updates. */
    private val consentPushRunnable = Runnable { pushConsentUpdate() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // key is null when storage is cleared (Android R+); treat as a consent change too.
        if (key == null || key.startsWith("IABTCF_")) {
            removeCallbacks(consentPushRunnable)
            postDelayed(consentPushRunnable, CONSENT_PUSH_DEBOUNCE_MS)
        }
    }

    init {
        BtaEventTracker.init(context)
        consentPrefs?.registerOnSharedPreferenceChangeListener(prefsListener)
        setupWebView()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        // Centered loading spinner, shown over the reserved height during the initial load.
        progressBar.visibility = GONE
        addView(
            progressBar,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
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
     * @param mockAds               Show mock/test ad creatives (the violet test banner) instead
     *                              of running the real ad auction. Useful for verifying ad slots
     *                              in test builds where real demand won't fill (**do not use in
     *                              production**).
     * @param isDarkMode            Override the color scheme: `true` forces dark theme,
     *                              `false` forces light theme, `null` (default) auto-detects
     *                              from the system via `prefers-color-scheme`.
     * @param isLoadingHolderEnabled When `true` (default), the SDK reserves height and shows
     *                              a loading spinner during the initial load. Set `false` to
     *                              suppress it and show your own loading placeholder instead
     *                              (the feed stays at 0 height until content arrives).
     * @param fontStyleUrls         Font stylesheet URLs (containing `@font-face` rules) injected
     *                              at the document level so the fonts are usable inside the feed's
     *                              Shadow DOM. Android's WebView ignores `@font-face` declared inside
     *                              a shadow root, so this makes custom fonts render. Defaults to the
     *                              standard AdConsole fonts ([DEFAULT_FONT_STYLE_URLS]); pass your
     *                              own URLs to override, or an empty list to disable.
     */
    @JvmOverloads
    fun load(
        btaFeedId: String,
        pageUrl: String,
        debug: Boolean = false,
        mockRecommendations: Boolean = false,
        isDarkMode: Boolean? = null,
        isLoadingHolderEnabled: Boolean = true,
        fontStyleUrls: List<String> = DEFAULT_FONT_STYLE_URLS,
        mockAds: Boolean = false,
    ) {
        // If we're returning from BtaWebViewActivity for the same feed, skip the reload.
        // The WebView already has the content; just resume it and clear the flag.
        if (suppressNextLoad && btaFeedId == currentFeedId) {
            suppressNextLoad = false
            webView.onResume()
            // Re-query the current height — the layout may have grown while we were away
            // (e.g. teaser images finished loading), so the constraint from before the
            // article opened could be too short and clip the feed.
            webView.evaluateJavascript(
                HEIGHT_JS
            ) { value ->
                if (destroyed) return@evaluateJavascript
                val cssPx = value?.toFloatOrNull() ?: return@evaluateJavascript
                if (cssPx > 0f) {
                    updateWebViewHeight((cssPx * resources.displayMetrics.density).toInt())
                }
            }
            return
        }
        suppressNextLoad = false
        performLoad(
            LoadParams(btaFeedId, pageUrl, debug, mockRecommendations, isDarkMode, isLoadingHolderEnabled, fontStyleUrls, mockAds),
            resetHeight = true,
        )
    }

    /**
     * Reload the feed content in place — fetches fresh recommendations without first
     * collapsing the view height to 0, so there is no blank flash or layout jump. The
     * pre-reload height is held while the new content loads and only settles to the new
     * size once it has rendered, so the feed does not visibly jump.
     *
     * Use this when your app refreshes page content (e.g. on back navigation) and you
     * want new recommendations without the initial-load height reset. Replays the most
     * recent [load] parameters; no-op if [load] has never been called.
     */
    fun reload() {
        val params = lastLoadParams ?: return
        // Skip the suppress-on-return flag — this is an explicit content refresh.
        suppressNextLoad = false
        // Hold the current height so the feed doesn't collapse while the new content
        // loads (the fresh page reports a near-zero height before the feed renders).
        reloadHeightFloorPx = webView.height.takeIf { it > 0 } ?: 0
        performLoad(params, resetHeight = false)
        // Safety net: release the floor after a max wait so a genuinely shorter feed
        // settles to its true (smaller) height even if it never reaches the old one.
        postDelayed({ releaseReloadFloor() }, RELOAD_FLOOR_TIMEOUT_MS)
    }

    /** Drop the reload height floor and settle to the new content's true height. */
    private fun releaseReloadFloor() {
        if (destroyed || reloadHeightFloorPx == 0) return
        reloadHeightFloorPx = 0
        webView.evaluateJavascript(
            HEIGHT_JS
        ) { value ->
            if (destroyed) return@evaluateJavascript
            val cssPx = value?.toFloatOrNull() ?: return@evaluateJavascript
            if (cssPx > 0f) updateWebViewHeight((cssPx * resources.displayMetrics.density).toInt())
        }
    }

    private fun performLoad(params: LoadParams, resetHeight: Boolean) {
        currentFeedId = params.btaFeedId
        lastLoadParams = params
        debugLogging = params.debug
        viewableImpressionFired = false
        feedLoadedFired = false
        // On a full load, show the loading state (reserved height + spinner) instead of
        // collapsing to 0 — unless the publisher opted out to show their own placeholder;
        // on a reload the floor (set by reload()) keeps the current height.
        if (resetHeight) {
            reloadHeightFloorPx = 0
            if (params.isLoadingHolderEnabled) setLoading(true) else updateWebViewHeight(0)
        }

        // Fire page view event.
        fireEvent(BtaEventType.PAGEVIEW, params.btaFeedId)

        // Register scroll listener for viewable impression detection.
        viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
        viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
        // Check immediately in case view is already on screen.
        post { checkViewabilityAndTrack() }

        // Remove any previously registered bridge before adding the new one.
        webView.removeJavascriptInterface(JS_BRIDGE_NAME)

        val bridge = BtaJsBridge(
            context = context,
            btaFeedId = params.btaFeedId,
            listener = listener,
            onHeightChanged = { px ->
                val scaledPx = (px * resources.displayMetrics.density).toInt()
                // While the loading state is shown, ignore empty-height reports (the page
                // reports ~0 until the feed renders); switch to real content on the first
                // non-zero height.
                if (loading && scaledPx <= 0) {
                    // keep spinner + reserved height
                } else {
                    if (loading) setLoading(false)
                    if (reloadHeightFloorPx > 0) {
                        // Mid-reload: never shrink below the pre-reload height. Once the new
                        // content grows back to it, following the true height is safe again.
                        updateWebViewHeight(maxOf(scaledPx, reloadHeightFloorPx))
                        if (scaledPx >= reloadHeightFloorPx) reloadHeightFloorPx = 0
                    } else {
                        updateWebViewHeight(scaledPx)
                    }
                    // Fire onFeedLoaded the first time real content appears (height > 0).
                    if (scaledPx > 0 && !feedLoadedFired) {
                        feedLoadedFired = true
                        listener?.onFeedLoaded()
                    }
                    // After content renders, re-check viewport visibility.
                    if (!viewableImpressionFired) post { checkViewabilityAndTrack() }
                }
            },
            onFeedLoaded = { /* JS SDK initialised — onFeedLoaded fires on first non-zero height */ },
            onFeedError = { error ->
                // No content will render — clear the loading state and collapse.
                if (loading) {
                    setLoading(false)
                    updateWebViewHeight(0)
                }
                listener?.onFeedError(error)
            },
            onWillOpenWebView = { suppressNextLoad = true },
        )
        webView.addJavascriptInterface(bridge, JS_BRIDGE_NAME)

        val html = buildHtml(
            params.btaFeedId,
            params.pageUrl,
            params.debug,
            params.mockRecommendations,
            params.mockAds,
            params.isDarkMode,
            params.fontStyleUrls,
        )
        webView.loadDataWithBaseURL(CDN_BASE_URL, html, "text/html", "UTF-8", null)
    }

    /** Call from `Activity.onResume()` or `Fragment.onResume()`. */
    fun onResume() = webView.onResume()

    /** Call from `Activity.onPause()` or `Fragment.onPause()`. */
    fun onPause() = webView.onPause()

    /** Call from `Activity.onDestroy()` or `Fragment.onDestroyView()`. */
    fun destroy() {
        // Block any pending delayed callbacks (e.g. the reload-floor release) from touching
        // the WebView after it's destroyed — that is undefined behavior and crashes some OEMs.
        destroyed = true
        removeCallbacks(consentPushRunnable)
        consentPrefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
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

            // Logs every resource request the WebView makes (page, scripts, XHR/fetch, ads,
            // beacons). Returns null so the request proceeds unchanged. Debug loads only.
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (debugLogging) {
                    val headers = request.requestHeaders.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                    Log.d(NET_TAG, "REQ ${request.method} ${request.url}${if (headers.isNotEmpty()) "  [$headers]" else ""}")
                }
                return null
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Width change (e.g. orientation change) reflows the content to a new width, so its
        // height changes. Re-measure and apply it — no reload() needed by the publisher.
        if (w > 0 && oldw > 0 && w != oldw && !loading && !destroyed && currentFeedId != null) {
            remeasureAfterResize(attempt = 0)
        }
    }

    /**
     * Re-measure the feed after a width change settles and apply the true height (which may
     * grow or shrink). Never reloads and never holds a floor — on rotation we follow the real
     * new height in both directions. A couple of passes let the WebView finish reflowing.
     */
    private fun remeasureAfterResize(attempt: Int) {
        if (destroyed) return
        postDelayed({
            if (destroyed) return@postDelayed
            webView.evaluateJavascript(
                HEIGHT_JS
            ) { value ->
                if (destroyed) return@evaluateJavascript
                val cssPx = value?.toFloatOrNull()
                if (cssPx != null && cssPx > 0f) {
                    updateWebViewHeight((cssPx * resources.displayMetrics.density).toInt())
                }
            }
            if (attempt + 1 < RESIZE_REMEASURE_PASSES) remeasureAfterResize(attempt + 1)
        }, RESIZE_REMEASURE_STEP_MS)
    }

    private fun updateWebViewHeight(heightPx: Int) {
        val lp = webView.layoutParams
        if (lp.height != heightPx) {
            lp.height = heightPx
            webView.layoutParams = lp
            requestLayout()
        }
    }

    /**
     * Toggle the initial-load loading state. When enabled, reserves [LOADING_HEIGHT_DP] of
     * height and shows a centered spinner instead of collapsing to 0; a timeout collapses
     * it if content never arrives, so there is no permanent dead space.
     */
    private fun setLoading(isLoading: Boolean) {
        loading = isLoading
        if (isLoading) {
            progressBar.visibility = VISIBLE
            updateWebViewHeight((LOADING_HEIGHT_DP * resources.displayMetrics.density).toInt())
            val generation = ++loadGeneration
            postDelayed({
                if (!destroyed && loading && generation == loadGeneration) {
                    setLoading(false)
                    updateWebViewHeight(0)
                }
            }, LOADING_TIMEOUT_MS)
        } else {
            progressBar.visibility = GONE
        }
    }

    /** Escape a string for safe use inside a double-quoted HTML attribute. */
    private fun htmlAttributeEscaped(value: String): String =
        value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * Reads the app's IAB TCF consent (as written by a CMP such as Usercentrics into the default
     * SharedPreferences — `IABTCF_TCString` / `IABTCF_gdprApplies`) and returns a `<script>` that
     * exposes it to the feed's WebView through the standard `__tcfapi`, so the ad layer (Prebid)
     * inside the feed picks up the user's consent. Returns "" when no consent string is stored yet
     * — the feed then behaves exactly as before.
     */
    private fun tcfConsentScript(): String {
        val snapshot = BtaConsent.tcfSnapshot(context) ?: return ""
        return tcfBridgeScript(snapshot.json)
    }

    /**
     * Builds the `__tcfapi` CMP stub that serves a **full** TCData object (decoded purpose/vendor/
     * special-feature/publisher consents and restrictions, built in [BtaConsent.buildTcData]) to
     * anything in the feed that reads TCF v2. Prebid enforcement reads `purpose.consents` /
     * `vendor.consents`, so these must be present or every bidder is blocked.
     *
     * The payload is a single JSON blob; only `<` is escaped (to `<`) so a malformed stored
     * value cannot break out of the `<script>` (`</script>`) — JSON itself already escapes quotes,
     * backslashes and newlines. The stub supports real add/removeEventListener with unique ids, and
     * [pushConsentUpdate] calls `window.__btaTcfApplyUpdate` so withdrawn/updated consent reaches
     * in-page auctions without a full reload.
     */
    private fun tcfBridgeScript(tcDataJson: String): String {
        val safe = tcDataJson.replace("<", "\\u003c")
        return """
                <script type="text/javascript">
                    (function() {
                        var base = $safe;
                        var listeners = {}, nextId = 0;
                        function snap(listenerId) {
                            var d = JSON.parse(JSON.stringify(base));
                            if (listenerId !== undefined) { d.listenerId = listenerId; }
                            return d;
                        }
                        function run(cmd, ver, cb, arg) {
                            try {
                                if (cmd === 'ping') {
                                    cb({ gdprApplies: base.gdprApplies, cmpLoaded: true, cmpStatus: base.cmpStatus, displayStatus: 'hidden', apiVersion: '2.2', cmpId: base.cmpId, cmpVersion: base.cmpVersion, tcfPolicyVersion: base.tcfPolicyVersion });
                                    return;
                                }
                                if (ver !== undefined && ver !== null && ver !== 2) { cb(null, false); return; }
                                if (cmd === 'getTCData') { cb(snap(undefined), true); }
                                else if (cmd === 'addEventListener') { var id = nextId++; listeners[id] = cb; cb(snap(id), true); }
                                else if (cmd === 'removeEventListener') {
                                    if (typeof arg === 'number' && listeners.hasOwnProperty(arg)) { delete listeners[arg]; cb(true, true); }
                                    else { cb(false, false); }
                                }
                                else { cb(null, false); }
                            } catch (e) {}
                        }
                        window.__btaTcfApplyUpdate = function(json) {
                            try {
                                base = JSON.parse(json);
                                for (var id in listeners) {
                                    if (!listeners.hasOwnProperty(id)) { continue; }
                                    var ev = snap(parseInt(id, 10));
                                    ev.eventStatus = 'useractioncomplete';
                                    listeners[id](ev, true);
                                }
                            } catch (e) {}
                        };
                        if (typeof window.__tcfapi !== 'function') { window.__tcfapi = run; }
                        window.addEventListener('message', function(e) {
                            var d = e.data, p;
                            try { p = (typeof d === 'string') ? JSON.parse(d) : d; } catch (err) { return; }
                            var c = p && p.__tcfapiCall; if (!c) return;
                            run(c.command, c.version, function(rv, ok) {
                                var r = { __tcfapiReturn: { returnValue: rv, success: ok, callId: c.callId } };
                                e.source.postMessage((typeof d === 'string') ? JSON.stringify(r) : r, '*');
                            }, c.parameter);
                        }, false);
                        var locatorAdded = false;
                        function addLocator() {
                            if (locatorAdded || window.frames['__tcfapiLocator']) { return; }
                            var parent = document.body || document.documentElement;
                            if (!parent) { return; }
                            var f = document.createElement('iframe');
                            f.style.cssText = 'display:none'; f.name = '__tcfapiLocator';
                            parent.appendChild(f); locatorAdded = true;
                        }
                        addLocator();
                        if (!locatorAdded) { document.addEventListener('DOMContentLoaded', addLocator); }
                    })();
                </script>
        """.trimIndent()
    }

    /** Re-reads consent from storage and pushes it to the live bridge so in-page auctions see the
     *  change (e.g. the user withdraws consent) without a full reload. No-op before the bridge loads. */
    private fun pushConsentUpdate() {
        if (destroyed) return
        val snapshot = BtaConsent.tcfSnapshot(context) ?: return
        val literal = JSONObject.quote(snapshot.json)
        webView.evaluateJavascript(
            "window.__btaTcfApplyUpdate && window.__btaTcfApplyUpdate($literal)",
            null,
        )
    }

    private fun buildHtml(
        feedId: String,
        pageUrl: String,
        debug: Boolean,
        mockRecommendations: Boolean,
        mockAds: Boolean,
        isDarkMode: Boolean?,
        fontStyleUrls: List<String>,
    ): String {
        val debugLine = if (debug) "debug: true," else ""
        val mockLine = if (mockRecommendations) "mockRecommendations: true," else ""
        val mockAdsLine = if (mockAds) "mockAds: true," else ""
        val darkLine = when (isDarkMode) {
            true  -> "forceDarkTheme: true,"
            false -> ""
            null  -> "isDarkThemeSupported: true,"
        }

        // Inject the publisher's font stylesheets at the DOCUMENT level so their @font-face
        // rules are registered globally and usable inside the feed's Shadow DOM. Android's
        // (older) WebView ignores @font-face declared inside a shadow root, so a document-level
        // <link> is what makes the custom fonts render.
        val fontLinks = fontStyleUrls.joinToString("\n                ") { url ->
            "<link rel=\"stylesheet\" href=\"${htmlAttributeEscaped(url)}\" />"
        }

        // Bridge the app's IAB TCF consent into the feed so Prebid picks it up (empty if none).
        val consentScript = tcfConsentScript()

        // Debug only: wrap fetch / XMLHttpRequest / sendBeacon so every request and response
        // (method, URL, and truncated body) is printed to console.log — which the WebChromeClient
        // forwards to logcat. Runs synchronously in <head> so it wraps them before the feed loads.
        val netLogScript = if (!debug) "" else """
                <script type="text/javascript">
                    (function() {
                        function log(s) { try { console.log('[NET] ' + s); } catch (e) {} }
                        var of = window.fetch;
                        if (of) {
                            window.fetch = function(input, init) {
                                var url = (typeof input === 'string') ? input : (input && input.url);
                                var method = (init && init.method) || (input && input.method) || 'GET';
                                var body = init && init.body;
                                log('FETCH REQ ' + method + ' ' + url + (body ? (' body=' + String(body).slice(0, 500)) : ''));
                                return of.apply(this, arguments).then(function(res) {
                                    try { res.clone().text().then(function(t) { log('FETCH RES ' + res.status + ' ' + url + ' ' + String(t).slice(0, 1000)); }); } catch (e) {}
                                    return res;
                                }).catch(function(err) { log('FETCH ERR ' + url + ' ' + err); throw err; });
                            };
                        }
                        var OX = window.XMLHttpRequest;
                        if (OX) {
                            var op = OX.prototype.open, os = OX.prototype.send;
                            OX.prototype.open = function(m, u) { this.__m = m; this.__u = u; return op.apply(this, arguments); };
                            OX.prototype.send = function(b) {
                                var self = this;
                                log('XHR REQ ' + this.__m + ' ' + this.__u + (b ? (' body=' + String(b).slice(0, 500)) : ''));
                                this.addEventListener('load', function() { log('XHR RES ' + self.status + ' ' + self.__u + ' ' + String(self.responseText || '').slice(0, 1000)); });
                                this.addEventListener('error', function() { log('XHR ERR ' + self.__u); });
                                return os.apply(this, arguments);
                            };
                        }
                        if (navigator.sendBeacon) {
                            var ob = navigator.sendBeacon.bind(navigator);
                            navigator.sendBeacon = function(u, d) { log('BEACON ' + u + (d ? (' body=' + String(d).slice(0, 500)) : '')); return ob(u, d); };
                        }
                    })();
                </script>""".trimIndent()

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
                $consentScript
                $netLogScript
                $fontLinks
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
                                $mockAdsLine
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

                        // Measure the BODY content height (not documentElement, whose scrollHeight is
                        // clamped to the viewport/frame and so can't shrink — leaving a huge gap after
                        // the feed gets shorter, e.g. rotating to landscape).
                        function reportHeight() {
                            var body = document.body;
                            var h = body ? Math.max(body.scrollHeight, body.offsetHeight) : 0;
                            AndroidBridge.onContentHeightChanged(h);
                        }

                        if (window.ResizeObserver) {
                            var ro = new ResizeObserver(function() { reportHeight(); });
                            ro.observe(document.documentElement);
                            if (document.body) ro.observe(document.body);
                        } else {
                            new MutationObserver(function() { reportHeight(); })
                                .observe(document.body, {
                                    childList: true,
                                    subtree: true,
                                    attributes: true
                                });
                        }
                        // Re-measure once every image has decoded — recommendation teasers are
                        // added dynamically, so their loads can expand the layout after first paint.
                        window.addEventListener('load', function() { reportHeight(); });
                        document.addEventListener('load', function(e) {
                            if (e.target && e.target.tagName === 'IMG') reportHeight();
                        }, true);
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
        private const val NET_TAG = "BtaFeedNet"
        private const val JS_BRIDGE_NAME = "AndroidBridge"

        /** Max time the reload height floor is held before settling to the new height. */
        private const val RELOAD_FLOOR_TIMEOUT_MS = 1500L
        private const val CONSENT_PUSH_DEBOUNCE_MS = 250L

        /** Reserved height (dp) shown with a spinner during the initial load. */
        private const val LOADING_HEIGHT_DP = 120f

        /** Max time the loading spinner shows before collapsing if no content arrives. */
        private const val LOADING_TIMEOUT_MS = 10_000L

        /** Re-measure passes and spacing after a width change (orientation), to catch reflow. */
        private const val RESIZE_REMEASURE_PASSES = 3
        private const val RESIZE_REMEASURE_STEP_MS = 100L

        /**
         * JS that measures the BODY content height. Not `documentElement`, whose scrollHeight is
         * clamped to the viewport/frame height and so can never report shorter than the current
         * frame — leaving a huge gap after the feed shrinks (e.g. rotating to landscape).
         */
        private const val HEIGHT_JS =
            "document.body ? Math.max(document.body.scrollHeight, document.body.offsetHeight) : 0"

        internal const val CDN_BASE_URL = "https://cdn.adnz.co/"

        /**
         * Font stylesheets injected at the document level by default so the standard AdConsole
         * fonts render inside the feed's Shadow DOM (needed on Android). Publishers can override
         * via [load]'s `fontStyleUrls`, or pass an empty list to disable.
         */
        @JvmField
        val DEFAULT_FONT_STYLE_URLS: List<String> = listOf(
            "${CDN_BASE_URL}business-click-fonts/Knockout/stylesheet.css",
            "${CDN_BASE_URL}business-click-fonts/Tiempos/stylesheet.css",
        )
    }
}
