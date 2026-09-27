package com.fourj.iptv.ui.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers reading a query back out of a speech recogniser's result.
 *
 * This exists because voice search cannot be tested on the emulator. The device used for
 * development reports no `android.hardware.microphone` feature, so a recogniser launches, waits for
 * audio that will never arrive, and has to be cancelled. Everything around that path is observed -
 * the button appears, it takes focus, the right intent is sent, cancelling returns cleanly - but the
 * recognised words never make it into the field, and the parsing was otherwise untested.
 *
 * The cases that matter are the ones where the answer is null. Null means "leave the query alone",
 * so every one of them is a situation where getting it wrong destroys something the viewer had
 * already typed.
 * already typed.
 *
 * **Robolectric, not a plain JVM test, and that is load-bearing.** The module sets
 * `isReturnDefaultValues = true`, so on a plain JVM every android.jar method is a stub that returns
 * null and writes nothing. A first attempt at this was a plain JVM test and five of the seven cases
 * passed - including every case asserting null. They passed because the stub returned null, not
 * because the logic was right, which is the worst possible behaviour for a test whose subject is
 * "returns null when there is nothing to return". Under Robolectric a real [Intent] stores and
 * returns the extra, so a null now means the function decided null.
 */
@RunWith(RobolectricTestRunner::class)
class SpokenQueryTest {

    // arrayListOf, not listOf: putStringArrayListExtra takes a java.util.ArrayList and a Kotlin
    // MutableList is not one, so the obvious spelling does not compile.
    private fun result(vararg results: String?): Intent =
        Intent().apply {
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS,
                arrayListOf<String>().apply { addAll(results.filterNotNull()) },
            )
        }

    @Test
    fun `takes the first result`() {
        // EXTRA_MAX_RESULTS is 1, but a recogniser is free to return more, and the first is the
        // one the platform documents as the best.
        val intent = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("sky sports cricket", "sky sports"),
        )
        assertEquals("sky sports cricket", spokenQuery(Activity.RESULT_OK, intent))
    }

    @Test
    fun `trims the recognised text`() {
        // Recognisers routinely hand back padding, and a leading space would make the search miss
        // every title rather than merely look untidy.
        assertEquals("sky news", spokenQuery(Activity.RESULT_OK, result("  sky news \n")))
    }

    @Test
    fun `cancel returns null even when the intent carries results`() {
        // The guard has to be on the result code, not on the presence of data. An intent is not
        // proof of a result, and trusting it here would search for a phrase the viewer rejected.
        assertNull(spokenQuery(Activity.RESULT_CANCELED, result("sky news")))
    }

    @Test
    fun `no data at all returns null`() {
        assertNull(spokenQuery(Activity.RESULT_OK, null))
    }

    @Test
    fun `missing results extra returns null`() {
        assertNull(spokenQuery(Activity.RESULT_OK, Intent()))
    }

    @Test
    fun `empty results list returns null`() {
        assertNull(spokenQuery(Activity.RESULT_OK, result()))
    }

    @Test
    fun `blank result returns null rather than clearing the query`() {
        // The case that matters most. Someone opens the microphone by accident, says nothing, and
        // backs out; an empty string here would wipe whatever they had already typed.
        assertNull(spokenQuery(Activity.RESULT_OK, result("   ")))
        assertNull(spokenQuery(Activity.RESULT_OK, result("")))
    }
}
