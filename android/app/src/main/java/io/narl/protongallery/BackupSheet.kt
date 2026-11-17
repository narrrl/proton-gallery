package io.narl.protongallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.PhoneFolder
import io.narl.protongallery.icons.CloudUpload

/** What the backup controls can do; defaults keep previews and tests short. */
class BackupActions(
    val allowAccess: () -> Unit = {},
    val setFolder: (PhoneFolder, Boolean) -> Unit = { _, _ -> },
    val setWifiOnly: (Boolean) -> Unit = {},
    val setChargingOnly: (Boolean) -> Unit = {},
    val backUpNow: () -> Unit = {},
    val upload: (List<GalleryItem>) -> Unit = {},
)

fun backupSummary(backup: BackupState): String {
    val progress = backup.progress
    return when {
        !backup.access -> "Allow access to see and back up this phone’s photos"
        progress.running && progress.checking || backup.checking -> "Checking which photos are backed up…"
        progress.running -> "Backing up ${progress.done + 1} of ${progress.total}"
        progress.message != null -> progress.message
        backup.automatic -> {
            val waiting = backup.folders.filter { it.backup }.sumOf { it.notBackedUp.toLong() }
            if (waiting == 0L) "Backup is on. Everything is backed up." else "Backup is on. $waiting waiting."
        }
        else -> "Off. Choose folders to back up."
    }
}

/** Above the Photos timeline: phone access, and backup progress. */
@Composable
fun PhotosHeader(backup: BackupState, onAllow: () -> Unit) {
    val progress = backup.progress
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
        if (progress.running || backup.checking) {
            Text(backupSummary(backup), Modifier.padding(top = 8.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress.running && !progress.checking && progress.total > 0) {
                val current = if (progress.size > 0) progress.bytes.toFloat() / progress.size else 0f
                LinearProgressIndicator({ ((progress.done + current.coerceIn(0f, 1f)) / progress.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/** Replaces the top bar while items are selected. */
@Composable
fun SelectionBar(selected: List<GalleryItem>, onClose: () -> Unit, onBackUp: () -> Unit) {
    val waiting = selected.count { it.phoneId != null && !it.backedUp }
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Clear selection") }
        Text(if (selected.size == 1) "1 selected" else "${selected.size} selected", Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FilledTonalButton(onClick = onBackUp, enabled = waiting > 0) {
            Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (waiting > 0) "Back up $waiting" else "Backed up")
        }
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
                Text("New photos and videos in the folders you choose go to Proton Photos, end-to-end encrypted. Nothing is deleted from the phone.",
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
                        enabled = backup.automatic && !backup.progress.running) {
                        Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Back up now")
                    }
                }
            }
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
