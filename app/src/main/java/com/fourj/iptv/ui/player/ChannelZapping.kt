package com.fourj.iptv.ui.player

import com.fourj.iptv.domain.model.LiveChannel

/**
 * The channel `delta` places away from [current] in [channels], wrapping at both ends.
 *
 * Wrapping is the point: on a set-top box, zapping past the last channel returns you to the first,
 * so a user can hold down without ever reaching a dead end. Returns null when there is nothing to
 * zap to, or when [current] is not in the list.
 */
internal fun zapped(channels: List<LiveChannel>, current: LiveChannel, delta: Int): LiveChannel? {
    if (channels.isEmpty()) return null
    val index = channels.indexOfFirst { it.streamId == current.streamId }
    if (index < 0) return null
    val next = (index + delta).mod(channels.size)
    return channels[next]
}
