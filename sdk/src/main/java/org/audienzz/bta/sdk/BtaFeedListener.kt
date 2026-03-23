package org.audienzz.bta.sdk

import org.audienzz.bta.sdk.model.AdClickPayload
import org.audienzz.bta.sdk.model.ArticleClickPayload

/**
 * Listener for events emitted by the BTA feed.
 *
 * All methods are called on the **main thread**.
 */
interface BtaFeedListener {

    /**
     * Called when an editorial article recommendation is clicked.
     *
     * @param payload Details about the clicked article.
     * @return `true` if the app has handled the click (SDK will not open any UI).
     *         `false` to let the SDK open the article URL in a fullscreen [BtaWebViewActivity].
     */
    fun onArticleClick(payload: ArticleClickPayload): Boolean = false

    /**
     * Called when an ad or native ad is clicked.
     *
     * @param payload Details about the clicked ad.
     * @return `true` if the app has handled the click (SDK will not open any UI).
     *         `false` to let the SDK open the ad URL in a fullscreen [BtaWebViewActivity].
     */
    fun onAdClick(payload: AdClickPayload): Boolean = false

    /**
     * Called when the BTA feed has successfully initialised and the JS has started.
     * Note: recommendations may still be loading at this point.
     */
    fun onFeedLoaded() {}

    /**
     * Called when the feed fails to load (e.g., network error, JS error).
     *
     * @param error Human-readable error description.
     */
    fun onFeedError(error: String) {}
}
