package com.fourj.iptv.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.fourj.iptv.di.AppContainer
import com.fourj.iptv.domain.model.LiveChannel
import com.fourj.iptv.domain.model.ProviderProfile
import com.fourj.iptv.ui.epg.LiveNowRow
import com.fourj.iptv.ui.theme.LocalUiScale

/**
 * Live TV browse: categories across the top, channels below.
 *
 * That shape is deliberate. A two-pane sidebar/grid layout is fine with a mouse but awkward with a
 * remote, and a grid of hundreds of channels is hard to scan from a sofa. One row of category tabs
 * and one long, focus-walking list is the pattern television users already know from a cable box.
 */
@Composable
fun LiveScreen(
    container: AppContainer,
    profile: ProviderProfile,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveViewModel = viewModel(
        key = "live-${profile.baseUrl}",
        factory = LiveViewModel.factory(container, profile),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowNext by viewModel.nowNext.collectAsStateWithLifecycle()
    val uiScale = LocalUiScale.current
    val error = state.error
    val nowSeconds = remember(state.channels) { System.currentTimeMillis() / 1000 }

    // Playing takes over the whole screen, the way a set-top box does.
    state.playing?.let { channel ->
        com.fourj.iptv.ui.player.PlayerScreen(
            channel = channel,
            channels = state.visibleChannels,
            streamUrl = viewModel.streamUrl(channel),
            requestHeaders = viewModel.requestHeaders(channel),
            nowNext = nowNext[channel.streamId],
            onChannelChange = viewModel::play,
            onBack = viewModel::onPlaybackFinished,
            modifier = modifier,
        )
        return
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = uiScale.horizontalMarginDp.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Live TV",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = profile.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // No Sign out button here any more. It moved to the app's top bar, where the
                // D-pad can actually reach it and where it works from every tab.
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = uiScale.horizontalMarginDp.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(count = state.categories.size, key = { state.categories[it].id }) { index ->
                    val category = state.categories[index]
                    val selected = category.id == state.selectedCategoryId
                    Button(
                        onClick = { viewModel.selectCategory(category.id) },
                        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.1f),
                    ) {
                        Text(
                            text = category.name,
                            maxLines = 1,
                            style = if (selected) {
                                MaterialTheme.typography.labelLarge
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // "On now" across the channels already loaded. Free: it reads the same cache the
            // channel list filled.
            if (nowNext.isNotEmpty() && state.visibleChannels.isNotEmpty()) {
                LiveNowRow(
                    channels = state.visibleChannels,
                    nowNextByStream = nowNext,
                    nowSeconds = nowSeconds,
                    onSelect = viewModel::play,
                    modifier = Modifier.padding(
                        horizontal = uiScale.horizontalMarginDp.dp,
                        vertical = 8.dp,
                    ),
                )
                Spacer(Modifier.height(12.dp))
            }

            when {
                error != null -> Message(text = error, color = MaterialTheme.colorScheme.error)

                state.isLoadingChannels && state.channels.isEmpty() ->
                    Message(text = "Loading channels...")

                state.visibleChannels.isEmpty() && !state.isLoadingChannels ->
                    Message(text = "No channels in this category.")

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = uiScale.horizontalMarginDp.dp,
                        end = uiScale.horizontalMarginDp.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(count = state.visibleChannels.size, key = { state.visibleChannels[it].streamId }) { index ->
                        val channel = state.visibleChannels[index]
                        ChannelRow(
                            channel = channel,
                            nowNext = nowNext[channel.streamId],
                            nowSeconds = nowSeconds,
                            logoSizeDp = uiScale.logoSizeDp,
                            onClick = { viewModel.play(channel) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    nowNext: com.fourj.iptv.ui.live.NowNext?,
    nowSeconds: Long,
    logoSizeDp: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        scale = CardDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val logo = channel.iconUrl
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(logoSizeDp.dp)
                        .clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.width(16.dp))
            }
            com.fourj.iptv.ui.epg.NowNextRow(
                channelName = channel.name,
                nowNext = nowNext,
                nowSeconds = nowSeconds,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Message(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
