package com.music.bitchord.data.sources.qobuz

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "BitChord"

/**
 * The gateway session Qobuz audio stands behind, ported from Mornye.
 *
 * Attribution: this client re-implements, in Kotlin, the signed-session
 * protocol Mornye's Rust backend speaks for its `qobuz-web` extension
 * (`rust_backend/crates/extensions/src/signed_session/` in mornyeapp/Mornye,
 * LGPL-3.0). BitChord is GPL-3.0; the protocol constants below are the
 * extension's own published manifest values (`sources/qobuz-web/manifest.json`
 * in mornyeapp/Mornye-Extension, also LGPL-3.0). Nothing here relicenses
 * either project.
 *
 * Why a session at all: Qobuz's public API will *describe* any track to
 * anyone, but the bytes Mornye plays come from a gateway
 * (`api.zarz.moe`) that only answers requests signed by a per-install
 * session, and a session is only minted after the install proves itself
 * once in a browser (a Cloudflare Turnstile challenge, in practice). The
 * protocol, faithfully copied from the Rust:
 *
 *  1. `GET /v2/bootstrap?app_version=…&install_id=…` answers either a
 *     session record or a `challenge_id`.
 *  2. A challenge is completed by opening
 *     `/v2/challenge?id=<challenge_id>&cb=<callback>` in a browser; the
 *     page redirects to the callback with a one-time `grant`.
 *  3. `POST /v2/session/exchange {grant, install_id, app_version,
 *     platform}` trades the grant for `{session_id, session_secret,
 *     expires_at}`.
 *  4. Every later request is HMAC-signed (see [signedHeaders]), and a
 *     session within an hour of expiry is rolled over with a signed
 *     `POST /v2/session/refresh {install_id}`.
 *
 * The secret lives only in encrypted prefs and is never logged; log lines
 * name the session's *state*, never its value.
 */
object ZarzSession {

    // ── Protocol constants, from the extension's manifest ───────────────

    const val BASE_URL = "https://api.zarz.moe/v2"
    private const val APP_VERSION = "qobuz-web@1.2.21"
    private const val PLATFORM = "extension"
    private const val SCHEME_LABEL = "ZARZ-HMAC-V1"
    private const val HEADER_PREFIX = "X-Zarz-"
    private const val TIME_WINDOW_SECONDS = 300L

    /**
     * Where the challenge page sends the grant. Mornye's own build answers
     * to `mornye://session-grant`; a host supplies its own callback (the
     * extension deliberately omits one), so BitChord supplies this.
     */
    const val CALLBACK_URL = "bitchord://qobuz-grant"

    /** Refresh when less than this remains, matching `refresh_due` in the Rust. */
    private const val REFRESH_WINDOW_MS = 3_600_000L

    /** Thrown when a signed call is asked for and no usable session exists. */
    class VerificationRequired(message: String = "Qobuz needs its one-time verification") :
        Exception(message)

    /** A signed gateway answer: the HTTP status and the body, parsed leniently. */
    data class SignedResponse(val status: Int, val body: String) {
        val json: JsonObject?
            get() = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    /** What a bootstrap attempt settled into. */
    sealed interface BootstrapResult {
        /** The gateway minted (or already held) a session; no browser step needed. */
        data object SessionEstablished : BootstrapResult

        /** The listener must complete this URL in a browser once. */
        data class Challenge(val url: String) : BootstrapResult

        data class Failure(val reason: String) : BootstrapResult
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Set when the gateway itself says the held session will not do — a
     * 401 on a signed call, in practice — so [needsVerification] keeps
     * saying so even though a (dead) record is still on disk. Cleared by a
     * successful exchange or refresh.
     */
    @Volatile
    private var verificationFlagged = false

    fun init(context: Context) {
        if (prefs != null) return
        // Same repair and degradation as the other stores; see EncryptedPrefs.
        prefs = EncryptedPrefs.open(context, "bitchord_qobuz", "bitchord_qobuz_plain")
        installId()
    }

    // ── Stored record ───────────────────────────────────────────────────

    private data class Record(val sessionId: String, val sessionSecret: String, val expiresAt: String)

    /**
     * This install's gateway identity: 16 random bytes as hex, generated
     * once and kept. The Rust generates the same shape (`random_hex(16)`
     * when the store has none) and presents it on bootstrap, exchange and
     * refresh alike, so it must not change between those calls.
     */
    @Synchronized
    fun installId(): String {
        val store = prefs ?: return ""
        store.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = randomHex(16)
        store.edit().putString(KEY_INSTALL_ID, created).apply()
        return created
    }

