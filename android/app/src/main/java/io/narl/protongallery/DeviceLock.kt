package io.narl.protongallery

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.provider.Settings

/**
 * The phone's own lock (fingerprint, face or screen lock) guards private
 * parts of the app, such as hidden albums. Nothing is stored: each unlock
 * lasts until the app goes to the background.
 */
object DeviceLock {
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** The phone has a screen lock the app can ask for. */
    fun available(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Ask for the phone's lock; `onResult(true)` once the user is confirmed. */
    fun unlock(context: Context, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        })
    }

    /** Where the user sets up a screen lock. */
    fun setupIntent(): Intent = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
        .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, AUTHENTICATORS)
}
