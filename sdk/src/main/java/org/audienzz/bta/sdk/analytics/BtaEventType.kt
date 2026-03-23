package org.audienzz.bta.sdk.analytics

internal enum class BtaEventType(val typeName: String) {
    /** Fired when BtaFeedView.load() is called. */
    PAGEVIEW("btafeed.pageview"),

    /** Fired when the BtaFeedView widget first enters the Android viewport. */
    VIEWABLE_IMPRESSION("btafeed.viewable_impression"),

    /** Fired when an individual ad item enters the feed viewport (JS callback). */
    AD_IMPRESSION("btafeed.ad_impression"),

    /** Fired when an ad is clicked. */
    AD_CLICK("btafeed.ad_click"),

    /** Fired when an individual article item enters the feed viewport (JS callback). */
    ARTICLE_IMPRESSION("btafeed.article_impression"),

    /** Fired when an article recommendation is clicked. */
    ARTICLE_CLICK("btafeed.article_click"),
}
