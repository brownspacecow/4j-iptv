package com.fourj.iptv.data.remote

import com.fourj.iptv.domain.model.ProviderProfile
import java.net.URI
import java.net.URLDecoder

/**
 * Parses the several shapes a user might paste or type for a provider.
 *
 * Providers hand out a "portal" link, usually one of:
 *
 * ```
 * http://host:8080/get.php?username=alice&password=secret
 * http://host:8080/player_api.php?username=alice&password=secret
 * http://host:8080/player_api.php?username=alice&password=secret&action=get_live_streams
 * ```
 *
 * …while some people just have three boxes: host, port, and credentials. [parseServer] covers
 * the address alone; [parseProfile] covers a full link with credentials embedded.
 *
 * Deliberately free of Android dependencies so it can be unit tested directly.
 */
object ProviderAddress {

    /**
     * Normalise a bare address such as `host`, `host:8080` or `https://host` into
     * `scheme://host[:port]`. Returns null if no host can be read out of it.
     *
     * A missing scheme defaults to `http` because a large share of Xtream panels are plain HTTP.
     */
    fun parseServer(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null

        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null

        return buildString {
            append(scheme)
            append("://")
            append(host)
            if (uri.port > 0) {
                append(':')
                append(uri.port)
            }
        }
    }

    /**
     * Pull a complete profile out of a provider link, or null if it is not one. The path is
     * deliberately ignored - only scheme, host, port and the `username`/`password` query
     * parameters matter.
     */
    fun parseProfile(raw: String): ProviderProfile? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null

        val baseUrl = parseServer(trimmed) ?: return null
        val username = queryParameter(uri.rawQuery, "username")
        val password = queryParameter(uri.rawQuery, "password")
        if (username.isNullOrBlank() || password.isNullOrBlank()) return null

        return ProviderProfile(baseUrl = baseUrl, username = username, password = password)
    }

    /** Read one query parameter, tolerating percent-encoding and a leading `?`. */
    fun queryParameter(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        val target = rawQuery.removePrefix("?")
        for (pair in target.split('&')) {
            if (pair.isBlank()) continue
            val index = pair.indexOf('=')
            if (index <= 0) continue
            val key = decode(pair.substring(0, index))
            if (!key.equals(name, ignoreCase = true)) continue
            val value = decode(pair.substring(index + 1))
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}
