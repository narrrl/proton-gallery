package io.narl.protongallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.narl.protongallery.bridge.AlbumOptions
import io.narl.protongallery.bridge.AlbumTag
import io.narl.protongallery.bridge.FolderAlbum

private const val MAX_TAGS = 20

/**
 * Local presentation settings of a Drive album: name, description, cover,
 * tags, pinning and hiding. Nothing here changes the folder in Drive. Order is
 * newest first; date headings follow the grid's zoom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumOptionsSheet(
    options: AlbumOptions,
    album: FolderAlbum?,
    knownTags: List<AlbumTag>,
    onSave: (AlbumOptions) -> Unit,
    onChooseCover: () -> Unit,
    onRemoveGallery: () -> Unit,
    onDismiss: () -> Unit,
    /** A Proton Photos album: its name is renamed in Proton, and it can be deleted. */
    proton: Boolean = false,
    onDeleteAlbum: () -> Unit = {},
) {
    var title by rememberSaveable { mutableStateOf(options.title ?: "") }
    var description by rememberSaveable { mutableStateOf(options.description ?: "") }
    var pinned by rememberSaveable { mutableStateOf(options.pinned) }
    var hidden by rememberSaveable { mutableStateOf(options.hidden) }
    var resetCover by rememberSaveable { mutableStateOf(false) }
    var tags by rememberSaveable { mutableStateOf(options.tags) }
    var newTag by rememberSaveable { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    fun edited() = options.copy(
        title = title.trim().ifEmpty { null }, description = description.trim().ifEmpty { null },
        pinned = pinned, hidden = hidden, tags = tags,
        coverKey = if (resetCover) null else options.coverKey,
    )
    fun addTag(value: String) {
        val tag = value.trim().replace(Regex("\\s+"), " ").take(40)
        if (tag.isNotEmpty() && tags.size < MAX_TAGS && tags.none { it.equals(tag, ignoreCase = true) }) tags = tags + tag
        newTag = ""
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()) {
            Text("Edit album", style = MaterialTheme.typography.headlineSmall)
            if (album != null) {
                Text(if (proton) "The name changes in Proton Photos too. Everything else stays in this app." else "Changes stay in this app. Your Drive folder isn’t renamed.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                OutlinedTextField(title, { title = it.take(200) }, Modifier.fillMaxWidth().padding(top = 20.dp),
                    label = { Text("Name") }, placeholder = { Text(album.folderName.ifEmpty { album.name }) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next))
                OutlinedTextField(description, { description = it.take(2000) }, Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text("Description") }, minLines = 2, maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }

            if (album != null) {
                SectionTitle("Cover")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (options.coverKey != null && !resetCover) "Chosen photo" else "Newest photo",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (options.coverKey != null && !resetCover) TextButton(onClick = { resetCover = true }) { Text("Use default") }
                    FilledTonalButton(onClick = { onSave(edited()); onDismiss(); onChooseCover() }) { Text("Choose") }
                }

                SectionTitle("Tags")
                if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        InputChip(selected = true, onClick = { tags = tags - tag }, label = { Text(tag) },
                            trailingIcon = { Icon(Icons.Outlined.Close, "Remove tag $tag", Modifier.size(18.dp)) })
                    }
                }
                if (tags.size < MAX_TAGS) OutlinedTextField(newTag, { newTag = it.take(40) }, Modifier.fillMaxWidth().padding(top = 4.dp),
                    label = { Text("Add a tag") }, singleLine = true,
                    trailingIcon = { IconButton(onClick = { addTag(newTag) }, enabled = newTag.isNotBlank()) { Icon(Icons.Outlined.Add, "Add tag") } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag(newTag) }))
                val suggestions = knownTags.map { it.name }.filter { known -> tags.none { it.equals(known, ignoreCase = true) } }
                if (suggestions.isNotEmpty() && tags.size < MAX_TAGS) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.take(12).forEach { tag -> SuggestionChip(onClick = { addTag(tag) }, label = { Text(tag) }) }
                }

                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Pin to top", "Show before other albums", pinned) { pinned = it }
                ToggleRow("Hide this album",
                    "It opens only after your fingerprint, face or screen lock. On this phone its photos also leave the timeline and the photo picker; other Proton apps still show them.",
                    hidden) { hidden = it }
                if (album.parent == null) TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(if (proton) "Delete album" else "Remove gallery from this app")
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if (newTag.isNotBlank()) addTag(newTag); onSave(edited()); onDismiss() }) { Text("Save") }
            }
        }
    }
    if (confirmRemove && proton) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Delete this album?") },
        text = { Text("“${album?.name}” is deleted from Proton Photos. Its photos stay in your timeline.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; onDismiss(); onDeleteAlbum() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
    )
    else if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove this gallery?") },
        text = { Text("“${album?.name}” and its albums leave this app. Nothing is deleted from Drive, and you can add the folder again later.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; onDismiss(); onRemoveGallery() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
    )
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
