package com.fourj.iptv.data.repository

import com.fourj.iptv.data.local.PlaceStore
import com.fourj.iptv.data.local.Stored
import com.fourj.iptv.data.local.ageInDays
import com.fourj.iptv.data.remote.PlaceLookup
import com.fourj.iptv.domain.model.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the app knows about where it is, and how sure of it. */
data class PlaceState(
    val place: Place?,
    /** Whole days since the lookup, or null when nothing has been looked up. */
    val ageInDays: Long?,
)

/**
 * The viewer's approximate city, looked up from the public IP address on every launch.
 *
 * **Nothing here can fail in a way the viewer sees.** The lookup runs in a background scope the
 * caller cannot cancel, a failure leaves the previous value in place, and the screen shows nothing
 * rather than an error. A cosmetic detail is not worth an error message, and a television that
 * cannot be geolocated — behind a VPN, on a reserved range, or simply offline — is common enough
 * that the failure path is the normal path for some people.
 *
 * **The first launch of a session waits for nothing.** The cached value is published synchronously
 * in the constructor, so the screen has something to draw on its first frame if there is anything to
 * draw. The network call happens afterwards and updates the flow if it succeeds. That ordering is
 * the whole reason a lookup on every launch is affordable: a viewer opening the app never sees an
 * empty corner while a request is in flight, they see the previous answer and then watch it change
 * if it has.
 *
 * **Every launch, deliberately, and it is worth being honest about the cost.** There is no cache
 * window: the public IP address goes to the geolocation service each time the app is opened, not
 * only when the answer would have changed. That is the right trade for a single television on a
 * single unmetered connection, where the alternative — a day-old answer that is wrong because the
 * household moved — is worse than one extra small request. It is the wrong trade for anything
 * metered or shared, and `PlaceStore` keeps the fetched-at stamp precisely so the age can be shown
 * when a request has been failing.
 */
class PlaceRepository(
    private val store: PlaceStore,
    private val lookup: PlaceLookup,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val fetching = Mutex()

    private val _state = MutableStateFlow(readCache())
    val state: StateFlow<PlaceState> = _state.asStateFlow()

    /**
     * Looks the city up, replacing the cached answer if this one succeeds.
     *
     * Returns immediately; the result lands in [state] when it arrives. Safe to call more than once:
     * the lock means the second caller waits for the first request and then finds the work already
     * done rather than sending a second one. A failure is not retried and does not clear what is
     * cached — the next launch will try again.
     */
    fun refresh() {
        scope.launch {
            fetching.withLock {
                val place = lookup.city() ?: return@withLock
                store.save(place, now())
                _state.value = PlaceState(place, 0)
            }
        }
    }

    private fun readCache(): PlaceState {
        val stored: Stored? = store.load()
        return PlaceState(stored?.place, stored.ageInDays(now()))
    }
}
