package com.coinportracker.backup

import com.coinportracker.database.ChainActivityEntity
import com.coinportracker.database.ChainHoldingEntity
import com.coinportracker.database.HistoryStateEntity
import com.coinportracker.database.MarketCacheEntity
import com.coinportracker.database.ValueSnapshotEntity
import com.coinportracker.security.BackupCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Tipos de mídia dentro do backup completo. */
enum class MediaKind { ROLL, HD, BANNER, BANNER_VIDEO }

/**
 * Um arquivo de mídia do backup. [name] é só o nome (nunca um caminho). Para [MediaKind.ROLL], [rollKind]/[rollId]/[addedAt]
 * reconstroem o item da Rolagem; para [MediaKind.HD], [hdOf] é a chave do original (nome do arquivo ou "asset_…").
 */
@Serializable
data class MediaEntry(val kind: String, val name: String, val bytes: Long, val rollKind: String? = null, val rollId: String? = null,
                      val addedAt: Long = 0, val hdOf: String? = null) {
    val path: String get() = "media/${kind.lowercase()}/$name"
}

@Serializable
data class RollStateDto(val enabled: Boolean, val gifsOn: Boolean, val photosOn: Boolean, val selected: String, val order: List<String>)

/** Tudo o que o backup completo (v2) guarda além das tabelas do backup v1. */
@Serializable
data class FullBackupData(
    val base: BackupPayload,
    val chainHoldings: List<ChainHoldingEntity> = emptyList(),
    val chainActivity: List<ChainActivityEntity> = emptyList(),
    val historyStates: List<HistoryStateEntity> = emptyList(),
    val valueSnapshots: List<ValueSnapshotEntity> = emptyList(),
    /** Recompensas de staking, épocas e preços por hora já lidos (chaves do cache local). */
    val cache: List<MarketCacheEntity> = emptyList(),
    val devConfig: String? = null,
    val taxRules: String? = null,
    val roll: RollStateDto? = null,
    val media: List<MediaEntry> = emptyList(),
)

@Serializable
data class FullManifest(
    val format: String = FORMAT, val schemaVersion: Int = SCHEMA, val appVersion: String, val versionCode: Int, val createdAt: Long,
    /** caminho no arquivo → SHA-256 do conteúdo. */
    val entries: Map<String, String> = emptyMap(),
) {
    companion object { const val FORMAT = "coinportracker.backup.v2"; const val SCHEMA = 2 }
}

class BackupVersionException(msg: String) : IllegalStateException(msg)

/** Backup lido e conferido numa pasta temporária, pronto para a restauração (nada foi aplicado ainda). */
class ExtractedBackup(val manifest: FullManifest?, val data: FullBackupData, val dir: File?, val files: Map<String, File>) {
    fun file(e: MediaEntry): File? = files[e.path]
    fun discard() { dir?.deleteRecursively() }
}

/**
 * v6.0 — backup completo: ZIP (data.json, mídias e manifest.json com o SHA-256 de cada arquivo) dentro do fluxo cifrado
 * [StreamCipher]. Leitura com tetos (tamanho por arquivo e total), nomes validados (sem "../" nem pastas inesperadas) e
 * conferência de todos os hashes antes de qualquer restauração.
 */
