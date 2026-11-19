package com.coinportracker.tax

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Regras fiscais versionadas e editáveis (IRPF / ganho de capital de criptoativos no Brasil).
 * Leis mudam: o valor padrão é o vigente em [effectiveFrom], mas o usuário pode editar,
 * importar e exportar a tabela em Configurações avançadas (Dev).
 *
 * Fontes do padrão 2026.1 (conferidas em 13/11/2026):
 * - P&R IRPF 2026 v1.00 (23/04/2026), Receita Federal: Grupo 08, códigos 01/02/03/10/99;
 *   declaração em Bens e Direitos pelo custo de aquisição quando ≥ R$ 5.000 por espécie.
 *   https://www.gov.br/receitafederal/pt-br/centrais-de-conteudo/publicacoes/perguntas-e-respostas/dirpf/p-r-irpf-2026-v1-00-2026-04-23.pdf
 * - Manual Meu Imposto de Renda, Bens Financeiros (atualizado 26/05/2026): criptoativos
 *   pelo valor original de aquisição (não o de mercado); limite de R$ 5.000 por espécie.
 *   https://www.gov.br/receitafederal/pt-br/assuntos/meu-imposto-de-renda/preenchimento/manual-mir/patrimonio/bens-financeiros
 * - Nota da Receita / Ministério da Fazenda (31/03/2023, página atualizada em 26/10/2026):
 *   isenção se o total alienado no mês ≤ R$ 35.000, no conjunto de todos os criptoativos;
 *   acima disso, ganho de capital com alíquotas progressivas; DARF código 4600.
 *   https://www.gov.br/fazenda/pt-br/assuntos/noticias/2023/marco/receita-federal-esclarece-sobre-declaracao-de-operacoes-com-moedas-virtuais
 * - Lei 8.981/1995, art. 21 (alíquotas 15 / 17,5 / 20 / 22,5 %); Lei 9.250/1995, art. 22 (isenção).
 * - MP 1.303/2025 caducou em 08/10/2025 (Ato Declaratório CN nº 67/2025): a alíquota única
 *   de 17,5 % e o fim da isenção de R$ 35.000 nunca chegaram a valer.
 *
 * O aplicativo não substitui um contabilista. Confira sempre no GCAP e na Receita o código
 * do DARF aceito no seu período (há menção secundária ao código 1897 a partir de abril/2026).
 */
@Serializable
data class TaxBracket(val upToBrl: String, val ratePct: String)

@Serializable
data class AssetCodeRule(val code: String, val label: String, val match: List<String> = emptyList())

