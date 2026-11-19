package com.coinportracker.backup

import com.coinportracker.data.RestorePreview
import com.coinportracker.data.RestoreResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface BackupFlow {
    data object Idle : BackupFlow
    data class Busy(val text: String) : BackupFlow
    data class Review(val preview: RestorePreview) : BackupFlow
    data class Done(val text: String) : BackupFlow
}

/** Textos (pt-BR) do resumo antes e depois da restauração. */
object RestoreText {
    private fun n(v: Int, one: String, many: String) = if (v == 1) "1 $one" else "$v $many"

    fun summary(p: RestorePreview): List<String> = buildList {
        val date = runCatching { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(p.createdAt)) }.getOrDefault("")
        add("Criado em $date pela versão ${p.appVersion}" + if (p.full) "" else " (backup antigo, só dados)")
        add("Carteiras: ${n(p.walletsNew, "nova", "novas")}" + if (p.walletsKnown > 0) ", ${n(p.walletsKnown, "já existe", "já existem")} (mantida${if (p.walletsKnown == 1) "" else "s"})" else "")
        if (p.transactions > 0) add("Operações no backup: ${p.transactions} (só entram as que faltam)")
        if (p.activityRows > 0) add("Histórico on-chain: ${p.activityRows} registros")
        if (p.alerts + p.goals > 0) add("Alertas e metas: ${p.alerts + p.goals}")
        if (p.mediaFiles > 0) add("Fotos e GIFs: ${p.mediaFiles} (repetidos não duplicam)")
        if (p.hasSettings) add("Ajustes do app passam a ser os do backup (dá para desfazer)")
        if (p.hasTax) add("Regras fiscais")
        if (p.hasDev) add("Configurações Dev (endpoints e redes)")
    }

    fun done(r: RestoreResult): String = buildString {
        append("Restauração concluída. ")
        append(n(r.walletsAdded, "carteira nova", "carteiras novas")).append(", ")
        append(n(r.rowsAdded, "registro novo", "registros novos")).append(", ")
        append(n(r.mediaAdded, "mídia nova", "mídias novas")).append('.')
        if (r.mediaDuplicates > 0) append(" ${n(r.mediaDuplicates, "mídia já estava", "mídias já estavam")} no app.")
        if (r.mediaSkipped > 0) append(" ${n(r.mediaSkipped, "mídia ficou", "mídias ficaram")} de fora (limite de 200 por tipo).")
        if (r.bannerKept) append(" Seu banner atual foi mantido.")
        append(" Nada do que já estava no app foi apagado.")
    }
}