object FullBackup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    const val DATA = "data.json"
    const val MANIFEST = "manifest.json"
    const val MAX_DATA_BYTES = 512L shl 20
    const val MAX_MEDIA_BYTES = 80L shl 20
    private val NAME = Regex("[A-Za-z0-9_.-]{1,96}")

    fun validName(n: String) = NAME.matches(n) && !n.startsWith(".") && ".." !in n

    /** Grava o backup em [out] (fechado ao final). [files]: mídia → arquivo de origem no aparelho. */
    fun write(out: OutputStream, password: CharArray, data: FullBackupData, files: Map<MediaEntry, File>, appVersion: String, versionCode: Int,
              now: Long = System.currentTimeMillis(), iterations: Int = BackupCrypto.DEFAULT_ITERATIONS) {
        val enc = StreamCipher.encrypt(out.buffered(1 shl 16), password, iterations)
        val sums = LinkedHashMap<String, String>()
        ZipOutputStream(enc).use { zip ->
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
            fun put(path: String, input: InputStream) {
                val md = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(path))
                val buf = ByteArray(64 * 1024)
                input.use { i -> while (true) { val r = i.read(buf); if (r < 0) break; md.update(buf, 0, r); zip.write(buf, 0, r) } }
                zip.closeEntry()
                sums[path] = md.digest().joinToString("") { "%02x".format(it) }
            }
            val present = data.media.filter { e -> validName(e.name) && files[e]?.isFile == true }
            put(DATA, json.encodeToString(FullBackupData.serializer(), data.copy(media = present)).byteInputStream(Charsets.UTF_8))
            for (e in present) put(e.path, files[e]!!.inputStream())
            val m = FullManifest(appVersion = appVersion, versionCode = versionCode, createdAt = now, entries = sums)
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(json.encodeToString(FullManifest.serializer(), m).toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
    }

    /**
     * Decifra e extrai para [staging] conferindo tudo. Falha (sem deixar nada em [staging]) se a senha estiver errada,
     * o arquivo tiver sido alterado ou cortado, um hash não bater, um nome for inválido ou a versão for mais nova que [maxVersionCode].
     */
    fun extract(input: InputStream, password: CharArray, staging: File, maxVersionCode: Int, maxTotalBytes: Long = Long.MAX_VALUE): ExtractedBackup {
        staging.deleteRecursively(); staging.mkdirs()
        try {
            val sums = HashMap<String, String>()
            val files = HashMap<String, File>()
            var data: FullBackupData? = null
            var manifest: FullManifest? = null
            var total = 0L
            ZipInputStream(StreamCipher.decrypt(input.buffered(1 shl 16), password)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val path = e.name
                    val cap = when {
                        path == DATA || path == MANIFEST -> MAX_DATA_BYTES
                        path.startsWith("media/") -> MAX_MEDIA_BYTES
                        else -> throw IllegalStateException("Backup recusado: conteúdo inesperado ($path).")
                    }
                    if (e.isDirectory || path in sums) throw IllegalStateException("Backup recusado: entrada repetida ou inválida.")
                    val target: File
                    if (path.startsWith("media/")) {
                        val parts = path.split('/')
                        if (parts.size != 3 || MediaKind.entries.none { it.name.lowercase() == parts[1] } || !validName(parts[2])) throw IllegalStateException("Backup recusado: nome de arquivo inválido.")
                        target = File(File(staging, parts[1]).apply { mkdirs() }, parts[2])
                    } else target = File(staging, path)
                    val md = MessageDigest.getInstance("SHA-256")
                    var n = 0L
                    target.outputStream().buffered(1 shl 16).use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = zip.read(buf); if (r < 0) break
                            n += r; total += r
                            if (n > cap) throw IllegalStateException("Backup recusado: arquivo grande demais ($path).")
                            if (total > maxTotalBytes) throw IllegalStateException("Sem espaço livre suficiente para ler este backup.")
                            md.update(buf, 0, r); o.write(buf, 0, r)
                        }
                    }
                    sums[path] = md.digest().joinToString("") { "%02x".format(it) }
                    when (path) {
                        DATA -> data = json.decodeFromString(FullBackupData.serializer(), target.readText(Charsets.UTF_8)).also { target.delete() }
                        MANIFEST -> manifest = json.decodeFromString(FullManifest.serializer(), target.readText(Charsets.UTF_8)).also { target.delete() }
                        else -> files[path] = target
                    }
                }
            }
            val m = manifest ?: throw IllegalStateException("Backup incompleto: falta o manifesto.")
            if (m.format != FullManifest.FORMAT) throw IllegalStateException("Formato de backup desconhecido: ${m.format.take(40)}")
            if (m.schemaVersion > FullManifest.SCHEMA) throw BackupVersionException("Backup criado por uma versão mais nova do app (formato ${m.schemaVersion}). Atualize o app para restaurar.")
            if (m.versionCode > maxVersionCode) throw BackupVersionException("Backup criado pela versão ${m.appVersion.take(16)}, mais nova que esta. Atualize o app para restaurar.")
            for ((p, h) in m.entries) if (sums[p] != h) throw IllegalStateException("Falha de integridade: $p não confere com o manifesto.")
            if (sums.keys.any { it != MANIFEST && it !in m.entries }) throw IllegalStateException("Falha de integridade: arquivo fora do manifesto.")
            val d = data ?: throw IllegalStateException("Backup incompleto: faltam os dados.")
            BackupCodec.validate(d.base)
            if (d.base.schemaVersion > 1) throw BackupVersionException("Backup criado por uma versão mais nova do app.")
            return ExtractedBackup(m, d, staging, files)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }
}
