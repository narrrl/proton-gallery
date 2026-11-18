package io.narl.protongallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.icons.PhotoLibrary

/** What the screens can do with Proton Photos albums. */
class AlbumActions(
    /** Albums to add photos to; null until loaded. */
    val choices: List<FolderAlbum>? = null,
    val loadChoices: () -> Unit = {},
    val add: (FolderAlbum, List<GalleryItem>) -> Unit = { _, _ -> },
    val create: (String, List<GalleryItem>) -> Unit = { _, _ -> },
    val remove: (List<GalleryItem>) -> Unit = {},
    val delete: (FolderAlbum) -> Unit = {},
    /** Hide photos: into this hidden album, or a new one with this name. */
    val hide: (List<GalleryItem>, FolderAlbum?, String) -> Unit = { _, _, _ -> },
)

/** Add the selected photos to an album, or to a new one. */
@Composable
fun AlbumChooserSheet(
    items: List<GalleryItem>, albums: List<FolderAlbum>?, thumbnails: ThumbnailLoader,
    onChoose: (FolderAlbum) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit,
) {
    val waiting = items.count { !it.inProton }
    AlbumChooserSheet("Add to album", buildString {
        append(if (items.size == 1) "1 item" else "${items.size} items")
        if (waiting > 0) append(if (waiting == 1) " · 1 isn’t backed up yet and stays out" else " · $waiting aren’t backed up yet and stay out")
    }, albums, thumbnails, onChoose, onCreate, onDismiss)
}

/** Choose an album, or name a new one: `suggestion` fills the name field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumChooserSheet(
    title: String, caption: String, albums: List<FolderAlbum>?, thumbnails: ThumbnailLoader,
    onChoose: (FolderAlbum) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit,
    suggestion: String = "", extra: (@Composable () -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf(suggestion) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp).imePadding()) {
            Text(title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
            Text(caption, Modifier.padding(horizontal = 24.dp).padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra?.invoke()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it.take(200) }, Modifier.weight(1f), label = { Text("New album") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name) }))
                Spacer(Modifier.width(12.dp))
                Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
            }
            when {
                albums == null -> ActivityBar(Activity("Loading your albums"), Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                albums.isEmpty() -> Text("No albums yet. Name one above.", Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(albums, key = { it.id }) { album ->
                        ListItem(
                            headlineContent = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(albumCaption(album)) },
                            leadingContent = { AlbumThumb(album, thumbnails, Modifier.size(48.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onChoose(album) }.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** An album's cover, or a placeholder while it has none. */
@Composable
fun AlbumThumb(album: FolderAlbum, loader: ThumbnailLoader, modifier: Modifier = Modifier) {
    val cover = album.cover
    val bitmap by produceState(cover?.let(loader::peek), cover?.id, cover?.revision, loader) { if (value == null) value = cover?.let { loader.load(it, album.hidden) } }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val shown = bitmap
        if (shown != null) Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Name a new, empty album. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAlbumDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()) {
            Text("New album", style = MaterialTheme.typography.headlineSmall)
            Text("Albums are kept in Proton Photos, so they show in Proton’s apps too.", Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it.take(200) }, Modifier.fillMaxWidth().padding(top = 16.dp), label = { Text("Name") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name) }))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
            }
        }
    }
}
