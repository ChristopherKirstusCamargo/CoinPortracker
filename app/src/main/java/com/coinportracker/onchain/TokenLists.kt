package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Fonte de listas de tokens conhecidos por rede (usada para descobrir saldos ERC-20 via Multicall). */
fun interface TokenListSource { suspend fun tokens(network: Network): List<TokenMeta> }

object TokenListCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val CG_IMG = "https://assets.coingecko.com/coins/images/"

    /** Formato compacto embutido: [[address, symbol, name, decimals, logo], ...] (logo "cg:" = prefixo CoinGecko). */
    fun parseCompact(text: String): List<TokenMeta> = json.parseToJsonElement(text).jsonArray.mapNotNull { e ->
        val a = e as? JsonArray ?: return@mapNotNull null
        val logo = a.getOrNull(4)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("cg:")) CG_IMG + it.removePrefix("cg:") else it }
        TokenMeta(a[0].jsonPrimitive.content.lowercase(), a[1].jsonPrimitive.content, a[2].jsonPrimitive.content, a[3].jsonPrimitive.intOrNull ?: return@mapNotNull null, logo)
    }

    /** Formato Token Lists (tokens.coingecko.com/<rede>/all.json). */
    fun parseTokenList(text: String): List<TokenMeta> = json.parseToJsonElement(text).jsonObject["tokens"]!!.jsonArray.mapNotNull { e ->
        val o = e.jsonObject
        val addr = o["address"]?.jsonPrimitive?.content ?: return@mapNotNull null
        TokenMeta(addr.lowercase(), o["symbol"]?.jsonPrimitive?.content ?: "?", o["name"]?.jsonPrimitive?.content ?: "?",
            o["decimals"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null, o["logoURI"]?.jsonPrimitive?.content)
    }

    fun toCompact(list: List<TokenMeta>): String = buildString {
        append('[')
        list.forEachIndexed { i, t ->
            if (i > 0) append(',')
            append('[').append(q(t.address)).append(',').append(q(t.symbol)).append(',').append(q(t.name)).append(',').append(t.decimals).append(',')
            append(q(t.logo?.replace(CG_IMG, "cg:") ?: "")).append(']')
        }
        append(']')
    }

    private fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
}

/**
 * Listas de tokens: começa pela cópia embutida no app (funciona offline) e, uma vez por semana,
 * baixa a lista pública atualizada do CoinGecko e guarda em cache no armazenamento privado.
 */
class CachedTokenLists(
    private val http: HttpClient,
    private val cacheDir: File,
    private val bundled: (String) -> InputStream?,
    private val clock: () -> Long = System::currentTimeMillis,
) : TokenListSource {
    private val mem = HashMap<Network, List<TokenMeta>>()
    private val lock = Mutex()
    private val policy = RatePolicy(minIntervalMs = 500, maxRetries = 1)

    override suspend fun tokens(network: Network): List<TokenMeta> = lock.withLock {
        val slug = network.tokenListSlug ?: return@withLock emptyList()
        mem[network]?.let { return@withLock it }
        val file = File(cacheDir, "$slug.json.gz")
        val fresh = file.exists() && clock() - file.lastModified() < WEEK
        var list: List<TokenMeta>? = null
        if (!fresh) {
            val r = http.get("tokenlist", "https://tokens.coingecko.com/$slug/all.json", policy)
            if (r is Outcome.Ok) {
                list = withContext(Dispatchers.Default) { runCatching { TokenListCodec.parseTokenList(r.value) }.getOrNull() }
                if (list != null && list.size > 50) withContext(Dispatchers.IO) {
                    runCatching { cacheDir.mkdirs(); GZIPOutputStream(file.outputStream()).use { it.write(TokenListCodec.toCompact(list).toByteArray()) } }
                }
            }
        }
        if (list == null && file.exists()) list = withContext(Dispatchers.IO) { runCatching { GZIPInputStream(file.inputStream()).use { TokenListCodec.parseCompact(it.readBytes().decodeToString()) } }.getOrNull() }
        if (list == null) list = withContext(Dispatchers.IO) { runCatching { bundled("tokenlists/$slug.json.gz")?.let { s -> GZIPInputStream(s).use { TokenListCodec.parseCompact(it.readBytes().decodeToString()) } } }.getOrNull() }
        (list ?: emptyList()).also { mem[network] = it }
    }

    companion object { const val WEEK = 7L * 24 * 3600 * 1000 }
}

/** Tokens de staking líquido reconhecidos (aparecem na seção Staking). */
object StakingTokens {
    val liquid = setOf(
        "STETH", "WSTETH", "RETH", "CBETH", "WEETH", "EETH", "EZETH", "RSETH", "METH", "SWETH", "SFRXETH", "OSETH", "ETHX", "ANKRETH", "LSETH",
        "MSOL", "JITOSOL", "BSOL", "JUPSOL", "INF", "BNSOL", "HSOL", "VSOL", "DSOL", "STSOL", "LST", "BBSOL", "PICOSOL", "JSOL", "STKESOL",
        "SLISBNB", "ANKRBNB", "STKBNB", "BNBX", "STMATIC", "MATICX", "SUSDE", "SUSDS", "SDAI",
    )
    fun isLiquidStaking(symbol: String) = symbol.uppercase() in liquid
}
