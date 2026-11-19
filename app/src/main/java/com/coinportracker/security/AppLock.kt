package com.coinportracker.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Bloqueio do app: biometria com fallback para a credencial do aparelho (PIN/padrão/senha).
 * Combinações suportadas pela androidx.biometric: STRONG|DEVICE_CREDENTIAL só a partir da API 30;
 * abaixo disso usa WEAK|DEVICE_CREDENTIAL (que inclui o fallback via KeyguardManager).
 */
object AppLock {
    val authenticators: Int get() = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** null se disponível; senão o motivo legível. */
    fun unavailableReason(ctx: Context): String? = when (BiometricManager.from(ctx).canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Configure um bloqueio de tela (PIN, padrão, senha ou biometria) no aparelho para usar o bloqueio do app."
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "O aparelho exige uma atualização de segurança para autenticação."
        else -> "Autenticação indisponível neste aparelho."
    }

    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear CoinPortracker")
            .setSubtitle("Use biometria ou o bloqueio de tela do aparelho")
            .setAllowedAuthenticators(authenticators)
            .build())
    }
}
