package org.audienzz.bta.sdk.analytics

internal data class BtaEvent(
    val type: BtaEventType,
    val publisherId: String?,
    val btaFeedId: String,
    val visitorId: String,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Position of the item in the feed. Only set for item-level events. */
    val index: Int? = null,
)
