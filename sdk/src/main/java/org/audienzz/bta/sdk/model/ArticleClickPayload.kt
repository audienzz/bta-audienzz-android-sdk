package org.audienzz.bta.sdk.model

import org.json.JSONObject

/**
 * Payload delivered when an article recommendation is clicked in the BTA feed.
 *
 * @property btaFeedId The feed ID that triggered the event.
 * @property index Zero-based position of the item in the feed.
 * @property article Raw JSON object from the BTA feed describing the article.
 * @property url Convenience accessor for the article URL.
 * @property title Convenience accessor for the article title.
 */
class ArticleClickPayload internal constructor(private val json: JSONObject) {

    val btaFeedId: String = json.optString("btaFeedId")
    val index: Int = json.optInt("index", -1)
    val article: JSONObject = json.optJSONObject("article") ?: JSONObject()

    /** Article destination URL. Empty string if not provided. */
    val url: String = article.optString("url")

    /** Article title. Empty string if not provided. */
    val title: String = article.optString("title")

    override fun toString(): String =
        "ArticleClickPayload(btaFeedId=$btaFeedId, index=$index, url=$url, title=$title)"

    internal companion object {
        fun fromJson(json: String): ArticleClickPayload = ArticleClickPayload(JSONObject(json))
    }
}
