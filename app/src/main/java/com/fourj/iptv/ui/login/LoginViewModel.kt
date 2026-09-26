package com.fourj.iptv.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fourj.iptv.data.remote.ProviderAddress
import com.fourj.iptv.data.remote.toUserMessage
import com.fourj.iptv.di.AppContainer
import com.fourj.iptv.domain.model.ProviderProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val isBusy: Boolean = false,
    val error: String? = null,
    /** Set once the credentials are verified and stored, so the caller can move on. */
    val connected: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !isBusy && server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onServerChange(value: String) {
        // A pasted provider link carries the credentials with it. Lift them straight out so the
        // user does not have to retype a long password on a television keyboard.
        val pasted = ProviderAddress.parseProfile(value)
        _state.update {
            if (pasted != null) {
                it.copy(
                    server = value,
                    username = pasted.username,
                    password = pasted.password,
                    error = null,
                )
            } else {
                it.copy(server = value, error = null)
            }
        }
    }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun connect() {
        val current = _state.value
        val profile = buildProfile(current) ?: run {
            _state.update { it.copy(error = "Enter your server address, username and password.") }
            return
        }

        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val repository = container.liveRepository(profile)
            repository.login()
                .onSuccess {
                    // Only persist once the provider has actually accepted them, so a typo can
                    // never replace a working profile.
                    container.credentialStore.save(profile)
                    repository.refreshCategories()
                    _state.update { it.copy(isBusy = false, connected = true) }
                }
                .onFailure { throwable ->
                    _state.update { it.copy(isBusy = false, error = throwable.toUserMessage()) }
                }
        }
    }

    /**
     * Accepts either a full provider link or the three fields typed separately.
     */
    private fun buildProfile(state: LoginUiState): ProviderProfile? {
        ProviderAddress.parseProfile(state.server)?.let { return it }

        val baseUrl = ProviderAddress.parseServer(state.server) ?: return null
        if (state.username.isBlank() || state.password.isBlank()) return null
        return ProviderProfile(
            baseUrl = baseUrl,
            username = state.username.trim(),
            password = state.password.trim(),
        )
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LoginViewModel(container) }
        }
    }
}
