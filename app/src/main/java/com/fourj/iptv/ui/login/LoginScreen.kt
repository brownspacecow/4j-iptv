package com.fourj.iptv.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.fourj.iptv.di.AppContainer
import com.fourj.iptv.ui.common.TvTextField
import com.fourj.iptv.ui.theme.LocalUiScale

/**
 * Connection screen.
 *
 * Typing a URL on a television keyboard is miserable, so the first field accepts a pasted provider
 * link and lifts the credentials out of it - see [LoginViewModel.onServerChange]. The separate
 * username and password boxes are there for providers that do not hand out a link.
 */
@Composable
fun LoginScreen(
    container: AppContainer,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = viewModel(factory = LoginViewModel.factory(container)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uiScale = LocalUiScale.current
    val keyboard = LocalSoftwareKeyboardController.current
    val serverFocus = FocusRequester()
    val error = state.error

    LaunchedEffect(Unit) { serverFocus.requestFocus() }

    LaunchedEffect(state.connected) {
        if (state.connected) onConnected()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = uiScale.horizontalMarginDp.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "4J TV",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Connect to your provider",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))

            TvTextField(
                value = state.server,
                onValueChange = viewModel::onServerChange,
                label = "Provider link or server address",
                placeholder = "http://server:port",
                modifier = Modifier
                    .width(620.dp)
                    .focusRequester(serverFocus),
            )

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TvTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = "Username",
                    modifier = Modifier.width(300.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                )
                TvTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = "Password",
                    modifier = Modifier.width(300.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboard?.hide()
                            viewModel.connect()
                        },
                    ),
                )
            }

            Spacer(Modifier.height(28.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Button(
                    onClick = {
                        keyboard?.hide()
                        viewModel.connect()
                    },
                    enabled = state.canSubmit,
                ) {
                    Text(if (state.isBusy) "Connecting..." else "Connect")
                }

                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
