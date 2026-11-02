package com.music.bitchord.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.qobuz.QobuzGrantLink
import com.music.bitchord.data.sources.qobuz.ZarzSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "BitChord"

/**
 * Qobuz's one-time verification, in a browser the app owns.
 *
 * The gateway behind Qobuz playback (see
 * [ZarzSession][com.music.bitchord.data.sources.qobuz.ZarzSession]) will
 * not mint a session for an install it has never seen prove itself in a
 * browser — in practice a Cloudflare Turnstile page. This dialog is that
 * proof: it asks the gateway where the install stands, loads the
 * challenge page it is handed, and watches for the page's redirect to
 * BitChord's own callback (`bitchord://qobuz-grant?grant=…&state=…`).
 * The grant in that redirect is traded for the session by
 * [ZarzSession.completeGrant]; the grant itself is never stored, and the
 * session it buys lives in encrypted prefs.
 *
 * Raised from the Sources screen when the Qobuz row is tapped without a
 * session. Deliberately a dialog over the settings list rather than a
 * destination: there is nothing here to browse, only a check to pass and
 * a result to report.
 *
 * The grant has three ways home, because the first version of this
 * dialog shipped with only the frailest of them — watching the embedded
 * page for its redirect — and a completed check stranded here: the page
 * said Verified and the app heard nothing. Now the page can also be
 * completed in the listener's real browser, which returns through the
 * app's registered deep link (the way Mornye's build does it), the
 * dialog polls for the session appearing by any route, and the page's
 * own copy-link can be pasted in by hand as the last resort.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QobuzVerifyDialog(onDismiss: () -> Unit, onVerified: () -> Unit) {
    val scope = rememberCoroutineScope()
    val currentOnVerified by rememberUpdatedState(onVerified)
    var challengeUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Asking the Qobuz gateway…") }
    var exchanging by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    // The grant can ride in the query string, in a fragment, or in text
    // the user pastes from the page's own "copy link" fallback — look in
    // all three shapes before giving up on a callback.
    fun extractParam(text: String, name: String): String? {
        runCatching { Uri.parse(text) }.getOrNull()
            ?.getQueryParameter(name)?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val match = Regex("""(?:^|[?&#])$name=([^&#\s]+)""").find(text) ?: return null
        return runCatching { Uri.decode(match.groupValues[1]) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun handleCallback(url: String) {
        if (exchanging) return
        val grant = extractParam(url, "grant") ?: return
        val state = extractParam(url, "state")
        // The state is the nonce this install put in its own callback
        // URL; a redirect carrying a different one is not the answer to
        // the challenge this dialog opened, whatever it claims to be.
        // A callback carrying no state at all is a different shape, not
        // a wrong answer: it is the gateway page's own copy-link
        // fallback, which hands over the grant alone for the listener to
        // carry back by hand. That one is let through and the exchange
        // judges the grant itself — a grant minted for somebody else's
        // challenge is not tied to this install and the gateway refuses
        // it there.
        val expected = ZarzSession.pendingState()
        if (expected != null && state != null && state != expected) {
            TrackLog.w(TAG, "  ✗ Qobuz verification callback carried an unexpected state")
            status = "That verification answer did not match this request. Try again."
            return
        }
        exchanging = true
        status = "Completing verification…"
        scope.launch {
            // Another return path (the deep link, handled at the app
            // root) may have beaten this one to the exchange.
            if (ZarzSession.hasUsableSession()) {
                status = "Verified ✓"
                currentOnVerified()
                return@launch
            }
            val ok = runCatching { ZarzSession.completeGrant(grant) }.getOrDefault(false)
            if (ok) {
                status = "Verified ✓"
                kotlinx.coroutines.delay(600)
                currentOnVerified()
            } else {
                exchanging = false
                status = "The gateway did not accept that verification. Try again."
            }
        }
    }

    LaunchedEffect(Unit) {
        when (val result = runCatching { ZarzSession.bootstrap() }.getOrNull()) {
            is ZarzSession.BootstrapResult.SessionEstablished -> currentOnVerified()
            is ZarzSession.BootstrapResult.Challenge -> {
                status = "Complete the check below — it only happens once."
                challengeUrl = result.url
            }
            is ZarzSession.BootstrapResult.Failure -> status = result.reason
            null -> status = "Could not reach the Qobuz gateway."
        }
    }

    // The browser return path: the challenge completed in the listener's
    // real browser and the grant came back through the app's deep link.
    // The app root trades it; here it only has to be noticed.
    val grantLink by QobuzGrantLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(grantLink) {
        val raw = grantLink ?: return@LaunchedEffect
        if (ZarzSession.hasUsableSession()) {
            status = "Verified ✓"
            currentOnVerified()
        } else {
            handleCallback(raw)
        }
    }

    // And the quiet catch-all: while a challenge is open, watch for the
    // session appearing by any route at all (a cold-start return has no
    // dialog-side callback to fire). Two seconds is a settings page's
    // idea of instant, and the loop dies with the dialog.
    LaunchedEffect(challengeUrl) {
        if (challengeUrl == null) return@LaunchedEffect
        while (true) {
            delay(2_000)
            if (ZarzSession.hasUsableSession()) {
                status = "Verified ✓"
                currentOnVerified()
                break
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verify Qobuz") },
        text = {
            Column {
                Text(status)
                val url = challengeUrl
                if (url != null) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    @Deprecated("Deprecated in Java")
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        url: String,
                                    ): Boolean {
                                        if (url.startsWith(ZarzSession.CALLBACK_URL)) {
                                            handleCallback(url)
                                            return true
                                        }
                                        return false
                                    }

                                    // The gateway's grant hand-off is a
                                    // server-driven (HTTP 302) redirect to
                                    // the callback, which the deprecated
                                    // override above does not reliably see
                                    // on modern Android. Watch every page
                                    // load as well, so a completed check
                                    // actually reaches the exchange.
                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        if (url != null &&
                                            url.startsWith(ZarzSession.CALLBACK_URL)
                                        ) {
                                            handleCallback(url)
                                        }
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        if (url != null &&
                                            url.startsWith(ZarzSession.CALLBACK_URL)
                                        ) {
                                            handleCallback(url)
                                        }
                                    }

                                    // A navigation to the app's own scheme
                                    // cannot be loaded by the WebView at
                                    // all — it surfaces here as a failed
                                    // main-frame load, and the failing URL
                                    // is the callback, grant included.
                                    override fun onReceivedError(
                                        view: WebView,
                                        request: android.webkit.WebResourceRequest?,
                                        error: android.webkit.WebResourceError?,
                                    ) {
                                        val failed = request?.url?.toString()
                                        if (request?.isForMainFrame == true &&
                                            failed != null &&
                                            failed.startsWith(ZarzSession.CALLBACK_URL)
                                        ) {
                                            handleCallback(failed)
                                        }
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                    )
                    // The way Mornye does it: a real browser, whose
                    // return trip the system itself delivers to the app
                    // through the deep link. Offered alongside the
                    // embedded page because the embedded page is the one
                    // whose return used to go missing.
                    val browserContext = LocalContext.current
                    TextButton(
                        onClick = {
                            runCatching {
                                browserContext.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    ) {
                        Text("Open in browser instead — it returns here by itself")
                    }
                    // Manual hand-off, for when the page says Verified
                    // but its automatic return to the app never fires:
                    // the page's own "Copy link" button puts the grant
                    // link on the clipboard; pasting it here finishes
                    // the same exchange.
                    Text(
                        "If the page says Verified but nothing happens, tap " +
                            "Copy link on the page and paste it here:",
                    )
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("Paste the link from the page") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = { handleCallback(pasted) },
                        enabled = pasted.isNotBlank() && !exchanging,
                    ) {
                        Text("Use pasted link")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
