package com.coinportracker.settings

/** v5.1.0 — Rolagem de banner: biblioteca de GIFs, fotos e vídeos atrás do saldo. v5.1.1: 200/200/2 e seletor por toque. */
enum class RollKind { GIF, PHOTO, VIDEO }

/** Um item da biblioteca. [file] é só o nome do arquivo dentro da pasta privada da biblioteca (nunca um caminho). */
data class RollItem(val id: String, val kind: RollKind, val file: String, val bytes: Long = 0L, val addedAt: Long = 0L)

/**
 * Estado da rolagem. A ordem de cada tipo é a ordem da lista. Desligada por padrão:
 * o banner único continua igual até a pessoa ligar a rolagem (ou adicionar mídia nela).
 */
data class BannerRoll(
    val enabled: Boolean = false,
    val items: List<RollItem> = emptyList(),
    val gifsOn: Boolean = true,
    val photosOn: Boolean = true,
    val videosOn: Boolean = true,
    /** A migração do banner antigo já rodou (uma vez só). */
    val migrated: Boolean = false,
    /** v5.1.1: mídia escolhida no seletor da Início (id do item); vazia = a primeira da sequência. */
    val selected: String = "",
) {
    fun of(kind: RollKind): List<RollItem> = items.filter { it.kind == kind }
    fun count(kind: RollKind): Int = items.count { it.kind == kind }
    /** Sequência da Início: GIFs na ordem, depois fotos, depois vídeos (só os tipos ligados). */
    val carousel: List<RollItem> get() = buildList {
        if (gifsOn) addAll(of(RollKind.GIF))
        if (photosOn) addAll(of(RollKind.PHOTO))
        // v5.1.5: vídeos saíram da Rolagem (os já salvos ficam ocultos)
    }
    /** A Início usa a rolagem só quando ligada e com algo para mostrar; senão, o banner único de sempre. */
    val active: Boolean get() = enabled && carousel.isNotEmpty()
    /** v5.1.1: o banner atual: o escolhido (se ainda existir e o tipo estiver ligado) ou o primeiro da sequência. */
    val current: RollItem? get() = carousel.let { c -> c.firstOrNull { it.id == selected } ?: c.firstOrNull() }
    /** v5.1.1: tipos com mídia (e ligados) para a pergunta do seletor, na ordem GIF, Foto, Vídeo. */
    val pickableKinds: List<RollKind> get() = RollKind.entries.filter { k -> carousel.any { it.kind == k } }
    val totalBytes: Long get() = items.sumOf { it.bytes.coerceAtLeast(0) }
}

/**
 * v5.1.3 — fonte única do banner: com a Rolagem ativa, é o arquivo da mídia escolhida no seletor
 * (Início, prévia dos Ajustes do Banner e "Melhorar resolução" usam este mesmo caminho); senão, o banner único.
 */
fun bannerSource(s: AppSettings, libDir: String, default: String): String {
    val item = if (s.roll.active) s.roll.current else null
    return if (item != null) "$libDir/${item.file}" else s.bannerUri.ifBlank { default }
}

object RollRules {
    const val MAX_GIFS = 200
    const val MAX_PHOTOS = 200
    /** v5.1.4: vídeos sem limite de quantidade (só 1 min cada e o uso de armazenamento aparece na tela). */
    const val MAX_VIDEOS = Int.MAX_VALUE
    const val MAX_VIDEO_MS = 60_000L
    /** Tetos por arquivo (memória: um GIF fica inteiro na memória enquanto visível). */
    const val MAX_GIF_BYTES = 16L * 1024 * 1024
    const val MAX_PHOTO_BYTES = 24L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 64L * 1024 * 1024
    /** Vídeo: maior resolução possível até 1080p (lado menor). */
    const val VIDEO_MAX_SHORT_SIDE = 1080

    fun max(kind: RollKind) = when (kind) { RollKind.GIF -> MAX_GIFS; RollKind.PHOTO -> MAX_PHOTOS; RollKind.VIDEO -> MAX_VIDEOS }
    fun maxBytes(kind: RollKind) = when (kind) { RollKind.GIF -> MAX_GIF_BYTES; RollKind.PHOTO -> MAX_PHOTO_BYTES; RollKind.VIDEO -> MAX_VIDEO_BYTES }

    fun limitMessage(kind: RollKind): String = when (kind) {
        RollKind.GIF -> "Limite de 200 GIFs atingido."
        RollKind.PHOTO -> "Limite de 200 fotos atingido."
        RollKind.VIDEO -> "Não foi possível adicionar o vídeo."
    }
    fun tooBigMessage(kind: RollKind): String = when (kind) {
        RollKind.GIF -> "GIF grande demais (máx. 16 MB)."
        RollKind.PHOTO -> "Foto grande demais (máx. 24 MB)."
        RollKind.VIDEO -> "Vídeo grande demais."
    }

    /** null = pode adicionar; senão, a mensagem curta. [pending] = itens a caminho (ex.: vídeo sendo preparado). */
    fun canAdd(roll: BannerRoll, kind: RollKind, pending: Int = 0): String? =
        if (roll.count(kind) + pending >= max(kind)) limitMessage(kind) else null

