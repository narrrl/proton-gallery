package io.narl.protongallery

import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/**
 * What the app is doing in the background, as one short line. Every screen
 * shows work the same way: a thin bar under the top bar, a ring around the
 * account button, and this line beside it. `fraction` is set when the share
 * done is known; otherwise the bar and ring keep moving.
 */
data class Activity(val label: String, val fraction: Float? = null)

/** The most important thing going on, or null when the app is idle. */
fun currentActivity(state: LibraryState, account: AccountState, backup: BackupState, section: GallerySection,
    sync: SyncProgress = SyncProgress()): Activity? {
    val number = NumberFormat.getIntegerInstance()
    val progress = backup.progress
    return when {
        !state.ready && !state.error -> Activity("Opening your library")
        state.preparing != null -> Activity(state.preparing)
        progress.uploading && backup.request?.running == true -> Activity("Backing up ${minOf(progress.done + 1, progress.total)} of ${number.format(progress.total)}", progress.fraction)
        state.importing && section == GallerySection.Device -> Activity(state.progress ?: "Saving on this device")
        account.driveSync != null && section == GallerySection.Drive -> Activity("Reading ${account.driveSync}" +
            if (sync.driveIndexed > 0) ": ${number.format(sync.driveIndexed)} found" else "")
        account.syncing && section in listOf(GallerySection.Photos, GallerySection.Albums) ->
            Activity("Updating Proton Photos" + if (sync.indexed > 0) ": ${number.format(sync.indexed)} found" else "")
        else -> null
    }
}

/**
 * The thin bar under a top bar. It keeps its 2 dp so nothing shifts when work starts.
 *
 * Known progress is drawn in the suite's violet unless a [color] is asked for —
 * the viewer asks for white, over a photo.
 */
@Composable
fun ActivityBar(activity: Activity?, modifier: Modifier = Modifier, color: Color? = null) {
    val accent = LocalBrand.current.accent
    Box(modifier.fillMaxWidth().height(2.dp)) {
        when {
            activity == null -> Unit
            // The same semantics Material's bar reports, so TalkBack still reads a percentage.
            activity.fraction != null && color == null -> Canvas(Modifier.fillMaxSize().progressSemantics(activity.fraction.coerceIn(0f, 1f))) {
                drawRect(accent, size = Size(size.width * activity.fraction.coerceIn(0f, 1f), size.height))
            }
            activity.fraction != null -> LinearProgressIndicator({ activity.fraction }, Modifier.fillMaxSize(), color = color ?: MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent, gapSize = 0.dp, drawStopIndicator = {})
            else -> LinearProgressIndicator(Modifier.fillMaxSize(), color = color ?: MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent, gapSize = 0.dp)
        }
    }
}
