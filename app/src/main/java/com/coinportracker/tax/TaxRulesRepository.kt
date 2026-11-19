package com.coinportracker.tax

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.taxStore by preferencesDataStore(name = "tax_rules")

/** Persistência local das regras fiscais (JSON editável). Vazio = padrão vigente. */
class TaxRulesRepository(private val context: Context) {
    private val key = stringPreferencesKey("rules")
    val rules: Flow<TaxRules> = context.taxStore.data.map { p -> p[key]?.let { runCatching { TaxRules.parse(it) }.getOrNull() } ?: TaxRules.default() }

    suspend fun current(): TaxRules = rules.first()

    suspend fun save(r: TaxRules) {
        val errs = TaxRules.validate(r)
        require(errs.isEmpty()) { errs.joinToString("; ") }
        val text = TaxRules.stringify(r)
        context.taxStore.edit { it[key] = text }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { snapshotFile().writeText(text) }
    }

    suspend fun reset() {
        context.taxStore.edit { it.remove(key) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { snapshotFile().delete() }
    }

    suspend fun importJson(text: String): TaxRules {
        val r = TaxRules.parseValidated(text)
        save(r)
        return r
    }

    suspend fun exportJson(): String = TaxRules.stringify(current())

    private fun snapshotFile() = File(context.filesDir, "tax_rules.json")
}
