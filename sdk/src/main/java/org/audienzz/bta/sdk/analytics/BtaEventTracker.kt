package org.audienzz.bta.sdk.analytics

import android.content.Context
import android.util.Log
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

    // ── Public ─────────────────────────────────────────────────────────────────

    /** Must be called before the first [track] to initialise the visitor ID. */
    fun init(context: Context) {
        if (visitorId == null) {
            visitorId = BtaVisitorId.getOrCreate(context.applicationContext)
        }
    }

    /**
     * Enqueue an event. Automatically triggers a debounced batch send.
     * The event is dropped with a warning if [init] has not been called yet.
     */
    fun track(event: BtaEvent) {
        if (visitorId == null) {
            Log.w(TAG, "BtaEventTracker not initialised — call BtaSdk.init() first. Dropping event: ${event.type.typeName}")
            return
        }
        // Inject the visitor ID resolved at init time.
        val enriched = event.copy(visitorId = visitorId!!)
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
        val batch = ArrayList(queue)
        queue.removeAll(batch.toSet())
        Log.d(TAG, "Sending batch of ${batch.size} event(s)")
        val success = sender.sendBatch(batch)
        if (!success) {
            Log.w(TAG, "Batch send failed — ${batch.size} event(s) lost")
        }
    }
}
