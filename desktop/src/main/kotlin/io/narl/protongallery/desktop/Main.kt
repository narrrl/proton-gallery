package io.narl.protongallery.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.narl.protongallery.bridge.GalleryEngine
import io.narl.protongallery.bridge.SignInStage
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Threads for calls into Rust. Each call blocks on async Rust code, which
 * needs more stack than the JVM gives pooled threads by default.
 */
private val Native = run {
    val count = AtomicInteger()
    Executors.newFixedThreadPool(4) { task ->
        Thread(null, task, "pg-native-${count.incrementAndGet()}", 8L shl 20).apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/** Where the catalog lives: XDG data home on Linux, local app data on Windows. */
fun dataDirectory(): File {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val base = if (windows) {
        System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Local")
    } else {
        System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")
    }
    return File(base, if (windows) "ProtonGallery" else "proton-gallery").apply { mkdirs() }
}

private sealed interface Startup {
    data object Opening : Startup
    data class Ready(val items: ULong, val stage: SignInStage) : Startup
    data class Failed(val reason: String) : Startup
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Proton Gallery", state = rememberWindowState(width = 1100.dp, height = 760.dp)) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) { Skeleton() }
        }
    }
}

/**
 * Placeholder until the shared Compose UI arrives (docs/DESKTOP.md, step 2):
 * proves the window, the native library and the catalog open on this computer.
 */
@Composable
private fun Skeleton() {
    var startup by remember { mutableStateOf<Startup>(Startup.Opening) }
    LaunchedEffect(Unit) {
        startup = try {
            withContext(Native) {
                // No session vault yet: sign-ins last until the app closes.
                val engine = GalleryEngine.open(dataDirectory().path)
                engine.restoreSession()
                Startup.Ready(engine.totals().count, engine.accountStatus().stage)
            }
        } catch (error: Exception) {
            Startup.Failed(error.message ?: error.javaClass.simpleName)
        }
    }
    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Proton Gallery", style = MaterialTheme.typography.headlineMedium)
        Text("Desktop preview. The gallery itself is not here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val state = startup) {
            Startup.Opening -> Text("Opening the library…")
            is Startup.Ready -> {
                Text("Library: ${dataDirectory().path}")
                Text("Items in this computer's library: ${state.items}")
                Text(if (state.stage == SignInStage.CONNECTED) "Signed in to Proton" else "Not signed in")
            }
            is Startup.Failed -> Text("Couldn’t open the library: ${state.reason}", color = MaterialTheme.colorScheme.error)
        }
    }
}
