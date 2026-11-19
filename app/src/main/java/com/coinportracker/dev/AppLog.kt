package com.coinportracker.dev

import java.util.ArrayDeque

data class LogLine(val t: Long, val tag: String, val msg: String)

/** Anel em memória (sem disco, sem rede). Endereços são redigidos na leitura, não na gravação. */
object AppLog {
    private const val CAP = 400
    private val buf = ArrayDeque<LogLine>(CAP)
    @Volatile var listeners: () -> Unit = {}

    fun add(tag: String, msg: String) {
        synchronized(buf) {
            if (buf.size >= CAP) buf.removeFirst()
            buf.addLast(LogLine(System.currentTimeMillis(), tag, msg))
        }
    }

    fun lines(redact: Boolean): List<LogLine> = synchronized(buf) {
        buf.map { if (redact) it.copy(msg = DevValidator.redact(it.msg)) else it }
    }

    fun clear() { synchronized(buf) { buf.clear() } }
}