    private fun loadRecord(): Record? {
        val store = prefs ?: return null
        val id = store.getString(KEY_SESSION_ID, null).orEmpty()
        val secret = store.getString(KEY_SESSION_SECRET, null).orEmpty()
        if (id.isBlank() && secret.isBlank()) return null
        return Record(id, secret, store.getString(KEY_EXPIRES_AT, null).orEmpty())
    }

    private fun saveRecord(record: Record) {
        val store = prefs ?: return
        store.edit()
            .putString(KEY_SESSION_ID, record.sessionId)
            .putString(KEY_SESSION_SECRET, record.sessionSecret)
            .putString(KEY_EXPIRES_AT, record.expiresAt)
            .apply()
        verificationFlagged = false
    }

    private fun clearSession() {
        val store = prefs ?: return
        store.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_SESSION_SECRET)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    /** The Rust `Record::usable`: both halves present, and not past `expires_at` when it parses. */
    private fun Record.isUsable(nowMs: Long): Boolean {
        if (sessionId.isBlank() || sessionSecret.isBlank()) return false
        val expiresMs = parseGatewayTime(expiresAt) ?: return true
        return nowMs < expiresMs
    }

    fun hasUsableSession(): Boolean =
        !verificationFlagged && loadRecord()?.isUsable(System.currentTimeMillis()) == true

    fun needsVerification(): Boolean = !hasUsableSession()

    /** The gateway refused the held session; forget it and say verification is due. */
    fun flagVerificationRequired() {
        verificationFlagged = true
        clearSession()
    }

    // ── Bootstrap / exchange / refresh ──────────────────────────────────

    /**
     * Asks the gateway where this install stands. A fresh install gets a
     * [BootstrapResult.Challenge]; an install the gateway already knows
     * can be handed a session outright.
     */
    suspend fun bootstrap(): BootstrapResult = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/bootstrap".toHttpUrl().newBuilder()
            .addQueryParameter("app_version", APP_VERSION)
            .addQueryParameter("install_id", installId())
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", gatewayUserAgent())
            .get()
            .build()
        val response = runCatching {
            Http.client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
        }.getOrElse {
            TrackLog.w(TAG, "  ✗ Zarz bootstrap failed: ${it.message}")
            return@withContext BootstrapResult.Failure("Could not reach the Qobuz gateway: ${it.message}")
        }
        val (code, body) = response
        if (code !in 200..299) {
            TrackLog.w(TAG, "  ✗ Zarz bootstrap http $code")
            return@withContext BootstrapResult.Failure("The Qobuz gateway answered HTTP $code")
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext BootstrapResult.Failure("The Qobuz gateway answered something unreadable")

        recordFrom(root)?.let { record ->
            saveRecord(record)
            TrackLog.d(TAG, "  ✓ Zarz bootstrap returned a session")
            return@withContext BootstrapResult.SessionEstablished
        }

        // A challenge the gateway addresses directly, when it sends one —
        // the Rust prefers an explicit auth/challenge URL over building one.
        val directUrl = root.string("auth_url") ?: root.string("challenge_url")
        if (!directUrl.isNullOrBlank()) return@withContext BootstrapResult.Challenge(directUrl)

        val challengeId = root.string("challenge_id")
        if (!challengeId.isNullOrBlank()) {
            return@withContext BootstrapResult.Challenge(challengeUrl(challengeId))
        }
        BootstrapResult.Failure("The Qobuz gateway returned neither a session nor a challenge")
    }

    /**
     * `protocol.rs`'s `challenge_url`: the challenge endpoint, carrying the
     * challenge id and this app's callback — the callback itself carrying
     * `cb_version=v2grant` and a one-time `state` the returning grant is
     * checked against. The page at this URL is where the listener solves
     * the gateway's Turnstile check; it redirects to the callback as
     * `<callback>?grant=…&state=…`.
     */
    private fun challengeUrl(challengeId: String): String {
        val state = randomHex(16)
        prefs?.edit()?.putString(KEY_PENDING_STATE, state)?.apply()
        val callback = "$CALLBACK_URL?cb_version=v2grant&state=$state"
        return "$BASE_URL/challenge?id=${urlEncode(challengeId)}&cb=${urlEncode(callback)}"
    }

    /** The `state` of the outstanding challenge, for checking a returning grant against. */
    fun pendingState(): String? = prefs?.getString(KEY_PENDING_STATE, null)

