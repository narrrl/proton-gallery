package io.narl.protongallery

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.narl.protongallery.bridge.SessionVault
import io.narl.protongallery.bridge.GalleryException
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens and derived unlock keys are encrypted with a non-exportable device key.
 * AtomicFile prevents torn refresh-token writes; both key and blob are excluded from backup.
 */
class SessionStorage(context: Context,
    private val filename: String = "proton-session.enc",
    private val alias: String = "proton-gallery.session.v1",
) : SessionVault {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))
    private val aad = "proton-gallery/session/v1".toByteArray(Charsets.UTF_8)
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Session key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized override fun load(): ByteArray? = secure {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@secure null
        check(file.baseFile.length() <= 1_048_576)
        val encoded = file.readFully()
        try {
            check(encoded.size in 29..1_048_576 && encoded[0] == 1.toByte())
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
                updateAAD(aad)
                doFinal(encoded, 13, encoded.size - 13)
            }
        } finally { encoded.fill(0) }
    }
    @Synchronized override fun save(bytes: ByteArray) = secure {
        try {
            check(bytes.size <= 1_048_000)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key(true)); updateAAD(aad)
            }
            val encrypted = cipher.doFinal(bytes)
            val output = file.startWrite()
            try {
                output.write(1); output.write(cipher.iv); output.write(encrypted)
                file.finishWrite(output)
            } catch (error: Exception) { file.failWrite(output); throw error }
            finally { encrypted.fill(0) }
        } finally { bytes.fill(0) }
    }
    @Synchronized override fun clear() = secure {
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
    }
    private inline fun <T> secure(block: () -> T): T = try { block() }
    catch (_: Exception) { throw GalleryException.Operation("Secure session storage is unavailable.") }
}

object PlatformTls {
    private var ready = false
    @Synchronized fun ensure(context: Context) {
        if (ready) return
        System.loadLibrary("pg_mobile")
        check(initialize(context.applicationContext)) { "Android certificate verification could not start" }
        ready = true
    }
    private external fun initialize(context: Context): Boolean
}
