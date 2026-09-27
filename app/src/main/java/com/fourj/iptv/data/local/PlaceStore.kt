package com.fourj.iptv.data.local

import android.content.Context
import com.fourj.iptv.domain.model.Place
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredPlace(
    val city: String,
    val region: String?,
    val country: String?,
    val countryCode: String?,
    val fetchedAtMillis: Long,
)

/**
 * Remembers the last successful city lookup, and how old it is.
 *
 * **Plain SharedPreferences, deliberately, and not encrypted.** Everything else this app persists
 * that way is a credential and is encrypted, so the reflex is to assume this needs the same. It does
 * not: a city is not a secret, it is the least sensitive thing the app stores, and
 * [CredentialStore] sets out the reasoning that makes the Keystore the right tool for a password and
 * the wrong one for everything else — a non-exportable key means the value cannot be read by
 * anything that can read the preferences file, which is no advantage for a field whose entire
 * content is "Kansas City".
 *
 * **The age is stored to be shown, not to expire anything.** The lookup runs on every launch, so
 * there is no cache window to enforce — but a request can fail, and when it does the last good
 * answer stays on screen. The age is what tells the viewer that the city they are reading is from
 * last week rather than from this launch, which is the difference between an honest approximate
 * location and a stale one presented as current.
 */
class PlaceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** The last known place, however old. Null if nothing has ever been stored. */
    fun load(): Stored? {
        val encoded = prefs.getString(KEY_DATA, null) ?: return null
        return runCatching {
            val stored = json.decodeFromString(StoredPlace.serializer(), encoded)
            // A stored record with no city cannot be rendered, so it is treated as no record at all
            // rather than being carried around as a Place with nothing in it.
            val city = stored.city.trim()
            if (city.isEmpty()) return@runCatching null
            Stored(
                place = Place(city, stored.region, stored.country, stored.countryCode),
                fetchedAtMillis = stored.fetchedAtMillis,
            )
        }.getOrNull()
    }

    fun save(place: Place, atMillis: Long) {
        val payload = json.encodeToString(
            StoredPlace.serializer(),
            StoredPlace(place.city, place.region, place.country, place.countryCode, atMillis),
        )
        prefs.edit().putString(KEY_DATA, payload).apply()
    }

    fun clear() = prefs.edit().remove(KEY_DATA).apply()

    private companion object {
        const val PREFS_NAME = "fourj_place"
        const val KEY_DATA = "place"
    }
}

/** A cached place and when it was fetched. */
data class Stored(val place: Place, val fetchedAtMillis: Long)

/**
 * Whole days since a lookup, for showing on screen. Null when nothing is cached.
 *
 * Clamped at zero: timezone corrections and NTP steps on a cheap television do move the clock, and
 * a record stamped in what is now the future should read as brand new rather than as "-1 days ago".
 */
fun Stored?.ageInDays(nowMillis: Long): Long? =
    this?.let { ((nowMillis - it.fetchedAtMillis).coerceAtLeast(0)) / DAY_IN_MILLIS }

private const val DAY_IN_MILLIS = 24L * 60 * 60 * 1000
