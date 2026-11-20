package com.coinportracker.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Regressão do bug "botão Início às vezes não faz nada" (v2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TabNavTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav: NavHostController

    private fun setUp() {
        rule.setContent {
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            Column {
                NavHost(nav, startDestination = "home") {
                    Tab.entries.forEach { t -> composable(t.route) { Text("tela ${t.route}") } }
                    composable("wallets2") { Text("tela carteiras") }
                    composable("asset2") { Text("tela ativo") }
                }
                if (TabNav.isTab(entry?.destination?.route)) BottomBar(nav, entry?.destination?.route)
            }
        }
        rule.waitForIdle()
    }

    private fun stack() = nav.currentBackStack.value.map { it.destination }.filter { it !is NavGraph }.map { it.route }

    @Test fun inicioFromMixedStackAlwaysGoesHome() {
        setUp()
        // Início → Carteiras (tela interna) → "Ver ativos" → Análise → Início
        rule.runOnIdle { nav.navigate("wallets2") }
        rule.runOnIdle { TabNav.switchTab(nav, "assets") }
        rule.runOnIdle { TabNav.switchTab(nav, "analysis") }
        rule.onNodeWithText("Início").performClick()
        rule.runOnIdle { assertEquals("home", nav.currentDestination?.route); assertEquals(listOf("home"), stack()) }
        rule.onNodeWithText("tela home").assertExists()
    }

    @Test fun clickingInicioInBottomBarWorksFromEveryTab() {
        setUp()
        for (t in Tab.entries.filter { it != Tab.HOME }) {
            rule.onNodeWithText(t.label).performClick()
            rule.runOnIdle { assertEquals(t.route, nav.currentDestination?.route) }
            rule.onNodeWithText("Início").performClick()
            rule.runOnIdle { assertEquals("home", nav.currentDestination?.route); assertEquals(listOf("home"), stack()) }
        }
    }

    @Test fun reselectingInicioAsksToScrollToTop() {
        setUp()
        val got = runBlocking {
            val w = async(start = CoroutineStart.UNDISPATCHED) { TabNav.reselect.first() }
            assertFalse(TabNav.switchTab(nav, "home"))
            w.await()
        }
        assertEquals("home", got)
    }

    @Test fun randomTapSequencesNeverBreakInicio() {
        setUp()
        val rnd = Random(42)
        val targets = Tab.entries.map { it.route } + listOf("wallets2", "asset2")
        repeat(150) {
            val r = targets[rnd.nextInt(targets.size)]
            rule.runOnIdle { if (TabNav.isTab(r)) TabNav.switchTab(nav, r) else nav.navigate(r) }
            if (rnd.nextInt(4) == 0) {
                rule.runOnIdle { TabNav.switchTab(nav, "home") }
                rule.runOnIdle { assertEquals(listOf("home"), stack()) }
            }
        }
    }
}
