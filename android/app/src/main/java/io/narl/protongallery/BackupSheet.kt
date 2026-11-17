package io.narl.protongallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.text.format.Formatter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.PhoneFolder
import io.narl.protongallery.icons.CloudUpload
import io.narl.protongallery.icons.LibraryAdd

/** What the backup controls can do; defaults keep previews and tests short. */
class BackupActions(
    val allowAccess: () -> Unit = {},
    val setFolder: (PhoneFolder, Boolean) -> Unit = { _, _ -> },
    val setWifiOnly: (Boolean) -> Unit = {},
    val setChargingOnly: (Boolean) -> Unit = {},
    val backUpNow: () -> Unit = {},
    val upload: (List<GalleryItem>) -> Unit = {},
    val setKeepDays: (Int) -> Unit = {},
    val freeUp: () -> Unit = {},
)

/** Free up space keeps this many recent days of media on the phone. */
val KEEP_CHOICES = listOf(30 to "30 days", 90 to "3 months", 365 to "1 year")

fun backupSummary(backup: BackupState): String {
    val progress = backup.progress
    return when {
        !backup.access -> "Allow access to see and back up this phone’s photos"
        progress.uploading -> "Backing up ${minOf(progress.done + 1, progress.total)} of ${progress.total}"
        progress.checking -> if (progress.toCheck > 0) "Comparing with Proton Photos: ${progress.toCheck} left" else "Comparing with Proton Photos…"
        progress.scanning -> "Reading this phone’s photos…"
        progress.message != null -> progress.message
        backup.automatic -> {
            val waiting = backup.folders.filter { it.backup }.sumOf { it.notBackedUp.toLong() }
            if (waiting == 0L) "Backup is on. Everything is backed up." else "Backup is on. $waiting waiting."
        }
        else -> "Off. Choose folders to back up."
    }
}

/** Above the Photos timeline: access to the phone's photos. Progress shows in the top bar. */
@Composable
fun PhotosHeader(backup: BackupState, onAllow: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (!backup.access) Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("See this phone’s photos here", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Your phone’s photos and Proton Photos in one timeline. Back them up when you want; they’re end-to-end encrypted before they leave the phone.",
                    Modifier.padding(top = 6.dp, bottom = 14.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Button(onClick = onAllow) { Text("Allow access") }
            }
        } else if (backup.partial) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Showing only the photos you selected", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAllow) { Text("Change") }
        }
    }
}

/** Replaces the top bar while items are selected. */
@Composable
fun SelectionBar(selected: List<GalleryItem>, onClose: () -> Unit, onBackUp: () -> Unit, onShare: () -> Unit,
    onDelete: (() -> Unit)? = null, onAddToAlbum: (() -> Unit)? = null, onRemoveFromAlbum: (() -> Unit)? = null) {
    val waiting = selected.count { it.phoneId != null && !it.backedUp }
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Clear selection") }
        Text(if (selected.size == 1) "1 selected" else "${selected.size} selected", Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share") }
        if (onRemoveFromAlbum != null) TextButton(onClick = onRemoveFromAlbum) { Text("Remove") }
        if (onAddToAlbum != null) IconButton(onClick = onAddToAlbum) { Icon(Icons.Outlined.LibraryAdd, "Add to album") }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete") }
        if (waiting > 0) FilledTonalButton(onClick = onBackUp) {
            Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back up $waiting")
        }
    }
}

/** Another app asked for photos: what to choose, and the way back. */
@Composable
fun PickBar(multiple: Boolean, selected: Int, onCancel: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel") }
        Text(when {
            !multiple -> "Choose a photo or video"
            selected == 0 -> "Choose photos and videos"
            else -> "$selected selected"
        }, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (multiple) Button(onClick = onDone, enabled = selected > 0) { Text("Add") }
    }
}

/** Automatic backup: which folders, and when. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSheet(backup: BackupState, connected: Boolean, actions: BackupActions, onSignIn: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Back up this phone", style = MaterialTheme.typography.headlineSmall)
                Text("New photos and videos in the folders you choose go to Proton Photos, end-to-end encrypted. Nothing is deleted from the phone unless you free up space below.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(backupSummary(backup), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            when {
                !connected -> Button(onClick = onSignIn, Modifier.padding(24.dp)) { Text("Sign in to Proton") }
                !backup.access -> Button(onClick = actions.allowAccess, Modifier.padding(24.dp)) { Text("Allow access to photos") }
                else -> {
                    Text("Folders", Modifier.padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                    if (backup.folders.isEmpty()) Text("No photos found on this phone yet.", Modifier.padding(horizontal = 24.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (folder in backup.folders) SwitchRow(folder.name, folderCaption(folder), folder.backup) { actions.setFolder(folder, it) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow("Only on Wi‑Fi", "Mobile data is never used for automatic backup", backup.wifiOnly, actions.setWifiOnly)
                    SwitchRow("Only while charging", null, backup.chargingOnly, actions.setChargingOnly)
                    Button(onClick = actions.backUpNow, Modifier.padding(horizontal = 24.dp).padding(top = 16.dp),
                        enabled = backup.automatic && !backup.progress.uploading) {
                        Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Back up now")
                    }
                    HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    FreeUpSection(backup, actions)
                }
            }
        }
    }
}

/**
 * Removes phone copies of photos that are safe in Proton Photos. Recent ones
 * stay, so the camera roll and other apps' photo pickers keep them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreeUpSection(backup: BackupState, actions: BackupActions) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text("Free up space", style = MaterialTheme.typography.titleSmall)
        Text("Remove photos and videos from this phone once they are safely in Proton Photos. Each one is checked with Proton first, and Android asks you before anything is deleted.",
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Keep on this phone the last", Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            KEEP_CHOICES.forEachIndexed { index, (days, label) ->
                SegmentedButton(selected = backup.keepDays == days, onClick = { actions.setKeepDays(days) },
                    shape = SegmentedButtonDefaults.itemShape(index, KEEP_CHOICES.size)) { Text(label) }
            }
        }
        val freeable = backup.freeable
        Text(when {
            freeable == null -> "Sign in to see what can be removed."
            freeable.count == 0uL -> "Nothing to remove yet. Older photos appear here once they’re backed up."
            else -> "${if (freeable.count == 1uL) "1 item" else "${freeable.count} items"} older than that are backed up: " +
                Formatter.formatShortFileSize(context, freeable.bytes.toLong())
        }, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = actions.freeUp, Modifier.padding(top = 12.dp), enabled = (freeable?.count ?: 0uL) > 0uL) {
            Text(if (freeable != null && freeable.count > 0uL) "Free up ${Formatter.formatShortFileSize(context, freeable.bytes.toLong())}" else "Free up space")
        }
    }
}

fun folderCaption(folder: PhoneFolder): String {
    val items = if (folder.count == 1uL) "1 item" else "${folder.count} items"
    return if (folder.notBackedUp == 0uL) "$items · all backed up" else "$items · ${folder.notBackedUp} not backed up"
}

@Composable
private fun SwitchRow(title: String, caption: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = caption?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.padding(horizontal = 8.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
