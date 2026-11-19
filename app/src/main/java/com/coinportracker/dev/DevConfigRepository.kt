package com.coinportracker.dev

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

class DevConfigRepository(private val context: Context) {
    private val file = File(context.filesDir, "dev-config.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _config = MutableStateFlow(load())
    val config = _config.asStateFlow()
    private var undo: DevConfig? = null

    init { DevSources.replace(_config.value) }

    fun current(): DevConfig = _config.value
    fun canUndo(): Boolean = undo != null

    private fun load(): DevConfig {
        if (!file.exists()) return DevDefaults.empty()
        return runCatching { json.decodeFromString(DevConfig.serializer(), file.readText()) }.getOrElse { DevDefaults.empty() }
    }

    fun save(c: DevConfig): DevValidation {
        val v = DevValidator.validate(c)
        if (!v.ok) return v
        undo = _config.value
        persist(c)
        return v
    }

    private fun persist(c: DevConfig) {
        file.writeText(json.encodeToString(DevConfig.serializer(), c))
        _config.value = c
        DevSources.replace(c)
    }

    fun undo(): Boolean {
        val prev = undo ?: return false
        undo = _config.value
        persist(prev)
        return true
    }

    fun resetSection(section: String): DevValidation {
        val c = _config.value
        val next = when (section) {
            "chains" -> c.copy(chains = emptyList())
            "prices" -> c.copy(prices = DevDefaults.prices())
            "custom" -> c.copy(customChains = emptyList(), customTokens = emptyList())
            "net" -> c.copy(net = DevNetSettings())
            "all" -> DevDefaults.empty()
            else -> c
        }
        return save(next)
    }

    fun exportJson(): String = json.encodeToString(DevConfig.serializer(), current())

    fun importJson(raw: String): DevValidation {
        if (raw.length > DevValidator.MAX_IMPORT_BYTES) return DevValidation(listOf("Arquivo de configuração grande demais (máx. 512 KB)."))
        val parsed = runCatching { json.decodeFromString(DevConfig.serializer(), raw) }.getOrElse {
            return DevValidation(listOf("Arquivo de configuração inválido."))
        }
        return save(parsed)
    }
}