    /**
     * Trades a grant from the challenge callback for a session, with the
     * Rust's retry shape: three attempts, backing off only on a 429.
     * Unsigned — the grant itself is the credential here.
     */
    suspend fun completeGrant(grant: String): Boolean = withContext(Dispatchers.IO) {
        if (grant.isBlank()) return@withContext false
        val body = buildJsonObject {
            put("grant", grant)
            put("install_id", installId())
            put("app_version", APP_VERSION)
            put("platform", PLATFORM)
        }.toString()
        for (attempt in 0..2) {
            val request = Request.Builder().url("$BASE_URL/session/exchange")
                .header("Accept", "application/json")
                .header("User-Agent", gatewayUserAgent())
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            val response = runCatching {
                Http.client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
            }.getOrNull() ?: return@withContext false
            val (code, responseBody) = response
            if (code == 429 && attempt < 2) {
                delay(1_000L * (attempt + 1))
                continue
            }
            if (code !in 200..299) {
                TrackLog.w(TAG, "  ✗ Zarz session exchange http $code")
                return@withContext false
            }
            val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
                ?: return@withContext false
            val record = recordFrom(root) ?: return@withContext false
            saveRecord(record)
            prefs?.edit()?.remove(KEY_PENDING_STATE)?.apply()
            TrackLog.d(TAG, "  ✓ Zarz session established by grant exchange")
            return@withContext true
        }
        false
    }

    /**
     * Completes verification from a raw callback, wherever the grant
     * actually surfaces: the deep link the browser returns with (see
     * [QobuzGrantLink]), a redirect the verify dialog's WebView caught,
     * or the link the challenge page itself offers to copy. The grant is
     * checked against the outstanding challenge's state before it is
     * traded — the check the dialog used to do alone, kept here so every
     * return path gets it.
     */
    suspend fun completeGrantFromCallback(raw: String): Boolean {
        val grant = callbackParam(raw, "grant") ?: return false
        val state = callbackParam(raw, "state")
        val expected = pendingState()
        if (expected != null && state != expected) {
            TrackLog.w(TAG, "  ✗ Qobuz verification callback carried an unexpected state")
            return false
        }
        return completeGrant(grant)
    }