@Serializable
data class TaxRules(
    val version: String = "2026.1",
    val effectiveFrom: String = "2026-01-01",
    val label: String = "Regras vigentes em 2026 (IRPF / ganho de capital de criptoativos)",
    val group: String = "08",
    val codes: List<AssetCodeRule> = defaultCodes,
    val declarationThresholdBrl: String = "5000.00",
    val monthlyExemptionBrl: String = "35000.00",
    val exemptionOnTotalAlienations: Boolean = true,
    val costMethod: String = "AVERAGE",
    val brackets: List<TaxBracket> = defaultBrackets,
    val darfCode: String = "4600",
    val darfDue: String = "Último dia útil do mês seguinte à alienação",
    val foreignFlatRatePct: String? = "15.0",
    val sources: List<String> = defaultSources,
    val disclaimer: String = DEFAULT_DISCLAIMER,
) {
    fun threshold(): BigDecimal = declarationThresholdBrl.toBigDecimalOrNull() ?: BigDecimal("5000")
    fun exemption(): BigDecimal = monthlyExemptionBrl.toBigDecimalOrNull() ?: BigDecimal("35000")
    fun effectiveDate(): LocalDate = runCatching { LocalDate.parse(effectiveFrom) }.getOrElse { LocalDate.of(2026, 1, 1) }

    fun codeFor(symbol: String, name: String? = null): AssetCodeRule {
        val s = symbol.trim().uppercase()
        val n = (name ?: "").uppercase()
        for (c in codes) {
            if (c.match.any { it.equals(s, true) || it.equals(n, true) }) return c
        }
        val stable = STABLE.contains(s) || n.contains("USD COIN") || n.contains("TETHER") || n.contains("STABLE")
        // v5: tabela editada/importada sem algum código obrigatório não pode derrubar o informe
        fun code(c: String) = codes.firstOrNull { it.code == c } ?: defaultCodes.first { it.code == c }
        return when {
            s == "BTC" || s == "BITCOIN" || n == "BITCOIN" -> code("01")
            stable -> code("03")
            s == "NFT" || n.contains("NFT") || n.contains("NON-FUNGIBLE") -> code("10")
            else -> code("02")
        }
    }

    fun taxOnGain(gain: BigDecimal): BigDecimal {
        if (gain.signum() <= 0) return BigDecimal.ZERO
        var leftover = gain
        var prev = BigDecimal.ZERO
        var tax = BigDecimal.ZERO
        for (b in brackets) {
            val cap = b.upToBrl.toBigDecimalOrNull() ?: continue
            val rate = b.ratePct.toBigDecimalOrNull() ?: continue
            val slice = leftover.min(cap.subtract(prev).max(BigDecimal.ZERO))
            if (slice.signum() > 0) tax = tax.add(slice.multiply(rate).scaleByPowerOfTen(-2))
            leftover = leftover.subtract(slice)
            prev = cap
            if (leftover.signum() <= 0) break
        }
        return tax
    }

    companion object {
        const val DEFAULT_DISCLAIMER =
            "Este informe é uma ajuda para a declaração. Não substitui um contabilista nem o programa GCAP da Receita Federal. Confira as regras vigentes e os comprovantes antes de declarar ou pagar."

        val defaultCodes = listOf(
            AssetCodeRule("01", "Criptomoeda Bitcoin — BTC", listOf("BTC", "BITCOIN")),
            AssetCodeRule("02", "Outras criptomoedas (altcoins)"),
            AssetCodeRule("03", "Stablecoins", listOf("USDT", "USDC", "DAI", "BRZ", "FDUSD", "TUSD", "USDP", "GUSD", "PYUSD", "EURC", "USDE", "BUSD")),
            AssetCodeRule("10", "NFTs (Non-Fungible Tokens)"),
            AssetCodeRule("99", "Outros criptoativos não incluídos nos códigos 01, 02, 03 ou 10"),
        )
        val defaultBrackets = listOf(
            TaxBracket("5000000", "15.0"),
            TaxBracket("10000000", "17.5"),
            TaxBracket("30000000", "20.0"),
            TaxBracket("999999999999", "22.5"),
        )
        val defaultSources = listOf(
            "P&R IRPF 2026 v1.00 (23/04/2026), Receita Federal — Grupo 08, códigos 01/02/03/10/99; declaração pelo custo de aquisição ≥ R$ 5.000 por espécie.",
            "Manual Meu Imposto de Renda, Bens Financeiros (atualizado 26/05/2026) — valor original de aquisição, não o de mercado.",
            "Nota da Receita Federal / Ministério da Fazenda (31/03/2023, página atualizada em 26/10/2026) — isenção de R$ 35.000 no mês sobre o conjunto das alienações; alíquotas progressivas; DARF 4600.",
            "Lei 8.981/1995, art. 21; Lei 9.250/1995, art. 22.",
            "MP 1.303/2025 caducou em 08/10/2025 (Ato Declaratório CN nº 67/2025).",
        )
        val STABLE = defaultCodes.first { it.code == "03" }.match.map { it.uppercase() }.toSet()

        val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        fun default() = TaxRules()
        fun parse(text: String): TaxRules = JSON.decodeFromString(serializer(), text)

        /** Importação/edição: decodifica e valida; erro legível em vez de regras incoerentes. */
        fun parseValidated(text: String): TaxRules {
            require(text.length <= 256 * 1024) { "Arquivo de regras grande demais." }
            val r = parse(text)
            val errs = validate(r)
            require(errs.isEmpty()) { errs.joinToString("; ") }
            return r
        }

        fun validate(r: TaxRules): List<String> {
            val e = ArrayList<String>()
            fun money(v: String, name: String) { val d = v.toBigDecimalOrNull(); if (d == null || d.signum() < 0) e += "$name inválido" }
            money(r.declarationThresholdBrl, "Limite de declaração")
            money(r.monthlyExemptionBrl, "Limite de isenção mensal")
            if (runCatching { LocalDate.parse(r.effectiveFrom) }.isFailure) e += "Data de vigência inválida (use AAAA-MM-DD)"
            if (r.brackets.isEmpty() || r.brackets.size > 20) e += "Informe de 1 a 20 faixas de alíquota"
            var prev = java.math.BigDecimal.ZERO
            r.brackets.forEachIndexed { i, b ->
                val cap = b.upToBrl.toBigDecimalOrNull(); val rate = b.ratePct.toBigDecimalOrNull()
                if (cap == null || cap <= prev) e += "Faixa ${i + 1}: limite deve ser maior que o da faixa anterior"
                if (rate == null || rate.signum() < 0 || rate > java.math.BigDecimal(100)) e += "Faixa ${i + 1}: alíquota entre 0 e 100%"
                if (cap != null) prev = cap
            }
            r.foreignFlatRatePct?.let { v -> val d = v.toBigDecimalOrNull(); if (d == null || d.signum() < 0 || d > java.math.BigDecimal(100)) e += "Alíquota de exterior inválida" }
            if (r.codes.size > 50 || r.codes.any { it.code.length > 4 || it.label.length > 200 || it.match.size > 200 }) e += "Tabela de códigos grande demais"
            if (r.costMethod !in setOf("AVERAGE", "FIFO")) e += "Método de custo deve ser AVERAGE ou FIFO"
            if (r.darfCode.length > 10 || r.sources.size > 50 || r.disclaimer.length > 2_000 || r.label.length > 300) e += "Texto longo demais"
            return e
        }
        fun stringify(r: TaxRules): String = JSON.encodeToString(serializer(), r)
    }
}
