package com.coinportracker.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Navegação entre abas (v3).
 *
 * Causa raiz do bug "Início às vezes não faz nada" (v2):
 *  1. Tocar em Início estando no Início era ignorado (`if (!sel)`): rolado para baixo, o toque não
 *     fazia nada — agora re-tocar uma aba rola até o topo.
 *  2. Vários atalhos internos ("Ver todos", cartão de P&L, "Ver ativos" em Carteiras) empilhavam as
 *     abas com `navigate("assets")` simples. A pilha ficava [home, carteiras, assets]; ao tocar
 *     Início com saveState/restoreState o NavController salvava essa pilha mista e, ao voltar a uma
 *     aba, restaurava telas internas por baixo (e o Início podia "não responder" enquanto a pilha
 *     restaurada era reapresentada). Agora todos os caminhos usam [switchTab]: Início = volta à
 *     raiz do grafo (pop até o início, salvando o estado das outras abas); outras abas = padrão
 *     oficial (popUpTo(startDestination) + saveState + restoreState + singleTop).
 */
object TabNav {
    private val _reselect = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emite a rota da aba quando o usuário toca na aba que já está aberta (rolar ao topo). */
    val reselect: SharedFlow<String> = _reselect.asSharedFlow()

    fun isTab(route: String?) = Tab.entries.any { it.route == route }

    /** Troca de aba de forma determinística. Retorna true se navegou, false se foi "re-toque". */
    fun switchTab(nav: NavController, route: String): Boolean {
        val current = nav.currentDestination?.route
        if (current == route) { _reselect.tryEmit(route); return false }
        if (route == Tab.HOME.route) {
            // volta à raiz: tira tudo que está acima do Início (salvando o estado das outras abas)
            val popped = nav.popBackStack(Tab.HOME.route, inclusive = false, saveState = true)
            if (!popped || nav.currentDestination?.route != Tab.HOME.route) {
                nav.navigate(Tab.HOME.route) { popUpTo(nav.graph.findStartDestination().id) { inclusive = true }; launchSingleTop = true }
            }
            return true
        }
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        return true
    }
}

/** Rola a lista ao topo quando a aba [route] é tocada de novo. */
@androidx.compose.runtime.Composable
fun ScrollToTopOnReselect(route: String, state: androidx.compose.foundation.lazy.LazyListState) {
    androidx.compose.runtime.LaunchedEffect(route, state) {
        TabNav.reselect.collect { r -> if (r == route) { if (state.firstVisibleItemIndex > 12) state.scrollToItem(6); state.animateScrollToItem(0) } }
    }
}