    /** Adiciona no fim do seu tipo; recusa (devolve o mesmo estado) se passar do limite ou repetir o id. */
    fun add(roll: BannerRoll, item: RollItem): BannerRoll {
        if (roll.items.any { it.id == item.id } || canAdd(roll, item.kind) != null || !validFile(item.file)) return roll
        return roll.copy(items = roll.items + item)
    }

    /** Coloca no começo do seu tipo (migração do banner antigo). */
    fun addFirst(roll: BannerRoll, item: RollItem): BannerRoll {
        if (roll.items.any { it.id == item.id } || canAdd(roll, item.kind) != null || !validFile(item.file)) return roll
        return roll.copy(items = listOf(item) + roll.items)
    }

    /** Marca o recém-adicionado como banner atual (e liga o tipo dele no seletor), quando [select]. */
    fun selectAdded(roll: BannerRoll, id: String, select: Boolean): BannerRoll {
        if (!select) return roll
        val k = roll.items.firstOrNull { it.id == id }?.kind ?: return roll
        return roll.copy(selected = id, gifsOn = roll.gifsOn || k == RollKind.GIF, photosOn = roll.photosOn || k == RollKind.PHOTO, videosOn = roll.videosOn || k == RollKind.VIDEO)
    }

    fun remove(roll: BannerRoll, id: String): BannerRoll = roll.copy(items = roll.items.filterNot { it.id == id })

    /** Move o item [delta] posições dentro do próprio tipo (vídeos não têm ordem: no-op). */
    fun move(roll: BannerRoll, id: String, delta: Int): BannerRoll {
        val item = roll.items.firstOrNull { it.id == id } ?: return roll
        if (item.kind == RollKind.VIDEO || delta == 0) return roll
        val same = roll.of(item.kind).toMutableList()
        val from = same.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, same.lastIndex)
        if (from == to) return roll
        same.add(to, same.removeAt(from))
        return roll.copy(items = RollKind.entries.flatMap { k -> if (k == item.kind) same else roll.of(k) })
    }

    private val FILE_RE = Regex("[A-Za-z0-9_.-]{1,64}")
    fun validFile(name: String): Boolean = FILE_RE.matches(name) && !name.startsWith(".") && ".." !in name

    // Codificação no DataStore: um item por linha "id|KIND|arquivo|bytes|adicionadoEm"
    fun encode(items: List<RollItem>): String = items.joinToString("\n") { "${it.id}|${it.kind.name}|${it.file}|${it.bytes}|${it.addedAt}" }

    /** Leitura tolerante: ignora linhas inválidas, ids repetidos e o que passar dos limites (ordem preservada). */
    fun decode(s: String?): List<RollItem> {
        if (s.isNullOrBlank()) return emptyList()
        val out = ArrayList<RollItem>()
        val seen = HashSet<String>()
        val counts = HashMap<RollKind, Int>()
        for (line in s.split('\n')) {
            val p = line.split('|')
            if (p.size < 3) continue
            val id = p[0].trim(); val file = p[2].trim()
            val kind = RollKind.entries.firstOrNull { it.name == p[1].trim() } ?: continue
            if (id.isEmpty() || id.length > 64 || !validFile(file) || !seen.add(id)) continue
            val n = counts.getOrDefault(kind, 0)
            if (n >= max(kind)) continue
            counts[kind] = n + 1
            out += RollItem(id, kind, file, p.getOrNull(3)?.toLongOrNull() ?: 0L, p.getOrNull(4)?.toLongOrNull() ?: 0L)
        }
        return out
    }

    /** Resumo curto para o bloco de Ajustes. */
    fun summary(roll: BannerRoll): String {
        if (roll.items.isEmpty()) return "Nenhuma mídia"
        val parts = listOfNotNull(
            roll.count(RollKind.GIF).takeIf { it > 0 }?.let { if (it == 1) "1 GIF" else "$it GIFs" },
            roll.count(RollKind.PHOTO).takeIf { it > 0 }?.let { if (it == 1) "1 foto" else "$it fotos" },
            roll.count(RollKind.VIDEO).takeIf { it > 0 }?.let { if (it == 1) "1 vídeo" else "$it vídeos" },
        )
        return (if (roll.enabled) "" else "Desligada · ") + parts.joinToString(" · ")
    }

    /** v6.0: nota da limpeza dos vídeos ocultos ("quantidade|bytes|quando"). */
    fun cleanNote(count: Int, bytes: Long, at: Long) = "$count|$bytes|$at"
    fun cleanNoteText(note: String): String? {
        val p = note.split('|'); val n = p.getOrNull(0)?.toIntOrNull() ?: return null; val b = p.getOrNull(1)?.toLongOrNull() ?: return null
        if (n <= 0) return null
        return (if (n == 1) "1 vídeo antigo da Rolagem" else "$n vídeos antigos da Rolagem") + " (ocultos desde a 5.1.5) foram removidos do app: ${fmtBytes(b)} liberados. Os originais na sua galeria continuam lá."
    }

    fun fmtBytes(b: Long): String = when {
        b < 1024 * 1024 -> "%d KB".format((b + 1023) / 1024)
        else -> String.format(java.util.Locale("pt", "BR"), "%.1f MB", b / 1048576.0)
    }
}
