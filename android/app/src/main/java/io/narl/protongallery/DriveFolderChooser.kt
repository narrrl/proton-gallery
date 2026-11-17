package io.narl.protongallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.narl.protongallery.bridge.DriveFolder
import io.narl.protongallery.icons.ChevronRight
import io.narl.protongallery.icons.FolderOpen

/** Browse My files and pick a folder to add as a gallery. */
@Composable
fun DriveFolderChooser(
    state: ChooserState,
    onOpen: (DriveFolder) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAdd: (DriveFolder) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onBack)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.trail.isNotEmpty()) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to parent folder") }
                    Text(if (state.trail.isEmpty()) "My files" else state.folder?.name ?: "", Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
                }
                Text("Choose a folder to show as a gallery. Every folder inside it becomes an album.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error, style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
                        }
                        state.children.isEmpty() -> Text("No folders inside", Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(state.children, key = { it.id }) { folder ->
                                ListItem(
                                    headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = if (folder.isGallery) {{ Text("Already a gallery") }} else null,
                                    leadingContent = { Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
                                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                    modifier = Modifier.clickable { onOpen(folder) },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                                )
                            }
                        }
                    }
                }
                val folder = state.folder
                val addable = folder != null && state.trail.isNotEmpty() && !folder.isGallery && !state.loading
                Surface(tonalElevation = 2.dp) {
                    Button(onClick = { folder?.let(onAdd) }, enabled = addable,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                        Text(when {
                            folder == null || state.trail.isEmpty() -> "Open a folder to add it"
                            folder.isGallery -> "Already a gallery"
                            else -> "Add “${folder.name}” as a gallery"
                        }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
