package com.coinportracker.core

/** Resultado explícito para operações que podem falhar (rede, parsing, IO). Nunca esconder erros. */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val message: String, val cause: Throwable? = null, val retryable: Boolean = false) : Outcome<Nothing>()

    fun getOrNull(): T? = (this as? Ok)?.value
    inline fun <R> map(f: (T) -> R): Outcome<R> = when (this) {
        is Ok -> Ok(f(value))
        is Err -> this
    }
}

/**
 * Como runCatching, mas nunca engole o cancelamento de coroutine: sair da tela ou trocar o ciclo de
 * atualização interrompe de verdade os laços de failover em vez de seguir para o próximo endpoint.
 */
inline fun <T> catchingNonCancel(block: () -> T): Result<T> = try { Result.success(block()) }
    catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
    catch (e: Throwable) { Result.failure(e) }
