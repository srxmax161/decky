package com.dashdeck.data

import android.content.Context
import androidx.core.content.edit
import com.dashdeck.model.DashConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Phone editor and car session run in the same process, so one StateFlow keeps both in sync. */
class ConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences("dashdeck", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<DashConfig> = _config.asStateFlow()

    fun update(transform: (DashConfig) -> DashConfig) {
        val next = transform(_config.value).let { if (it.pages.isEmpty()) DashConfig.DEFAULT.copy(pages = DashConfig.DEFAULT.pages.take(1)) else it }
        _config.value = next
        prefs.edit { putString(KEY, json.encodeToString(DashConfig.serializer(), next)) }
    }

    fun reset() = update { DashConfig.DEFAULT }

    fun exportJson(): String = json.encodeToString(DashConfig.serializer(), _config.value)

    /** Throws if [raw] isn't a valid config, leaving the current one untouched. */
    fun importJson(raw: String) {
        val parsed = json.decodeFromString(DashConfig.serializer(), raw)
        update { parsed }
    }

    private fun load(): DashConfig {
        val raw = prefs.getString(KEY, null) ?: return DashConfig.DEFAULT
        return runCatching { json.decodeFromString(DashConfig.serializer(), raw) }
            .getOrNull()
            ?.takeIf { it.pages.isNotEmpty() && it.version >= DashConfig.CURRENT_VERSION }
            ?: DashConfig.DEFAULT
    }

    private companion object {
        const val KEY = "config"
    }
}
