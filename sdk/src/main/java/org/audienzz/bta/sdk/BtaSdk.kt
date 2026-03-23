package org.audienzz.bta.sdk

import android.content.Context
import org.audienzz.bta.sdk.analytics.BtaEventTracker

/**
 * SDK-wide initialisation entry point. Call [init] once, early in your application lifecycle
 * (e.g. `Application.onCreate()`), before any [BtaFeedView] is created.
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         BtaSdk.init(context = this, publisherId = "your-publisher-id")
 *     }
 * }
 * ```
 */
object BtaSdk {

    /** Publisher identifier assigned by Audienzz. Set via [init]. */
    internal var publisherId: String? = null
        private set

    /**
     * Initialise the BTA SDK.
     *
     * @param context     Application context — used to initialise the visitor ID store.
     * @param publisherId Publisher identifier provided by Audienzz.
     */
    fun init(context: Context, publisherId: String) {
        this.publisherId = publisherId
        BtaEventTracker.init(context.applicationContext)
    }
}
