package com.fourj.iptv.ui.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import java.util.Locale

/**
 * Whether this device can turn speech into text.
 *
 * Asking is not the same as assuming, and the difference is the whole reason this function exists.
 * Android TV is not a uniform platform: some images ship a recogniser, some do not, and the app has
 * no way to install one. A voice button that is always shown is a button that does nothing on a
 * device without a recogniser, which is worse than no button at all - a control that fails silently
 * reads as a broken app rather than as a missing feature.
 *
 * Must be `remember`ed on the context, and the context must be the current one: the answer can
 * change if a recogniser package is installed or removed while the app is running, and the caller
 * only wants to rebuild the button, not re-run a package query on every recomposition.
 */
@Composable
fun rememberVoiceSearchAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
}

/**
 * Speak a search query.
 *
 * **The recogniser does the recording, not this app.** [RecognizerIntent] launches whatever
 * recogniser the device provides and takes the text back as an activity result. The alternative -
 * `AudioRecord` into a bundled speech model - would mean shipping a model, holding
 * `RECORD_AUDIO`, and reimplementing recognition, to produce a worse result than the one already
 * installed and already trained on the viewer's accent. Letting the platform own it also means this
 * app never sees a microphone permission prompt, because it never touches the microphone.
 *
 * **The button's own focus handling is the caller's business.** This deliberately does not grab
 * focus on appearing: whether the microphone or the keyboard should hold focus when search opens is
 * a judgement about how people use the app, and it belongs next to the rest of the search screen's
 * layout rather than hidden in here.
 */
@Composable
fun VoiceSearchButton(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        spokenQuery(result.resultCode, result.data)?.let(onResult)
    }

    Button(
        onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                // Free form, not web search. This catalogue is searched by title, and the web-search
                // model is tuned to answer questions, which biases it towards long natural phrases
                // and away from the two or three words a viewer actually says.
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Partial results are for showing a live transcript in the field while someone is
                // still talking. The field already has the finished text a moment later, and a
                // half-transcribed title re-running the search on every syllable would be worse
                // than nothing.
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel, film or series")
            }
            try {
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                // The recogniser was uninstalled between the availability check and the press.
                // Deliberately silent: the button is only ever rendered when the check passed, so
                // reaching here means the device changed under us, and there is no useful thing to
                // say to someone holding a remote.
            }
        },
        // Applied, not merely accepted. An optional FocusRequester that is taken as a parameter and
        // then quietly dropped compiles, runs, and leaves the caller convinced it asked for initial
        // focus on this control - which is how the search screen spent a while opening with the
        // keyboard up and the caret in the field while this button sat next to it, unfocused.
        modifier = modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    ) {
        Text("Speak")
    }
}

/**
 * The query a speech recogniser handed back, or null if it handed back nothing usable.
 *
 * Split out and pure so it can be tested, because this is the one part of voice search that cannot
 * be exercised on a device without a microphone - and a television emulator has none. Everything
 * around it is observable: the button appears, it takes focus, it launches the right intent, and
 * cancelling comes back clean. Whether the recognised words actually reach the search box is the
 * part that would otherwise ship unverified.
 *
 * Null means "change nothing", and that is the correct answer for every failure case rather than
 * an empty string. An empty query would clear the box, so someone who opened the microphone by
 * accident and cancelled would lose what they had already typed - a small thing, and exactly the
 * kind that is only noticed once it has happened.
 */
internal fun spokenQuery(resultCode: Int, data: Intent?): String? {
    // A cancel is not an error. Backing out of the microphone is a normal thing to do, and it must
    // leave the query exactly as it was.
    if (resultCode != Activity.RESULT_OK) return null

    val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        ?.firstOrNull()
        ?.trim()
    return spoken?.takeIf { it.isNotEmpty() }
}

