package com.coinportracker

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.coinportracker.security.AppLock
import com.coinportracker.ui.AppRoot
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.theme.CoinPortrackerTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    companion object { @Volatile private var processAlive = false }

    private val vm: MainViewModel by viewModels()
    private val locked = mutableStateOf(true)
    /** Só compõe o app depois de ler as configurações: sem "piscar" saldos antes de saber se o bloqueio está ligado. */
    private val settingsLoaded = mutableStateOf(false)
    private val lockOn = mutableStateOf(false)
    private var backgroundedAt = 0L
    private var lockEnabled = false
    private var autoLockMs = 60_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighestRefreshRate()
        // Recriação no mesmo processo (rotação, tema) mantém o estado; depois de morte do processo
        // o app sempre volta bloqueado (o "desbloqueado" salvo no Bundle não vale num processo novo).
        locked.value = if (processAlive) savedInstanceState?.getBoolean("locked") ?: true else true
        processAlive = true
        // Padrão seguro até as configurações carregarem (FLAG_SECURE vem ligado por padrão nas configurações).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)

        lifecycleScope.launch {
            vm.c.settings.settings.collect { st ->
                lockEnabled = st.biometricLock; autoLockMs = st.autoLockSeconds * 1000L
                lockOn.value = st.biometricLock
                if (!st.biometricLock) locked.value = false
                if (st.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                // Com o bloqueio ligado, a miniatura em "recentes" nunca mostra saldos (mesmo com capturas permitidas). API 33+.
                if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!(st.biometricLock || st.secureScreen))
                settingsLoaded.value = true
            }
        }
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_START -> vm.setForeground(true); Lifecycle.Event.ON_STOP -> vm.setForeground(false); else -> Unit }
        })

        setContent {
            val st by vm.hub.collectAsStateWithLifecycle()
            CoinPortrackerTheme(st.settings.theme, reduceMotion = st.settings.lowPower, lowPower = st.settings.lowPower, anim = st.settings.anim) {
                val isLocked by locked
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    // o estado de bloqueio vem direto das configurações (o hub pode emitir antes com valores padrão)
                    if (!settingsLoaded.value) Unit
                    else if (isLocked && lockOn.value) LockScreen { authenticate() } else AppRoot(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (lockEnabled && backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt >= autoLockMs) locked.value = true
        backgroundedAt = 0L
    }

    override fun onStop() { super.onStop(); if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime() }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putBoolean("locked", locked.value) }

    private fun authenticate() {
        AppLock.unavailableReason(this)?.let { reason ->
            // Sem nenhuma credencial no aparelho não há como autenticar: desliga o bloqueio e avisa.
            vm.updateSettings { it.copy(biometricLock = false) }; vm.toast("Bloqueio desativado: $reason"); locked.value = false; return
        }
        AppLock.prompt(this, onSuccess = { locked.value = false }, onError = { vm.toast(it) })
    }

    /** Pede o modo de tela com maior taxa de atualização na resolução atual (120/144 Hz quando disponível). */
    private fun requestHighestRefreshRate() {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        display ?: return
        val cur = display.mode
        val best = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("CoinPortracker bloqueado", style = MaterialTheme.typography.titleLarge)
        Text("Seus dados ficam criptografados neste aparelho.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Text("Desbloquear") }
    }
}
