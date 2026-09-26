package com.fourj.iptv.data.remote

import android.util.Log
import kotlinx.coroutines.delay

private const val TAG = "4J"

/**
 * Run [block], retrying a few times before giving up.
 *
 * This exists because of a specific, observed panel behaviour: `get_live_streams` on a large
 * category intermittently returns a **truncated JSON body** - the connection is closed partway
 * through the array, and deserialisation fails with "expected end of array, but had EOF". The
 * categories call is small and never does this; a big channel list does, often enough that a
 * single attempt is not good enough.
 *
 * Every failure mode here is transient by nature - a dropped connection, a CDN cutting a large
 * response, a panel busy generating JSON - so retrying is the correct response rather than
 * treating it as fatal.
 *
 * [isWorthRetrying] lets genuinely permanent failures (bad credentials) fail fast instead of
 * burning three attempts.
 */
internal suspend fun <T> retrying(
    attempts: Int = DEFAULT_ATTEMPTS,
    initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    label: String = "request",
    isWorthRetrying: (Throwable) -> Boolean = { it.isTransientNetworkFailure() },
    block: suspend () -> T,
): Result<T> {
    var lastFailure: Throwable? = null

    for (attempt in 1..attempts) {
        val outcome = runCatchingCancellable { block() }
        val value = outcome.getOrNull()
        if (outcome.isSuccess && value != null) {
            if (attempt > 1) Log.i(TAG, "$label succeeded on attempt $attempt")
            return Result.success(value)
        }

        val failure = outcome.exceptionOrNull() ?: lastFailure ?: IllegalStateException("no result")
        lastFailure = failure

        val retryable = isWorthRetrying(failure)
        Log.w(
            TAG,
            "$label attempt $attempt/$attempts failed (retryable=$retryable): ${failure.message}",
        )

        if (!retryable || attempt == attempts) break
        // Linear backoff. Deliberately simple: the failures are network-shaped and there is no
        // fleet of clients to be polite towards, so an exponential curve buys nothing here.
        delay(initialDelayMillis * attempt)
    }

    return Result.failure(lastFailure ?: IllegalStateException("$label produced no result"))
}

/**
 * True for failures that a later attempt might not hit.
 *
 * A rejected password or a 404 will fail identically forever, so those are excluded.
 */
internal fun Throwable.isTransientNetworkFailure(): Boolean = when (this) {
    is retrofit2.HttpException -> code() in 500..599 || code() == 429 || code() == 408
    is java.io.IOException -> true
    is kotlinx.serialization.SerializationException -> true
    else -> false
}

/**
 * `runCatching` that lets cancellation through.
 *
 * `kotlin.runCatching` catches `CancellationException` and folds it into a failed `Result`, which
 * is wrong: cancellation is control flow, not an error. Swallowing it means a coroutine that was
 * meant to stop carries on, and it surfaces in the log as a spurious failure every time a
 * `flatMapLatest` supersedes an in-flight request - which is ordinary, not something to report.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

private const val DEFAULT_ATTEMPTS = 3
private const val DEFAULT_INITIAL_DELAY_MILLIS = 500L
