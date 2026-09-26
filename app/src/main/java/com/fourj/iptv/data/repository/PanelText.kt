package com.fourj.iptv.data.repository

import java.util.Base64

/**
 * Decode a panel-supplied string if - and only if - it is Base64-encoded text.
 *
 * **Why this is needed.** Some Xtream panels return EPG titles, and occasionally descriptions,
 * Base64-encoded. Observed against a real provider: a listing whose title arrived as
 * `Q05OIE5ld3M=` and which is plainly "CNN News". Displayed raw it is unreadable, so the feature
 * looks broken even when the data is good.
 *
 * **Why this is not done naively.** A plain `runCatching { decode(it) }` is not safe, because the
 * overwhelming majority of titles are ordinary text that happens to be legal Base64 - "News" is
 * valid Base64 and decodes to garbage bytes. Silently replacing readable text with mojibake would
 * be far worse than leaving a base64 title encoded.
 *
 * So the result is only accepted when it decodes cleanly *and* the decoded form is more plausible
 * as a programme title than the encoded form:
 *  - the input must be a syntactically valid Base64 block
 *  - it must decode as valid UTF-8 with no replacement characters
 *  - the decoded text must be printable and must contain real letters
 *  - the decoded text must actually be shorter than the input - Base64 always inflates by about a
 *    third, so anything else means we misidentified it
 *  - the decoded text must contain a space or be a plausible single word, since a run of letters
 *    with no spaces is usually an accident of decoding rather than a title
 */
internal fun decodePanelText(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length < MIN_BASE64_LENGTH) return value
    if (!trimmed.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) return value
    // Padding is only ever at the end; '=' anywhere else means this is not base64 at all.
    if (trimmed.indexOf('=') in 0 until trimmed.length - MAX_PADDING) return value

    // This provider emits base64 *without* padding - a 13-character title for "Q2V2..." was
    // observed still base64 after decoding, because 13 is not a multiple of 4. Re-pad rather
    // than reject, or half the panel's titles stay encoded.
    val padded = trimmed.padEnd(trimmed.length + (4 - trimmed.length % 4) % 4, '=')

    val decoded = runCatching {
        String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }.getOrNull() ?: return value

    // A replacement character means the bytes were not valid UTF-8; something else decoded them.
    if (decoded.contains('�')) return value
    if (decoded.isBlank()) return value
    if (decoded.length >= trimmed.length) return value
    if (!decoded.all { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }) return value
    if (!decoded.any { it.isLetter() }) return value
    // Ordinary words decode to letter soup; a real title has word structure.
    if (!decoded.contains(' ') && decoded.length < MIN_DECODED_LENGTH) return value

    return decoded
}

private const val MIN_BASE64_LENGTH = 8

/** At most two '=' may appear, and only at the end. */
private const val MAX_PADDING = 2

/** Below this, a space-free decoded string is not credible as a title. */
private const val MIN_DECODED_LENGTH = 12
