package com.fourj.iptv.data.remote

import com.fourj.iptv.domain.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
private data class IpWhoResponse(
    val success: Boolean = false,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val message: String? = null,
)

/**
 * Asks [IP_WHO_IS] where this device's public IP address is.
 *
 * **This is the one place the app talks to a server it was not configured with**, and it is worth
 * being blunt about what that costs: the television's public IP address goes to a third party, and
 * the answer identifies the household to roughly city accuracy. There is no way to get a city from
 * an IP without sending the IP somewhere, and the alternatives are worse — `Geocoder` needs a
 * runtime location permission and a GPS fix, which a television in a living room does not have, and
 * inferring it from the provider's EPG would be a guess dressed up as a lookup.
 *
 * The service is chosen for what it does *not* need: `ipwho.is` answers over HTTPS with no API key
 * and no account, so there is nothing to configure, nothing to leak, and no key sitting in the APK.
 * Every other candidate either wanted a key (which then ships in the binary and can be lifted out
 * of it) or served its free tier over plain HTTP.
 *
 * One request, cached by [com.fourj.iptv.data.local.PlaceStore]. Nothing here is on any path the
 * viewer is waiting on: a failure returns null and the app carries on as if the feature were off.
 */
class PlaceLookup(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * The city, or null if it could not be determined.
     *
     * Null covers every failure and they are deliberately not distinguished on screen: no network,
     * a service outage, a private or reserved address the service will not geolocate, or a malformed
     * response. A television is often on a network the service cannot place, and an error message
     * about a cosmetic detail would be worse than showing nothing.
     */
    suspend fun city(): Place? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(IP_WHO_IS)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let(::parsePlace)
            }
        }.getOrNull()
    }

    private companion object {
        /**
         * Over HTTPS with no trailing path: the bare host answers with the caller's own address,
         * which is the whole point and needs no arguments.
         */
        const val IP_WHO_IS = "https://ipwho.is/"

        /**
         * Its own client rather than the provider's, for two reasons. The provider's client is
         * rebuilt per profile and carries that profile's credentials and address, and a cosmetic
         * lookup has no business sharing a connection pool with a panel that is rate-limiting us.
         * The timeouts are also short on purpose - if the service has not answered in a few seconds
         * the answer is not worth waiting for.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/**
 * Turns a response body into a place, or null if it is not one.
 *
 * Extracted from [PlaceLookup.city] and made internal so it can be tested directly. The request
 * itself is not worth testing - it is three lines of OkHttp - but *this* is where every interesting
 * decision lives: a well-formed body can still be a refusal, and a partial answer still has to
 * produce a line worth reading. None of it can be reached on a device, because the emulator's
 * address is the host's and always resolves.
 *
 * `success: false` is how this service reports a reserved or private range it will not geolocate,
 * and it still returns a well-formed body, so the flag is the only thing separating a real answer
 * from a refusal. Checking for a city instead would not do: a refusal carries no city, but a
 * *success* with a blank city would slip through just the same.
 */
internal fun parsePlace(body: String): Place? = runCatching {
    val parsed = XtreamNetwork.json.decodeFromString(IpWhoResponse.serializer(), body)
    if (!parsed.success) return null
    parsed.toPlace()
}.getOrNull()

private fun IpWhoResponse.toPlace(): Place? {
    val cityName = city?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // Region is a state or county and frequently the same as the city, so repeating it is noise.
    val regionName = region?.trim()?.takeIf { it.isNotEmpty() && !it.equals(cityName, ignoreCase = true) }
    val countryName = country?.trim()?.takeIf { it.isNotEmpty() }
    return Place(
        city = cityName,
        region = regionName,
        country = countryName,
        countryCode = countryCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
    )
}
