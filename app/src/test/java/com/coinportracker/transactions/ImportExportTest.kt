package com.coinportracker.transactions

import org.junit.Assert.*
import org.junit.Test

class ImportExportTest {
    @Test fun csv_ptBR_semicolon_andAliases() {
        val csv = "data;tipo;ativo;quantidade;preço;moeda;taxa\n01/02/2025 10:00;Compra;sol;1,5;\"1.234,56\";BRL;2,50\n"
        val rows = CsvCodec.parse(csv)
        assertEquals(1, rows.size)
        val r = rows[0]
        assertTrue(r.errors.toString(), r.valid)
        assertEquals("SOL", r.record!!.asset)
        assertEquals("1,5", r.record!!.quantity)
        assertEquals(0, java.math.BigDecimal("1234.56").compareTo(com.coinportracker.core.Decimals.parse(r.record!!.unitPrice)))
    }

    @Test fun csv_validationErrors() {
        val csv = "date,type,asset,quantity,unit_price\n2025-01-01,BUY,BTC,abc,\n2025-01-01,FOO,BTC,1,1\nnot-a-date,SELL,BTC,1,1\n"
        val rows = CsvCodec.parse(csv)
        assertFalse(rows[0].valid); assertTrue(rows[0].errors.any { "Quantidade" in it }); assertTrue(rows[0].errors.any { "preço" in it })
        assertFalse(rows[1].valid); assertTrue(rows[1].errors.any { "Tipo" in it })
        assertFalse(rows[2].valid); assertTrue(rows[2].errors.any { "Data" in it })
    }

    @Test fun csv_roundTrip_quotesAndNewlines() {
        val rec = TxRecord(1735732800000, "SWAP", "ETH", "1", "3000", "USD", "SOL", "20", notes = "linha 1\nlinha \"2\", fim")
        val text = CsvCodec.export(listOf(rec))
        val back = CsvCodec.parse(text)
        assertEquals(1, back.size)
        assertEquals(rec, back[0].record)
    }

    @Test fun json_roundTrip() {
        val recs = listOf(TxRecord(1, "BUY", "BTC", "0.1", "300000"), TxRecord(2, "STAKING", "SOL", "0.01", "500"))
        val back = JsonCodec.parse(JsonCodec.export(recs))
        assertEquals(recs, back.map { it.record })
    }

    @Test fun json_invalid_throwsClearError() {
        try { JsonCodec.parse("{\"foo\":1}"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("JSON inválido")) }
    }

    @Test fun duplicates_detectedAgainstExistingAndWithinFile() {
        val a = TxRecord(1735732800000, "BUY", "BTC", "0.10", "300000")
        val sameDifferentScale = a.copy(quantity = "0.1", timestamp = a.timestamp + 30_000) // mesmo minuto, mesma quantidade
        val other = a.copy(quantity = "0.2")
        val rows = listOf(a, sameDifferentScale, other).mapIndexed { i, r -> ParsedRow(i, r, emptyList(), emptyList(), emptyMap()) }
        val existing = setOf(Fingerprint.of(other))
        val m = DuplicateDetector.mark(rows, existing)
        assertFalse(m[0].duplicateInFile || m[0].duplicateOfExisting)
        assertTrue(m[1].duplicateInFile)
        assertTrue(m[2].duplicateOfExisting)
    }

    @Test fun duplicates_byTxHash() {
        val a = TxRecord(1, "BUY", "BTC", "1", "1", txHash = "0xABC")
        val b = TxRecord(999999999, "BUY", "BTC", "2", "5", txHash = "0xabc")
        assertEquals(Fingerprint.of(a), Fingerprint.of(b))
    }

    @Test fun decimalsParse_formats() {
        val p = com.coinportracker.core.Decimals::parse
        assertEquals("1234.56", p("1.234,56")!!.toPlainString())
        assertEquals("1234.56", p("1,234.56")!!.toPlainString())
        assertEquals("0.5", p("0,5")!!.toPlainString())
        assertEquals("1000000", p("1.000.000")!!.toPlainString())
        assertEquals("12.5", p("R$ 12,50")!!.stripTrailingZeros().toPlainString())
        assertNull(p("abc"))
    }
}
