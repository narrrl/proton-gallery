package io.narl.protongallery

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
fun currentActivity(state: LibraryState, account: AccountState, backup: BackupState): Activity? {
    val number = NumberFormat.getIntegerInstance()
    val progress = backup.progress
    return when {
        !state.ready && !state.error -> Activity("Opening your library")
        state.preparing != null -> Activity(state.preparing)
        progress.uploading -> Activity("Backing up ${minOf(progress.done + 1, progress.total)} of ${number.format(progress.total)}", progress.fraction)
        state.importing -> Activity(state.progress ?: "Saving on this device")
        account.driveSync != null -> Activity("Reading ${account.driveSync}" +
            if (account.driveIndexed > 0) ": ${number.format(account.driveIndexed)} found" else "")
        account.syncing -> Activity("Updating Proton Photos" + if (account.indexed > 0) ": ${number.format(account.indexed)} found" else "")
        progress.checking -> Activity(if (progress.toCheck > 0) "Comparing with Proton: ${number.format(progress.toCheck)} left"
            else "Comparing with Proton Photos", progress.fraction)
        progress.scanning -> Activity("Reading this phone’s photos")
        else -> null
    }
}

/** The thin bar under a top bar. It keeps its 2 dp so nothing shifts when work starts. */
@Composable
fun ActivityBar(activity: Activity?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.fillMaxWidth().height(2.dp)) {
        when {
            activity == null -> Unit
            activity.fraction != null -> LinearProgressIndicator({ activity.fraction }, Modifier.fillMaxSize(), color = color,
                trackColor = Color.Transparent, gapSize = 0.dp, drawStopIndicator = {})
            else -> LinearProgressIndicator(Modifier.fillMaxSize(), color = color, trackColor = Color.Transparent, gapSize = 0.dp)
        }
    }
}
