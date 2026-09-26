package com.fourj.iptv.ui.vod

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.fourj.iptv.domain.model.Episode
import com.fourj.iptv.ui.theme.LocalUiScale

/**
 * Seasons across the top, episodes below.
 *
 * Episodes are the level a viewer actually navigates: a series is a long list of them and the
 * season is a step on the way, not a destination in itself.
 */
@Composable
fun SeriesDetailScreen(
    detail: SeriesDetailState,
    onSeasonChange: (Int) -> Unit,
    onEpisodeClick: (Episode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiScale = LocalUiScale.current

    Surface(
        modifier = modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = uiScale.horizontalMarginDp.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = detail.series.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Header: artwork, then whatever metadata the panel supplied. Every field is optional
            // on a real panel, so each line is conditional rather than shown empty.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = uiScale.horizontalMarginDp.dp),
            ) {
                detail.series.posterUrl?.let { poster ->
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(150.dp)
                            .height(225.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surface),
                    )
                    Spacer(Modifier.width(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    detail.series.plot?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    MetadataLine("Genre", detail.series.genre)
                    MetadataLine("Cast", detail.series.cast)
                    MetadataLine("Director", detail.series.director)
                    MetadataLine("Released", detail.series.releaseDate)
                }
            }

            Spacer(Modifier.height(16.dp))

            if (detail.seasons.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = uiScale.horizontalMarginDp.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(count = detail.seasons.size, key = { detail.seasons[it].number }) { index ->
                        val season = detail.seasons[index]
                        Button(
                            onClick = { onSeasonChange(season.number) },
                            scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.08f),
                        ) {
                            Text(season.name)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            when {
                detail.error != null -> Message(detail.error, MaterialTheme.colorScheme.error)
                detail.isLoading -> Message("Loading episodes…")
                detail.episodes.isEmpty() -> Message("No episodes here.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = uiScale.horizontalMarginDp.dp,
                        end = uiScale.horizontalMarginDp.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = detail.episodes.size, key = { detail.episodes[it].id }) { index ->
                        val episode = detail.episodes[index]
                        Card(
                            onClick = { onEpisodeClick(episode) },
                            modifier = Modifier.fillMaxWidth(),
                            scale = CardDefaults.scale(focusedScale = 1.02f),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "E${episode.episodeNumber}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(64.dp),
                                )
                                Text(
                                    text = episode.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

