package io.narl.protongallery

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Hosted verification only. Origin-scoped messaging never exposes a Java object
 * to CAPTCHA provider frames. Passwords and API session tokens never enter here.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptchaVerification(url: String, enabled: Boolean, onToken: (String) -> Unit) {
    val currentToken by rememberUpdatedState(onToken)
    val currentEnabled by rememberUpdatedState(enabled)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var consumed by remember(url) { mutableStateOf(false) }
    DisposableEffect(url) { onDispose { webView?.apply { stopLoading(); destroy() }; webView = null } }
    val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    if (!supported || !trustedVerificationUrl(url)) {
        Text("Update Android System WebView to complete Proton’s verification.", color = MaterialTheme.colorScheme.error)
        return
    }
    if (failed) Text("Verification couldn’t load. Check your connection, then cancel and try signing in again.", color = MaterialTheme.colorScheme.error)
    key(url) {
        AndroidView(modifier = Modifier.fillMaxWidth().height(360.dp), factory = { context ->
            WebView(context).apply {
                webView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                WebViewCompat.addWebMessageListener(this, "GalleryVerification", setOf("https://verify.proton.me")) { _, message, origin, mainFrame, _ ->
                    if (mainFrame && trustedVerificationUrl(origin.toString()) && currentEnabled && !consumed) {
                        verificationToken(message.data.orEmpty())?.let { token -> consumed = true; currentToken(token) }
                    }
                }
                WebViewCompat.addDocumentStartJavaScript(this,
                    "Object.defineProperty(window,'AndroidInterface',{value:{dispatch:function(message){window.GalleryVerification.postMessage(message);}}});",
                    setOf("https://verify.proton.me"))
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame && !trustedVerificationUrl(request.url.toString())
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) failed = true
                    }
                }
                loadUrl(url)
            }
        })
    }
}

internal fun trustedVerificationUrl(url: String): Boolean = runCatching {
    val uri = Uri.parse(url)
    uri.scheme == "https" && uri.host == "verify.proton.me" && (uri.port == -1 || uri.port == 443) && uri.userInfo == null
}.getOrDefault(false)
internal fun verificationToken(message: String): String? = runCatching {
    if (message.length > 16_384) return null
    val json = JSONObject(message)
    if (json.optString("type") != "HUMAN_VERIFICATION_SUCCESS") return null
    val payload = json.getJSONObject("payload")
    if (payload.optString("type") != "captcha") return null
    payload.getString("token").takeIf { it.isNotBlank() && it.length <= 8192 }
}.getOrNull()
