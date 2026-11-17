package io.narl.protongallery

import io.narl.protongallery.bridge.GalleryEngine
import io.narl.protongallery.bridge.GalleryException
import io.narl.protongallery.bridge.LibraryView
import io.narl.protongallery.bridge.planSmartGrid
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Loads the actual host cdylib through generated Kotlin and JNA, not a fake engine. */
class BridgeTest {
    @Test fun secure_vault_callbacks_restore_offline_and_sanitize_unexpected_platform_errors() {
        val dir = Files.createTempDirectory("gallery-vault-ffi").toFile()
        var broken = true
        val vault = object : io.narl.protongallery.bridge.SessionVault {
            override fun load(): ByteArray? {
                if (broken) throw IllegalStateException("platform-private-detail")
                return """{"version":1,"session_id":"fixture-session","username":"fixture-user","user_id":"fixture-account","access_token":"fixture-access","refresh_token":"fixture-refresh","scopes":["full"],"password_mode":1,"key_passphrases":{"fixture-key":"fixture-derived-key"}}""".toByteArray()
            }
            override fun save(bytes: ByteArray) { error("No network requests or refreshes are expected") }
            override fun clear() {}
        }
        try {
            GalleryEngine.openWithVault(dir.path, vault).use { engine ->
                val error = assertThrows(GalleryException.Operation::class.java) { engine.restoreSession() }
                assertFalse(error.detail.contains("platform-private-detail"))
                broken = false
                engine.restoreSession()
                assertEquals(io.narl.protongallery.bridge.SignInStage.CONNECTED, engine.accountStatus().stage)
                assertEquals("fixture-user", engine.accountStatus().username)
                assertTrue(engine.pageAt(LibraryView.Photos, null, false, 90u).items.isEmpty())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun generated_bindings_roundtrip_imports_pages_errors_and_restart() {
        val dir = Files.createTempDirectory("gallery-ffi").toFile()
        try {
            GalleryEngine.open(dir.path).use { engine ->
                repeat(19) { index ->
                    val staged = File(engine.incomingDirectory(), "selected-$index")
                    staged.writeBytes(ByteArray(128) { (it + index).toByte() })
                    engine.importFile(staged.path, "Photo $index.jpg", "image/jpeg", 42)
                }
                assertEquals(19uL, engine.totals().count)
                assertEquals(0uL, engine.countAt(LibraryView.Photos))
                assertTrue(engine.pageAt(LibraryView.Photos, null, false, 90u).items.isEmpty())
                assertThrows(GalleryException::class.java) { engine.pageAt(LibraryView.DriveAlbum("missing", "missing"), null, false, 90u) }
                val layout = planSmartGrid(listOf(1.5, 0.75, 1.5, 0.75), 412u, 140u, 3u)
                assertEquals(4, layout.sumOf { it.tiles.size })
                assertTrue(layout.first().tiles.first().width > layout.first().tiles[1].width)
                val first = engine.page(null, false, 7u)
                val second = engine.page(first.next, false, 7u)
                val back = engine.page(second.previous, true, 7u)
                assertEquals(first.items.map { it.id }, back.items.map { it.id })
                assertNull(back.previous)
                assertTrue(first.items.all { File(it.localPath).length() == 128L })
                assertThrows(GalleryException::class.java) { engine.page(null, false, 0u) }
            }
            GalleryEngine.open(dir.path).use { reopened ->
                assertEquals(19uL, reopened.totals().count)
                assertEquals(19, reopened.page(null, false, 90u).items.size)
            }
        } finally { dir.deleteRecursively() }
    }
}
