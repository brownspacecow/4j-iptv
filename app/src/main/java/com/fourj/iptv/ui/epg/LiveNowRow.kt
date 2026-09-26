package com.fourj.iptv.ui.epg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fourj.iptv.domain.model.LiveChannel
import com.fourj.iptv.ui.live.NowNext

/**
 * "On now" across the visible channels.
 *
 * This is the row that answers "what is worth watching?" without the viewer having to browse
 * anything. It is fed from the same cache as the channel list, so it costs no extra requests.
 */
@Composable
fun LiveNowRow(
    channels: List<LiveChannel>,
    nowNextByStream: Map<Int, NowNext>,
    nowSeconds: Long,
    onSelect: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only channels we actually have a current programme for; a row of empty cards is worse than
    // no row at all.
    val onNow = channels.filter { nowNextByStream.containsKey(it.streamId) }
    if (onNow.isEmpty()) return

    Column(modifier = modifier) {
        Text(
            text = "On now",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(count = onNow.size, key = { onNow[it].streamId }) { index ->
                val channel = onNow[index]
                val nowNext = nowNextByStream.getValue(channel.streamId)
                Card(
                    onClick = { onSelect(channel) },
                    modifier = Modifier.width(260.dp),
                    scale = CardDefaults.scale(focusedScale = 1.05f),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = nowNext.current.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${nowNext.endsInMinutes(nowSeconds)} min left",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
