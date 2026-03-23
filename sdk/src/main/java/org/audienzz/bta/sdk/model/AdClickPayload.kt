package org.audienzz.bta.sdk.model

import org.json.JSONObject

/**
 * Payload delivered when an ad or native ad is clicked in the BTA feed.
 * This covers both display ads (onAdClick) and native ads (onNativeAdClick) from the feed JS.
 *
 * @property btaFeedId The feed ID that triggered the event.
 * @property index Zero-based position of the item in the feed.
 * @property adUnit Raw JSON object from the BTA feed describing the ad unit.
 * @property url Convenience accessor for the ad destination URL.
 */
class AdClickPayload internal constructor(private val json: JSONObject) {

    val btaFeedId: String = json.optString("btaFeedId")
    val index: Int = json.optInt("index", -1)
    val adUnit: JSONObject = json.optJSONObject("adUnit") ?: JSONObject()

    /**
     * Ad destination URL.
     *
     * Resolution order (first non-blank wins):
     * 1. Top-level `url` — extracted in the JS bridge from all common adUnit field names
     *    (`clickUrl`, `url`, `destinationUrl`, `targetUrl`, `href`).
     * 2. `adUnit.url` — direct fallback for any field not covered above.
     *
     * Empty string if the JS SDK does not expose a URL at all.
     */
    val url: String = json.optString("url").ifBlank { adUnit.optString("url") }

    override fun toString(): String =
        "AdClickPayload(btaFeedId=$btaFeedId, index=$index, url=$url)"

    internal companion object {
        fun fromJson(json: String): AdClickPayload = AdClickPayload(JSONObject(json))
    }
}
