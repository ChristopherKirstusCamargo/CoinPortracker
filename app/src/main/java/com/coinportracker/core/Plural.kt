package com.coinportracker.core

/** Plural pt-BR simples: singular só para 1 (e −1). "0 ligadas", "1 ligada", "4 ligadas". */
object Plural {
    fun of(n: Int, singular: String, plural: String): String = "$n " + if (n == 1 || n == -1) singular else plural
}
