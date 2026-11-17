package io.narl.protongallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.narl.protongallery.bridge.GalleryEngine
import io.narl.protongallery.bridge.GalleryException
import io.narl.protongallery.bridge.SignInStage
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a unique probe blob/key and catalog. Never opens the real account or library. */
@RunWith(AndroidJUnit4::class)
class SessionStorageDeviceTest {
    @Test fun keystore_vault_is_authenticated_atomic_and_reopens_through_the_rust_callback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = "vault-probe-${UUID.randomUUID()}"
        val blob = File(context.noBackupFilesDir, "$probe.enc")
        val root = File(context.noBackupFilesDir, probe)
        val storage = SessionStorage(context, blob.name, probe)
        try {
            PlatformTls.ensure(context)
            assertNull(storage.load())
            val fixture = """{"version":1,"session_id":"fixture-session","username":"fixture-user","user_id":"fixture-account","access_token":"fixture-access","refresh_token":"fixture-refresh","scopes":["full"],"password_mode":1,"key_passphrases":{"fixture-key":"fixture-derived-key"}}""".toByteArray()
            storage.save(fixture.copyOf())
            val firstCiphertext = blob.readBytes()
            assertFalse(firstCiphertext.toString(Charsets.UTF_8).contains("fixture-refresh"))
            assertArrayEquals(fixture, SessionStorage(context, blob.name, probe).load())
            storage.save(fixture.copyOf())
            assertFalse(firstCiphertext.contentEquals(blob.readBytes())) // fresh GCM nonce
            GalleryEngine.openWithVault(root.path, storage).use { engine ->
                engine.restoreSession() // SDK construction and Kotlin callback, no network
                assertEquals(SignInStage.CONNECTED, engine.accountStatus().stage)
                assertEquals("fixture-user", engine.accountStatus().username)
            }
            val tampered = blob.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            blob.writeBytes(tampered)
            assertThrows(GalleryException.Operation::class.java) { storage.load() }
            assertTrue(blob.exists()) // corruption is reported, never silently discarded
            storage.clear()
            assertNull(storage.load())
        } finally {
            storage.clear()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(probe) }
            root.deleteRecursively()
        }
    }
}
