package io.narl.protongallery

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.narl.protongallery.bridge.SignInStage
import android.content.ClipData
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch

/** Native credentials UI. Secrets are never in saved instance state or a WebView. */
@Composable
fun AccountScreen(state: AccountState, onClose: () -> Unit, onSignIn: (String, String) -> Unit,
    onCode: (String) -> Unit, onMailbox: (String) -> Unit, onVerification: (String) -> Unit,
    onSignOut: () -> Unit, onRefresh: () -> Unit, onTestConnection: () -> Unit = {},
    onRepairPhotos: () -> Unit = {},
) {
    var username by remember { mutableStateOf(state.status.username) }
    var secret by remember(state.status.stage) { mutableStateOf("") }
    var reveal by remember(state.status.stage) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { secret = ""; reveal = false } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); secret = "" }
    }
    val stage = state.status.stage
    fun submit() {
        if (state.busy) return
        val value = secret
        when (stage) {
            SignInStage.SIGNED_OUT -> onSignIn(username, value)
            SignInStage.SECOND_FACTOR -> onCode(value)
            SignInStage.MAILBOX_PASSWORD -> onMailbox(value)
            else -> return
        }
        secret = ""; reveal = false
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn,
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text(if (state.connected) "Done" else "Cancel") }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp).padding(top = 24.dp, bottom = 32.dp).widthIn(max = 480.dp),
                    verticalArrangement = Arrangement.Center) {
                    Image(painterResource(R.drawable.ic_gallery), null, Modifier.size(56.dp))
                    Spacer(Modifier.height(28.dp))
                    Text(when(stage) {
                        SignInStage.SECOND_FACTOR -> "One more step"
                        SignInStage.MAILBOX_PASSWORD -> "Unlock your library"
                        SignInStage.VERIFICATION -> "A quick verification"
                        SignInStage.CONNECTED -> "Your Proton account"
                        else -> "Your moments,\ncloser than ever."
                    }, style = MaterialTheme.typography.headlineLarge)
                    Text(when(stage) {
                        SignInStage.SECOND_FACTOR -> "Enter the six-digit code from your authenticator app."
                        SignInStage.MAILBOX_PASSWORD -> "Your account uses a separate mailbox password. Enter it to unlock your photos."
                        SignInStage.VERIFICATION -> "Complete Proton’s verification to continue signing in."
                        SignInStage.CONNECTED -> state.status.username
                        else -> "Sign in to browse your Proton Photos in a faster, dedicated gallery."
                    }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp))
                    if (stage == SignInStage.VERIFICATION) {
                        state.status.verificationUrl?.let { CaptchaVerification(it, !state.busy, onVerification) }
                    } else if (!state.connected) {
                        if (stage == SignInStage.SIGNED_OUT) {
                            OutlinedTextField(username, { username = it.take(512) }, label = { Text("Email or username") },
                                modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), shape = MaterialTheme.shapes.medium)
                            Spacer(Modifier.height(12.dp))
                        }
                        val code = stage == SignInStage.SECOND_FACTOR
                        OutlinedTextField(secret, { secret = if (code) it.filter { char -> char in '0'..'9' }.take(6) else it.take(4096) },
                            label = { Text(if (code) "Authenticator code" else if (stage == SignInStage.MAILBOX_PASSWORD) "Mailbox password" else "Password") },
                            modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                            visualTransformation = if (reveal || code) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = if (code) null else { { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") } } },
                            keyboardOptions = KeyboardOptions(keyboardType = if (code) KeyboardType.NumberPassword else KeyboardType.Password, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { submit() }), shape = MaterialTheme.shapes.medium)
                        Spacer(Modifier.height(20.dp))
                        val ready = !state.busy && secret.isNotEmpty() && (!code || secret.length == 6) && (stage != SignInStage.SIGNED_OUT || username.isNotBlank())
                        // The one gradient button: the way in wears the icons' face.
                        Button(onClick = { submit() }, enabled = ready,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .then(if (ready) Modifier.background(LocalBrand.current.gradient, Pill) else Modifier),
                            shape = Pill,
                            colors = if (ready) ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White) else ButtonDefaults.buttonColors()) {
                            Text(when {
                                state.busy && stage == SignInStage.SIGNED_OUT -> "Signing in…"
                                state.busy -> "Checking…"
                                stage == SignInStage.SIGNED_OUT -> "Sign in"
                                else -> "Continue"
                            })
                        }
                    } else {
                        if (state.sessionExpired) {
                            Text("Your Proton session expired.", color = MaterialTheme.colorScheme.error)
                            Button(onClick = onSignOut, enabled = !state.busy) { Text("Sign in again") }
                        } else {
                            FilledTonalButton(onClick = onRefresh, enabled = !state.syncing && !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = Pill) {
                                Text(if (state.syncing) "Refreshing Photos…" else "Refresh Proton Photos")
                            }
                            state.lastPhotosSync?.let { lastSync ->
                                val date = java.util.Date(lastSync * 1000)
                                val formatted = java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(date)
                                Text("Last Photos sync $formatted", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = onRepairPhotos, enabled = !state.syncing && !state.busy) { Text("Repair library") }
                            TextButton(onClick = onSignOut, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                Text("Sign out", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        BatteryOptimization(Modifier.padding(top = 16.dp))
                        PhotoPickerSection(Modifier.padding(top = 24.dp))
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                    if (state.error != null || state.report != null || state.checking) ConnectionCheck(state, onTestConnection)
                    if (state.status.persistenceFailed) Text("Your latest session couldn’t be saved. Free some device storage and refresh before closing the app.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
                    if (state.busy) ActivityBar(Activity("Talking to Proton"), Modifier.padding(top = 16.dp))
                    Spacer(Modifier.height(28.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                        Text("Your password is never saved. Your session is protected by Android Keystore on this device.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimization(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var exempt by remember(context) { mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) == true) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }) { Text("Battery optimization") }
        Text(if (exempt) "Proton Gallery is exempt." else "Proton Gallery is optimized. Android may delay background sync.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Step-by-step check of the way to Proton, for when no debugger is attached. */
@Composable
private fun ConnectionCheck(state: AccountState, onTest: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTest, enabled = !state.checking) { Text(if (state.report == null) "Test connection" else "Test again") }
            if (state.checking) Text("Checking…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            state.report?.let { report ->
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Connection report", report))) } }) { Text("Copy") }
            }
        }
        if (state.checking) ActivityBar(Activity("Checking the connection"), Modifier.padding(bottom = 8.dp))
        state.report?.let { report ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                SelectionContainer {
                    Text(report, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Proton Photos in Android's own photo picker. Android lists only the cloud
 * apps the phone maker allows, so the first step is a one-time adb command.
 */
@Composable
private fun PhotoPickerSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var checks by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        // Settings or adb may have changed it while the app was away.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) checks++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val status by produceState<PhotoPicker.Status?>(null, checks) {
        value = withContext(Dispatchers.IO) { PhotoPicker.status(context) }
    }
    val current = status ?: return
    if (current == PhotoPicker.Status.Unavailable) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Text("Android photo picker", style = MaterialTheme.typography.titleSmall)
            Text(when (current) {
                PhotoPicker.Status.Active -> "Your Proton Photos show up whenever an app asks you to choose a photo."
                PhotoPicker.Status.Allowed -> "Choose Proton Gallery as the cloud media app, and your Proton Photos show up whenever an app asks you to choose a photo."
                else -> "Android only lists cloud apps your phone maker allows. To add Proton Gallery, turn on USB debugging, connect the phone to a computer and run this once:"
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            if (current == PhotoPicker.Status.NotAllowed) {
                val command = PhotoPicker.command(context.packageName)
                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(top = 12.dp)) {
                    SelectionContainer {
                        Text(command, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                Text("This sets the whole list. If you use another cloud app in the picker, such as Google Photos (com.google.android.apps.photos), add it after a comma. Restart the phone if Proton Gallery doesn’t show up.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("adb command", command))) } }) { Text("Copy command") }
            }
            if (current != PhotoPicker.Status.NotAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TextButton(onClick = { runCatching { context.startActivity(PhotoPicker.settings()) } }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (current == PhotoPicker.Status.Active) "Picker settings" else "Choose in picker settings")
                }
            }
        }
    }
}
