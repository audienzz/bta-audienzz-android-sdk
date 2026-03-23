package org.audienzz.bta.sdk.analytics

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal class BtaEventSender {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Serialises [events] as a CloudEvents 1.0 batch and POSTs to the ingestion endpoint.
     * @return `true` if the server accepted the batch (HTTP 2xx).
     */
    fun sendBatch(events: List<BtaEvent>): Boolean {
        if (events.isEmpty()) return true
        val body = buildBatchJson(events)
        return try {
            post(ENDPOINT, body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send event batch: ${e.message}")
            false
        }
    }

    // ── JSON serialisation ─────────────────────────────────────────────────────

    private fun buildBatchJson(events: List<BtaEvent>): String {
        val array = JSONArray()
        events.forEach { event -> array.put(buildEventJson(event)) }
        return array.toString()
    }

    private fun buildEventJson(event: BtaEvent): JSONObject {
        val data = JSONObject().apply {
            put("publisherId", event.publisherId ?: JSONObject.NULL)
            put("btaFeedId", event.btaFeedId)
            put("visitorId", event.visitorId)
            put("sessionId", event.sessionId)
            put("timestamp", event.timestamp)
            event.index?.let { put("index", it) }
        }

        return JSONObject().apply {
            put("specversion", "1.0")
            put("source", "mobile-sdk")
            put("datacontenttype", "application/json")
            put("type", event.type.typeName)
            put("id", UUID.randomUUID().toString())
            put("time", isoFormat.format(Date(event.timestamp)))
            put("data", data)
        }
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private fun post(urlString: String, body: String): Boolean {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/cloudevents-batch+json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val success = responseCode in 200..299
            if (!success) {
                Log.w(TAG, "Event batch rejected: HTTP $responseCode")
            }
            success
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "BtaEventSender"
        private const val ENDPOINT =
            "https://dev-api.adnz.co/api/ws-event-ingester/submit/batch"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}
