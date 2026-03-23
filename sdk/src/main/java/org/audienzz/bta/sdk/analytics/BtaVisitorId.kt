package org.audienzz.bta.sdk.analytics

import android.content.Context
import java.util.UUID

internal object BtaVisitorId {

    private const val PREF_NAME = "org.audienzz.bta.sdk.prefs"
    private const val KEY_VISITOR_ID = "visitor_id"

    /**
     * Returns the persisted visitor ID, creating and storing a new UUID if none exists.
     * The visitor ID is stable across app sessions.
     */
    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VISITOR_ID, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(KEY_VISITOR_ID, id).apply()
        }
    }
}
