package com.fourj.iptv.data.remote

import android.util.Log
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

private const val TAG = "4J"

/**
 * Parse a catalogue response, keeping whatever the panel managed to send.
 *
 * Parses normally when the body is complete. When it is not, closes the array at its last complete
 * element and parses that, logging how much was salvaged and how much never arrived.
 *
 * Falls back to failing the call if there is nothing to salvage, so a genuinely unreadable response
 * still surfaces as an error rather than as an empty category.
 */
internal fun <T> readCatalogueLeniently(
    body: String,
    expectedBytes: Long?,
    strategy: DeserializationStrategy<T>,
    json: Json,
    label: String,
): Result<T> {
    return runCatchingCancellable { json.decodeFromString(strategy, body) }
        .recoverCatching { failure ->
            val closed = TruncatedJson.closeArray(body)
                ?: throw failure
            val salvaged = json.decodeFromString(strategy, closed)
            val kept = TruncatedJson.countCompleteElements(closed)
            Log.w(
                TAG,
                "$label: response was cut off, kept $kept complete entr" +
                    (if (kept == 1) "y" else "ies") +
                    TruncatedJson.salvagedSuffix(body.length, expectedBytes),
            )
            salvaged
        }
}

/**
 * Salvaging a JSON array the provider cut off.
 *
 * This panel truncates large catalogue responses constantly - a film shelf is about 20 MB and the
 * body is cut at roughly 2.2 MB, which kotlinx.serialization rejects with "expected end of array,
 * but had EOF". Treating that as a failed request throws away every title that *did* arrive, and
 * with it any chance of searching them: a run on this provider left 99 of 269 shelves unreadable for
 * no better reason than that the last one was incomplete.
 *
 * A truncated array is still nearly all there. `[{"a":1},{"b":2},{"c":3` contains two complete
 * records and half a third, so closing the array at the last complete record recovers the shelf.
 * What is lost is the tail - the titles that were in flight when the connection dropped - and that
 * is recorded so the gap is visible rather than silent.
 */
internal object TruncatedJson {

    /**
     * Close a cut-off array at its last complete element, or null if there is nothing to salvage.
     *
     * Returns null rather than a guess when the body is not an array, or when no element completed
     * before the cut. Callers should then fail the request as before: an empty salvage would look
     * like a genuinely empty category, which is a very different thing.
     */
    fun closeArray(body: String): String? {
        val start = body.indexOf('[')
        if (start < 0) return null

        var index = start + 1
        var inString = false
        var escaped = false
        var depth = 0
        var lastElementEnd = -1

        while (index < body.length) {
            val char = body[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }

                char == '"' -> inString = true

                char == '{' || char == '[' -> {
                    depth++
                }

                char == '}' || char == ']' -> {
                    if (depth == 0) {
                        // A clean close at depth zero: the array was not truncated at all.
                        return null
                    }
                    depth--
                    // Depth returns to zero as we step out of a top-level element, so this is the
                    // boundary of a record that finished.
                    if (depth == 0) lastElementEnd = index + 1
                }
            }
            index++
        }

        if (lastElementEnd < 0) return null
        return body.substring(0, lastElementEnd) + "]"
    }

    /**
     * How many complete elements the body holds, for reporting what a salvage cost.
     *
     * Counts only at the top level, so braces inside a title - "Extreme {Sports}" - do not inflate
     * the number.
     */
    fun countCompleteElements(body: String): Int {
        var count = 0
        var index = body.indexOf('[') + 1
        var inString = false
        var escaped = false
        var depth = 0
        var sawElement = false

        while (index < body.length) {
            val char = body[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }

                char == '"' -> inString = true
                char == '{' || char == '[' -> {
                    if (depth == 0) sawElement = true
                    depth++
                }

                char == '}' || char == ']' -> {
                    if (depth == 0) return count
                    depth--
                    if (depth == 0 && sawElement) {
                        count++
                        sawElement = false
                    }
                }
            }
            index++
        }
        return count
    }

    /**
     * The last point the body reached, for saying how much was lost.
     *
     * The panel's own `Content-Length` is the honest comparison: it reports what it intended to send,
     * which is exactly the number that shows how much never arrived.
     */
    fun salvagedSuffix(receivedBytes: Int, expectedBytes: Long?): String {
        if (expectedBytes == null || expectedBytes <= 0) return ""
        val lost = (expectedBytes - receivedBytes).coerceAtLeast(0)
        if (lost <= 0) return ""
        return " (panel announced ${expectedBytes / 1024} KB, ${lost / 1024} KB never arrived)"
    }
}
