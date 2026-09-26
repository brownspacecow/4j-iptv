package com.fourj.iptv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fourj.iptv.ui.theme.LocalUiScale

/**
 * The manual sync, as a screen of its own.
 *
 * Kept separate from search because it is a different job with a different rhythm. Search answers a
 * question in under a second; a sync is a long download the viewer starts deliberately and watches
 * from the edge of the room. Folding the controls into the search box made the one thing that makes
 * search work look like a footnote, and gave nowhere to show what a run had actually achieved.
 *
 * **The panel cannot do better than a best effort**, and this screen says so rather than implying a
 * clean result. It serves no ETag, no Last-Modified and no "changed since", and it truncates large
 * responses - so a sync gets as far as it can and the rest is reported. A progress bar that reached
 * 100% and quietly left a third of the catalogue unreadable would be worse than an honest partial
 * figure.
 */
@Composable
fun SyncScreen(
    state: SearchUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiScale = LocalUiScale.current
    val progress = state.syncProgress
    val coverage = state.coverage

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = uiScale.horizontalMarginDp.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Sync",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(14.dp))

        // The controls come before the numbers, and directly under the title, so the D-pad reaches
        // them on the first press rather than after arrowing through a column of statistics.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.isIndexing) {
                Button(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = onStart) {
                    Text(if ((coverage?.total ?: 0) == 0) "Start sync" else "Sync again")
                }
            }
            Button(onClick = onBack) { Text("Back") }
        }

        Spacer(Modifier.height(18.dp))

        if (state.isIndexing) {
            Text(
                text = if (state.isFetchingShelves) {
                    "Asking your provider what is on your account…"
                } else {
                    "Syncing… ${state.indexedSoFar} titles found"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!state.isFetchingShelves) {
                Spacer(Modifier.height(8.dp))
                ProgressBar(progress.fraction)
            }
            Spacer(Modifier.height(18.dp))
        }

        Text(
            text = statusLine(state, progress),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(16.dp))
        KindRow("Live TV", progress.liveDone, progress.liveTotal, coverage?.liveIndexed)
        Spacer(Modifier.height(8.dp))
        KindRow("Films", progress.movieDone, progress.movieTotal, coverage?.movieIndexed)
        Spacer(Modifier.height(8.dp))
        KindRow("Series", progress.seriesDone, progress.seriesTotal, coverage?.seriesIndexed)

        if (state.skippedShelves > 0) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = "${state.skippedShelves} shelf${if (state.skippedShelves == 1) "" else "s"} " +
                    "could not be read",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Your provider cut those off before they finished sending. Their titles are " +
                    "not in search. Syncing again often gets further, because the panel is less " +
                    "busy.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.indexError?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Your provider does not support searching, so the app searches a copy it keeps " +
                "on this device. Syncing downloads the names of everything on your account. It can " +
                "take a while, it can be stopped, and it picks up where it left off.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One line saying what state the search index is in.
 *
 * A dedicated sentence rather than a number, because "9,019 titles" and "9,019 of 20,000" are very
 * different things to a viewer and only the second one is honest.
 */
private fun statusLine(state: SearchUiState, progress: SyncProgress): String {
    val coverage = state.coverage ?: return "Checking what is searchable…"
    return when {
        state.isIndexing -> "Syncing. You can leave this screen and it will keep going."
        coverage.total == 0 && !state.isIndexing ->
            if (progress.shelvesTotal == 0) {
                "Nothing synced yet. Press Start sync to download your catalogue."
            } else {
                "Nothing synced yet."
            }
        coverage.isComplete && state.skippedShelves == 0 ->
            "Everything is synced. ${coverage.total} titles are searchable."
        coverage.isComplete ->
            "${coverage.total} titles searchable, ${state.skippedShelves} shelf(s) unreadable."
        else -> "${coverage.total} titles searchable so far. " +
            "${coverage.scopesComplete} of ${coverage.scopesTotal} shelves done."
    }
}

@Composable
private fun KindRow(label: String, done: Int, total: Int, indexed: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(110.dp),
        )
        // "shelves done / shelves offered", and the titles found, because those are different
        // numbers and conflating them would make a part-read shelf look fully read.
        Text(
            text = if (total == 0) "no shelves" else "$done of $total shelves",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        indexed?.let {
            Text(
                text = "$it titles",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A determinate bar, or a plain caption when there is no total yet.
 *
 * Renders nothing rather than an empty bar when [fraction] is null: a bar at zero because the
 * denominator is unknown is a claim, and the wrong one.
 */
@Composable
private fun ProgressBar(fraction: Float?) {
    if (fraction == null) {
        Text(
            text = "Working out how much there is to do…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(8.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = "${(clamped * 100).toInt()}%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
