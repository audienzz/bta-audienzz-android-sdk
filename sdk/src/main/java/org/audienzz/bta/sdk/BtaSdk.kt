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
 *         BtaSdk.init(context = this, companyId = "your-companyId-id")
 *     }
 * }
 * ```
 */
object BtaSdk {

    /** Company identifier assigned by Audienzz. Set via [init]. */
    internal var companyId: String? = null
        private set

    /**
     * Initialise the BTA SDK.
     *
     * @param context     Application context — used to initialise the visitor ID store.
     * @param companyId Company identifier provided by Audienzz.
     */
    fun init(context: Context, companyId: String) {
        this.companyId = companyId
        BtaEventTracker.init(context.applicationContext)
    }
}
