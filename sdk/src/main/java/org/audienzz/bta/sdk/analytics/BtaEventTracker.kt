package org.audienzz.bta.sdk.analytics

import android.content.Context
import android.util.Log
import org.audienzz.bta.sdk.BtaConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Internal analytics tracker. Accumulates events in-memory and sends them
 * in batches after a 2-second debounce window.
 *
 * Thread-safe: [track] can be called from any thread.
 */
internal object BtaEventTracker {

    private const val TAG = "BtaEventTracker"
    private const val DEBOUNCE_MS = 2_000L

    /** Session ID generated once per process lifetime. */
    val sessionId: String = UUID.randomUUID().toString()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = CopyOnWriteArrayList<BtaEvent>()
    private val sender = BtaEventSender()
    private var debounceJob: Job? = null

    private var visitorId: String? = null
    private var appContext: Context? = null

    // ── Public ─────────────────────────────────────────────────────────────────

    /** Must be called before the first [track]. Stores the app context for consent + visitor ID. */
    fun init(context: Context) {
        appContext = context.applicationContext
        // The visitor ID is created lazily on the first allowed track() so that no identifier is
        // persisted before consent is known (see BtaConsent.analyticsAllowed).
    }

    /**
     * Enqueue an event. Automatically triggers a debounced batch send.
     * The event is dropped with a warning if [init] has not been called yet, and silently when
     * the app's IAB TCF consent does not permit analytics (GDPR applies without consent).
     */
    fun track(event: BtaEvent) {
        val context = appContext
        if (context == null) {
            Log.w(TAG, "BtaEventTracker not initialised — call BtaSdk.init() first. Dropping event: ${event.type.typeName}")
            return
        }
        // Honour the app's IAB TCF consent: drop analytics when GDPR applies without consent.
        if (!BtaConsent.analyticsAllowed(context)) {
            Log.d(TAG, "Analytics suppressed (no consent) — dropping event: ${event.type.typeName}")
            return
        }
        // Create/resolve the visitor ID lazily, only once consent allows it.
        val id = visitorId ?: BtaVisitorId.getOrCreate(context).also { visitorId = it }
        val enriched = event.copy(visitorId = id)
        queue.add(enriched)
        Log.d(TAG, "Queued event: ${event.type.typeName} (queue size: ${queue.size})")
        scheduleSend()
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun scheduleSend() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            flush()
        }
    }

    private fun flush() {
        if (queue.isEmpty()) return
        // Re-check in case consent was withdrawn while events were queued.
        val context = appContext
        if (context != null && !BtaConsent.analyticsAllowed(context)) {
            val dropped = queue.size
            queue.clear()
            Log.d(TAG, "Analytics suppressed (no consent) — dropping $dropped queued event(s)")
            return
        }
        val batch = ArrayList(queue)
        queue.removeAll(batch.toSet())
        Log.d(TAG, "Sending batch of ${batch.size} event(s)")
        val success = sender.sendBatch(batch)
        if (!success) {
            Log.w(TAG, "Batch send failed — ${batch.size} event(s) lost")
        }
    }
}
