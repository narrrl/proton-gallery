package io.narl.protongallery

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.narl.protongallery.bridge.PublicDriveLink
import kotlinx.coroutines.launch

data class DriveLinkState(
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val link: PublicDriveLink? = null,
    val error: String? = null,
)

@Composable
fun DriveLinkDialog(
    state: DriveLinkState, private: Boolean,
    onRead: () -> Unit, onCreate: (String, Long?) -> Unit, onRevoke: () -> Unit, onClose: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val parsedDays = days.toIntOrNull()
    val validDays = days.isEmpty() || (parsedDays != null && parsedDays in 1..3650)
    val link = state.link
    val url = link?.url
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Public link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.busy) Text("Contacting Proton…")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    link != null -> {
                        Text("People with this link can view this Drive folder" +
                            if (link.passwordProtected) " with its password." else ".")
                        link.expires?.let { expiry ->
                            val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(expiry * 1000))
                            Text("Expires $date")
                        }
                        if (confirming) Text("Revoke access for everyone using this link?")
                        else TextButton(enabled = !state.busy, onClick = { confirming = true }) { Text("Revoke link") }
                    }
                    state.loaded && private -> Text("This folder is hidden in Gallery.")
                    state.loaded -> {
                        Text("Anyone with the link can view this Drive folder. You can revoke access here.")
                        OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(days, { days = it }, label = { Text("Expires in days (optional)") },
                            singleLine = true, isError = !validDays, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { if (confirming) confirming = false else onClose() }) { Text(if (confirming) "Cancel" else "Close") } },
        confirmButton = {
            when {
                confirming && link != null -> TextButton(enabled = !state.busy, onClick = { confirming = false; onRevoke() }) { Text("Revoke") }
                url != null -> TextButton(enabled = !state.busy, onClick = {
                    val clip = ClipData.newPlainText("Proton Drive link", url)
                    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    scope.launch { clipboard.setClipEntry(ClipEntry(clip)); copied = true }
                }) { Text(if (copied) "Copied" else "Copy link") }
                !state.loaded -> TextButton(enabled = !state.busy, onClick = onRead) { Text("Retry") }
                link == null && !private -> TextButton(enabled = !state.busy && validDays, onClick = {
                    val expires = days.toLongOrNull()?.let { System.currentTimeMillis() / 1000 + it * 86_400 }
                    onCreate(password, expires)
                    password = ""
                    copied = false
                }) { Text("Create viewer link") }
            }
        },
    )
}
