package com.coinportracker.ui

import android.net.Uri
import com.coinportracker.data.ChartRange
import com.coinportracker.data.HubState
import com.coinportracker.data.SyncStatus
import com.coinportracker.domain.Currency
import com.coinportracker.onchain.PricePoint
import com.coinportracker.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal

/**
 * O que as telas principais precisam do ViewModel. Separado para que as telas possam ser
 * renderizadas com dados de exemplo (capturas de tela na JVM) sem banco nem rede.
 */
interface UiModel {
    /** v6.0: lê o histórico on-chain agora ([force] = puxar para atualizar; sem force, só se a última leitura tiver mais de 2 min). */
    fun refreshActivity(force: Boolean = false) {}
    /** v6.0: estado da leitura do histórico (carregando, erro por rede, redes sem histórico) para a Atividade. */
    val activityStatus: StateFlow<com.coinportracker.data.HistoryStatus> get() = NO_HIST
    val hub: StateFlow<HubState>
    val refreshing: StateFlow<Boolean>
    val sync: StateFlow<SyncStatus>
    val chart: StateFlow<ChartData?>
    val range: StateFlow<ChartRange>
    fun setRange(r: ChartRange)
    fun fullSync(manual: Boolean)
    fun updateSettings(f: (AppSettings) -> AppSettings)
    fun assetChart(range: ChartRange, key: String, onResult: (List<PricePoint>) -> Unit)
    fun setCostBasis(groupKey: String, avg: BigDecimal?, cur: Currency)
    fun ensureLedgerAsset(groupKey: String, symbol: String, name: String, iconChain: String?, onDone: (String) -> Unit)
    fun hideToken(key: String, hide: Boolean)
    fun setBanner(uri: Uri?)
    val activity: StateFlow<List<com.coinportracker.database.ChainActivityEntity>>
    /** v5: assinaturas das contas de stake (carteira ↔ stake é "Entre contas", não entrada/saída). */
    val stakeSignatures: StateFlow<Set<String>> get() = NO_SIGS
    /** v5: histórico completo para o Contábil (a lista da tela de Atividade é limitada às mais recentes). */
    val allActivity: StateFlow<List<com.coinportracker.database.ChainActivityEntity>> get() = activity
    val allTx: StateFlow<List<com.coinportracker.database.TransactionEntity>>
    fun addWatchWallet(address: String, name: String, kind: com.coinportracker.onchain.AddressKind? = null, onDone: (Boolean) -> Unit)
    fun addWallet(name: String, type: String)
    fun renameWallet(id: Long, name: String)
    fun removeWallet(id: Long)
    fun syncWallet(id: Long)
    fun validateAddress(a: String): Pair<com.coinportracker.onchain.AddressKind?, String?>
    fun toast(m: String?)
    val alerts: StateFlow<List<com.coinportracker.database.AlertEntity>>
    fun saveAlert(a: com.coinportracker.database.AlertEntity)
    fun deleteAlert(a: com.coinportracker.database.AlertEntity)
}

private val NO_SIGS: StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet())
private val NO_HIST: StateFlow<com.coinportracker.data.HistoryStatus> = kotlinx.coroutines.flow.MutableStateFlow(com.coinportracker.data.HistoryStatus())
