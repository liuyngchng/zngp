package com.voicenote.app.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    // 服务器配置
    val serverUrl: String = "http://192.168.1.1:8080",
    val username: String = "admin",
    val password: String = ""
)

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[Keys.SERVER_URL] ?: "http://192.168.1.1:8080",
            username = prefs[Keys.USERNAME] ?: "admin",
            password = prefs[Keys.PASSWORD] ?: ""
        )
    }

    suspend fun updateServerConfig(url: String, username: String, password: String) {
        context.dataStore.edit {
            it[Keys.SERVER_URL] = url
            it[Keys.USERNAME] = username
            it[Keys.PASSWORD] = password
        }
    }
}