package com.fourj.iptv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.fourj.iptv.data.repository.SearchHit
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.ui.common.TvTextField
import com.fourj.iptv.ui.theme.LocalUiScale

/**
 * Search across live TV, films and series.
 *
 * **Why the coverage line is on screen rather than in a settings page.** This provider has no
 * server-side search, so the app can only search what it has downloaded. On a fresh install that is
 * almost nothing, and a search box that returns "no results" for a film the viewer can see in the
 * provider's own app looks broken. Showing what is indexed - and offering to index the rest - is the
 * difference between a tool that is honest about its limits and one that appears faulty.
 *
 * The IME is used for entry rather than an on-screen D-pad keyboard. Worth recording why that is a
 * real trade and not just the easy option: a soft keyboard over the content is unfamiliar on a
 * television, and it competes with focus for the D-pad. In exchange it is the only way to type a
 * twenty-character title comfortably, and a search you can only enter three letters at a time is not
 * worth having. The field intercepts up and down to move focus, so results stay reachable while the
 * keyboard is up.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onStartIndexing: () -> Unit,
    onCancelIndexing: () -> Unit,
    onHitClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiScale = LocalUiScale.current
    val queryFocus = remember { FocusRequester() }

    // Put the caret in the field as the screen appears, so the viewer can type immediately. Without
    // it, opening search leaves focus on the button that opened it, and the first thing anyone
    // tries - typing - does nothing.
    LaunchedEffect(Unit) {
        runCatching { queryFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = uiScale.horizontalMarginDp.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        TvTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = "Search live TV, films and series",
            placeholder = "Type a channel, film or series name",
            focusRequester = queryFocus,
        )

        Spacer(Modifier.height(10.dp))
        IndexControls(
            state = state,
            onStartIndexing = onStartIndexing,
            onCancelIndexing = onCancelIndexing,
        )

        Spacer(Modifier.height(12.dp))

        when {
        state.query.isBlank() -> SearchHint(state)
        state.isEmptyResult -> NoResults(state)
        else -> Results(state = state, onHitClick = onHitClick)
    }
    }
}

/**
 * The indexing action and the coverage line.
 *
 * **The button sits directly under the search field, on the left, rather than at the far right of
 * the status line.** It was there first, and it was unreachable: focus went field -> results ->
 * field, stepping straight over the button, and a tap missed it too. Compose resolves D-pad focus by
 * proximity, and a control pushed to the right edge is a long way from a field that spans the full
 * width - so the one control that makes search work was reachable only with a pointer, which on a
 * television is not a control at all. Left-aligned and directly below the field, it is the next thing
 * down, and the visual order and the focus order are the same order.
 */
@Composable
private fun IndexControls(
    state: SearchUiState,
    onStartIndexing: () -> Unit,
    onCancelIndexing: () -> Unit,
) {
    val coverage = state.coverage

    if (state.isIndexing) {
        Button(onClick = onCancelIndexing) { Text("Stop indexing") }
    } else if (coverage?.isComplete != true) {
        Button(onClick = onStartIndexing) {
            Text(if (coverage?.total == 0) "Index everything" else "Index the rest")
        }
    }

    Spacer(Modifier.height(8.dp))

    // Broken down by kind because the halves fail differently: live channels are a dozen requests
    // and finish quickly, while films and series on a large provider are the slow part. One
    // combined number would hide which half is still missing.
    val summary = when {
        state.isIndexing -> "Indexing… ${state.indexedSoFar} titles so far"
        coverage == null -> "Checking what is searchable…"
        coverage.total == 0 -> "Nothing indexed yet - search will find nothing"
        coverage.isComplete -> "All ${coverage.total} titles are searchable"
        else -> "${coverage.total} titles indexed of ${coverage.scopesTotal} shelves " +
            "(${coverage.liveIndexed} channels)"
    }

    Text(
        text = summary,
        style = MaterialTheme.typography.labelMedium,
        color = if (coverage?.isComplete == true) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )

    state.indexError?.let { message ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SearchHint(state: SearchUiState) {
    val coverage = state.coverage
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when {
                coverage == null -> " "
                coverage.total == 0 ->
                    "This provider has no server-side search, so the app searches what it has " +
                        "downloaded. Index your library to make search useful."
                coverage.isComplete -> "Search covers your whole library."
                else -> "Search covers the shelves opened so far. " +
                    "Index the rest to search everything."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Nothing found - and an honest account of why.
 *
 * The distinction matters more than it looks. With an incomplete index, "no results" does not mean
 * the title is absent from the provider, it means the app has not looked yet. Saying "not on your
 * provider" there would be a claim the app cannot support, and the viewer has no way to tell it apart
 * from a correct answer.
 */
@Composable
private fun NoResults(state: SearchUiState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Nothing matched \"${state.query.trim()}\".",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (state.missingBecauseIncomplete) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Not everything is indexed yet, so this does not mean it is absent from " +
                    "your provider. Index the rest and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Results(
    state: SearchUiState,
    onHitClick: (SearchHit) -> Unit,
) {
    // One list, grouped by kind with a heading per group, rather than three separate lists. Three
    // lists would each need their own focus target, and arrowing down from the search field would
    // have to cross a boundary the viewer cannot see.
    val sections = buildList {
        if (state.live.isNotEmpty()) add("Live TV" to state.live)
        if (state.movies.isNotEmpty()) add("Films" to state.movies)
        if (state.series.isNotEmpty()) add("Series" to state.series)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { (title, hits) ->
            item(key = "header-$title") {
                Text(
                    text = "$title (${hits.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(
                count = hits.size,
                key = { index -> hits[index].let { "${it.kind}:${it.contentId}" } },
            ) { index ->
                HitRow(hit = hits[index], onClick = { onHitClick(hits[index]) })
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val image = hit.posterUrl ?: hit.iconUrl
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(72.dp)
                        .height(48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surface),
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = kindLabel(hit.kind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun kindLabel(kind: ContentKind): String = when (kind) {
    ContentKind.LIVE_CHANNEL -> "TV"
    ContentKind.MOVIE -> "Film"
    ContentKind.SERIES -> "Series"
    ContentKind.EPISODE -> "Episode"
}
