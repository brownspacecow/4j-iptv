package com.fourj.iptv.ui.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fourj.iptv.ui.live.NowNext

/**
 * "On now / next" for a channel row.
 *
 * Sized for a television: the programme title is the point, so it gets the larger style and the
 * channel name steps back. A time range is shown alongside because "what is on, and until when"
 * is the question a viewer actually has.
 */
@Composable
fun NowNextRow(
    channelName: String,
    nowNext: NowNext?,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
) {
    if (nowNext == null) {
        Text(
            text = channelName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val current = nowNext.current
    Column(modifier = modifier) {
        Text(
            text = current.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                append(channelName)
                append("  ·  ")
                append("${timeLabel(current.start)}–${timeLabel(current.end)}")
                append("  ·  ")
                append("${nowNext.endsInMinutes(nowSeconds)} min left")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        ProgressBar(
            progress = nowNext.progress(nowSeconds),
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .height(3.dp),
        )
        nowNext.next?.let { next ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Next: ${next.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A thin elapsed-time bar.
 *
 * Drawn rather than composed from Material components: the stock progress indicators animate and
 * carry their own sizing, and this needs to be a quiet 3dp rule that reads at a distance without
 * drawing attention to itself.
 */
@Composable
private fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** `HH:mm` in the device's own time zone. */
internal fun timeLabel(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "--:--"
    val instant = java.time.Instant.ofEpochSecond(epochSeconds)
    val local = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    return "%02d:%02d".format(local.hour, local.minute)
}