    /**
     * One parameter out of a callback, in whatever shape it arrived: a
     * query pair, a fragment pair, or a pair inside pasted text. Grants
     * are URL-safe tokens, so the decode is a formality — but a pasted
     * link can carry an encoded one.
     */
    private fun callbackParam(raw: String, name: String): String? {
        val match = Regex("""(?:^|[?&#])$name=([^&#\s]+)""").find(raw) ?: return null
        return runCatching { java.net.URLDecoder.decode(match.groupValues[1], "UTF-8") }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Signed rollover, run when the held session is inside its last hour:
     * `POST /session/refresh {install_id}`, and whichever of the three
     * session fields the answer restates replaces the stored one. A failed
     * refresh is not fatal here — the held session is used until the
     * gateway actually refuses it, which is the Rust's posture too.
     */
    private suspend fun refreshIfDue() {
        val record = loadRecord() ?: return
        val now = System.currentTimeMillis()
        if (!record.isUsable(now)) return
        val expiresMs = parseGatewayTime(record.expiresAt) ?: return
        if (expiresMs - now > REFRESH_WINDOW_MS) return
        val body = buildJsonObject { put("install_id", installId()) }.toString()
        val response = runCatching {
            signedRequestLocked(record, "POST", "/session/refresh", body, emptyMap())
        }.getOrNull() ?: return
        if (response.status !in 200..299) {
            TrackLog.w(TAG, "  ✗ Zarz session refresh http ${response.status}")
            return
        }
        val root = response.json ?: return
        val refreshed = Record(
            sessionId = root.string("session_id") ?: record.sessionId,
            sessionSecret = root.string("session_secret") ?: record.sessionSecret,
            expiresAt = root.string("expires_at") ?: record.expiresAt,
        )
        if (refreshed != record) {
            saveRecord(refreshed)
            TrackLog.d(TAG, "  ✓ Zarz session refreshed")
        }
    }

    // ── Signed requests ─────────────────────────────────────────────────

    /**
     * One signed gateway call, as the extension's `session.signedFetch`.
     *
     * [path] is the path under [BASE_URL] — `/tickets`, `/dl/qbz`, … —
     * query string included when there is one. Throws [VerificationRequired]
     * when there is no usable session to sign with; the caller decides what
     * that means for the track in hand.
     */
    suspend fun signedRequest(
        method: String,
        path: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): SignedResponse = withContext(Dispatchers.IO) {
        var record = loadRecord()
        if (record == null || !record.isUsable(System.currentTimeMillis())) {
            throw VerificationRequired()
        }
        refreshIfDue()
        record = loadRecord()
        if (record == null || !record.isUsable(System.currentTimeMillis())) {
            throw VerificationRequired()
        }
        signedRequestLocked(record, method, path, body, extraHeaders)
    }

    private fun signedRequestLocked(
        record: Record,
        method: String,
        path: String,
        body: String?,
        extraHeaders: Map<String, String>,
    ): SignedResponse {
        val url = BASE_URL + path
        val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val headers = signedHeaders(record, method.uppercase(Locale.US), url, bodyBytes)
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", gatewayUserAgent())
        headers.forEach { (name, value) -> builder.header(name, value) }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        when (method.uppercase(Locale.US)) {
            "GET" -> builder.get()
            "POST" -> {
                builder.header("Content-Type", "application/json")
                builder.post((body ?: "").toRequestBody(JSON_MEDIA))
            }
            else -> builder.method(method.uppercase(Locale.US), body?.toRequestBody(JSON_MEDIA))
        }
        return Http.client.newCall(builder.build()).execute().use { response ->
            SignedResponse(response.code, response.body?.string().orEmpty())
        }
    }

    /**
     * The signing itself, line for line from `signed_headers` in the Rust
     * `protocol.rs` — do not "tidy" any of it:
     *
     *  - `timestamp` is the UTC instant, `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`.
     *  - `window` is the Unix second divided by the 300s time window.
     *  - A *rolling* key is derived first: `HMAC-SHA256(session_secret,
     *    "<window>:<session_id>")`, base64url-no-pad — and it is that
     *    base64 *string's bytes* which key the second HMAC.
     *  - The signed string is the scheme label, method, escaped path
     *    (path only, no query), an empty line, the lowercase hex SHA-256
     *    of the body, the timestamp, a random 12-byte hex nonce, the
     *    session id, the app version and the platform, joined with `\n`.
     *  - Headers are those values under the manifest's `X-Zarz-` prefix.
     */
    private fun signedHeaders(
        record: Record,
        method: String,
        url: String,
        body: ByteArray,
    ): Map<String, String> {
        val nowMs = System.currentTimeMillis()
        val timestamp = synchronized(TIMESTAMP_FORMAT) { TIMESTAMP_FORMAT.format(Date(nowMs)) }
        val bodyHash = sha256Hex(body)
        val window = (nowMs / 1000) / TIME_WINDOW_SECONDS
        val rolling = hmacBase64Url(
            record.sessionSecret.toByteArray(Charsets.UTF_8),
            "$window:${record.sessionId}".toByteArray(Charsets.UTF_8),
        )
        val nonce = randomHex(12)
        val signing = listOf(
            SCHEME_LABEL,
            method,
            url.toHttpUrl().encodedPath,
            "",
            bodyHash,
            timestamp,
            nonce,
            record.sessionId,
            APP_VERSION,
            PLATFORM,
        ).joinToString("\n")
        val signature = hmacBase64Url(
            rolling.toByteArray(Charsets.UTF_8),
            signing.toByteArray(Charsets.UTF_8),
        )
        return linkedMapOf(
            "${HEADER_PREFIX}Session" to record.sessionId,
            "${HEADER_PREFIX}Timestamp" to timestamp,
            "${HEADER_PREFIX}Nonce" to nonce,
            "${HEADER_PREFIX}Body-SHA256" to bodyHash,
            "${HEADER_PREFIX}Signature" to signature,
            "${HEADER_PREFIX}App-Version" to APP_VERSION,
            "${HEADER_PREFIX}Platform" to PLATFORM,
        )
    }

    // ── Small pieces ────────────────────────────────────────────────────

    private fun recordFrom(root: JsonObject): Record? {
        val id = root.string("session_id") ?: return null
        val secret = root.string("session_secret") ?: return null
        val expires = root.string("expires_at") ?: return null
        if (id.isBlank() || secret.isBlank() || expires.isBlank()) return null
        return Record(id, secret, expires)
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** The gateway's own client name for itself: Mornye's network layer sends `Mornye/<app_version>`. */
    private fun gatewayUserAgent() = "Mornye/$APP_VERSION"

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    internal fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    private fun hmacBase64Url(key: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message))
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * The gateway's `expires_at`, an RFC 3339 timestamp. An unparseable
     * value is treated the way the Rust treats it: no expiry is known, so
     * the record's usability turns on the session fields alone.
     */
    private fun parseGatewayTime(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        runCatching { return java.time.Instant.parse(trimmed).toEpochMilli() }
        runCatching { return java.time.OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        return null
    }

    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_SESSION_SECRET = "session_secret"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_PENDING_STATE = "pending_state"

    private val JSON_MEDIA = "application/json".toMediaType()

    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
