package com.fourj.iptv.ui.player

/**
 * Strips the account credentials out of a stream URL so it can be logged.
 *
 * An Xtream stream URL carries the panel's username and password as path segments:
 * `/live/alice/s3cret/42.ts`. That is the panel's own convention and there is no way to request a
 * stream without them, so every "which URL failed?" log line was writing the viewer's password in
 * cleartext.
 *
 * That matters more than it might look. Logcat is readable by the viewer, is attached to bug reports
 * by habit, and on older Android any installed app holding `READ_LOGS` can read it too. A password
 * is not something to hand out as a diagnostic.
 *
 * Only the segment positions are dropped. The shape of the URL - host, content type, stream id - is
 * exactly what makes a log line useful for diagnosis, and none of it is secret.
 *
 * Deliberately a shape match rather than a search for "anything that looks like a password": the
 * panel's path layout is known, so matching it is simpler and easier to reason about. Anything that
 * does not fit the shape is left alone rather than mangled, because a log line that quietly reads
 * `<redacted>` teaches you nothing - and a mangled one can hide the very address you were looking
 * for.
 */
internal fun redactCredentials(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url

    val pathStart = url.indexOf('/', schemeEnd + 3)
    if (pathStart < 0) return url

    val segments = url.substring(pathStart + 1).split('/')

    // The convention is <type>/<user>/<pass>/<id>, so the type marker is the *first* path segment
    // and the two after it are the secrets. Matching on that shape rather than on "segment N is
    // called live" matters: <type> is at index 0, and treating the marker as the position to redact
    // from shifts everything along and leaves the password in the log.
    if (segments.size < 4 || segments[0] !in CREDENTIAL_PATH_SEGMENTS) return url

    return url.substring(0, pathStart + 1) +
        segments[0] + "/" + USER_PLACEHOLDER + "/" + PASS_PLACEHOLDER + "/" +
        segments.drop(3).joinToString("/")
}

private const val USER_PLACEHOLDER = "<user>"
private const val PASS_PLACEHOLDER = "<pass>"

private val CREDENTIAL_PATH_SEGMENTS = setOf("live", "movie", "series")
