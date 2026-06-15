package com.iptv.fourj.data.db

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.iptv.fourj.data.model.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "providers")

class ProviderStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val providersKey = stringSetPreferencesKey("provider_list")

    private val activeProviderKey = longPreferencesKey("active_provider_id")

    suspend fun getProviders(): List<Provider> {
        val prefs = context.dataStore.data.first()
        val rawList = prefs[providersKey] ?: emptySet()
        return rawList.mapNotNull { runCatching { json.decodeFromString<Provider>(it) }.getOrNull() }
    }

    suspend fun addProvider(provider: Provider): Provider {
        val providers = getProviders().toMutableList()
        val newId = if (providers.isEmpty()) 1L else (providers.maxOf { it.id } + 1)
        val newProvider = provider.copy(id = newId)
        providers.add(newProvider)
        saveProviders(providers)
        return newProvider
    }

    suspend fun updateProvider(provider: Provider) {
        val providers = getProviders().toMutableList()
        val idx = providers.indexOfFirst { it.id == provider.id }
        if (idx >= 0) providers[idx] = provider
        saveProviders(providers)
    }

    suspend fun deleteProvider(providerId: Long) {
        val providers = getProviders().filter { it.id != providerId }
        saveProviders(providers)
        val prefs = context.dataStore.data.first()
        if (prefs[activeProviderKey] == providerId) {
            context.dataStore.edit { it.remove(activeProviderKey) }
        }
    }

    suspend fun getActiveProvider(): Provider? {
        val prefs = context.dataStore.data.first()
        val activeId = prefs[activeProviderKey] ?: return null
        return getProviders().find { it.id == activeId }
    }

    suspend fun setActiveProvider(providerId: Long) {
        context.dataStore.edit { it[activeProviderKey] = providerId }
    }

    private suspend fun saveProviders(providers: List<Provider>) {
        val rawSet = providers.map { json.encodeToString(it) }.toSet()
        context.dataStore.edit { it[providersKey] = rawSet }
    }
}
