package com.music.bitchord.data.sources.qobuz

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

private const val TAG = "BitChord"

/**
 * Qobuz, in its two halves, and the gateway calls that join them.
 *
 * Attribution: ported from the `qobuz-web` extension for Mornye
 * (`sources/qobuz-web/index.js` in mornyeapp/Mornye-Extension, LGPL-3.0)
 * into BitChord (GPL-3.0). The endpoints, app ids and the ticket dance
 * below are that extension's; the shapes here follow its code, not a
 * guess at Qobuz's API.
 *
 * The public half needs no account at all: Qobuz's catalogue API answers
 * `track/search` to anyone presenting the web player's app id, with full
 * metadata — ISRC included. That is what [searchTracks] uses, exactly the
 * fallback the extension itself uses when its signed path is unavailable
 * (`getPublicQobuzJSON`).
 *
 * The audio half has no public door, and this file is careful about the
 * one that looks like it might be: Qobuz's signed `track/getFileUrl`,
 * under the preview credentials the extension carries, answers *samples*
 * — measured live: `sample=true`, 30 seconds, 128k-class MP3 whatever
 * format was requested. Serving that as the track would be a lie the
 * player cannot detect, so it is not used anywhere here. Full audio comes
 * only from the gateway, through [ZarzSession]: a download ticket first,
 * then `/dl/qbz`, which answers a direct FLAC URL.
 */
object QobuzApi {

    private const val API_BASE = "https://www.qobuz.com/api.json/0.2"
    private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

    /** The web player's app id, as the extension's public fallback presents it. */
    private const val PUBLIC_APP_ID = "735532640"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One catalogue row, with the fields [com.music.bitchord.data.model.Song] is built from. */
    data class QobuzTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val artworkUrl: String?,
        val durationSec: Int?,
        val explicit: Boolean?,
        val isrc: String?,
        /** The best rendition the catalogue states, e.g. "24-bit · 96 kHz"; null when it states none. */
        val qualityLabel: String?,
    )

    /** A resolved full-track rendition from the gateway. */
    data class QobuzDownload(
        val url: String,
        val bitDepth: Int?,
        val sampleRateHz: Int?,
    )

    /**
     * `GET /track/search` on the public catalogue, the extension's own
     * unsigned fallback. A failed or unreadable answer is an empty list:
     * search callers treat a dead catalogue as a miss, not an error.
     */
    suspend fun searchTracks(query: String, limit: Int): List<QobuzTrack> =
        withContext(Dispatchers.IO) {
            val url = "$API_BASE/track/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                .addQueryParameter("offset", "0")
                .build()
            val request = Request.Builder().url(url)
                .header("X-App-Id", PUBLIC_APP_ID)
                .header("Accept", "application/json")
                .get()
                .build()
            val body = runCatching {
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        TrackLog.w(TAG, "  ✗ Qobuz public search http ${response.code}")
                        return@withContext emptyList()
                    }
                    response.body?.string()
                }
            }.getOrNull() ?: return@withContext emptyList()
            if (body.isNullOrBlank()) return@withContext emptyList()
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return@withContext emptyList()
            val items = root["tracks"]?.jsonObject?.get("items")?.jsonArray
                ?: return@withContext emptyList()
            items.mapNotNull { it.jsonObject.toQobuzTrack() }
        }

    private fun kotlinx.serialization.json.JsonObject.toQobuzTrack(): QobuzTrack? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val title = this["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val version = this["version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val artist = this["performer"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val album = this["album"]?.jsonObject
        val albumTitle = album?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val artwork = album?.get("image")?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull
        val durationSec = this["duration"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        val explicit = this["parental_warning"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        // The recording's own identity, for TrackMatcher; Qobuz states it
        // on the track row itself, no enrichment pass needed.
        val isrc = normalizeIsrc(this["isrc"]?.jsonPrimitive?.contentOrNull)
        val bitDepth = this["maximum_bit_depth"]?.jsonPrimitive?.intOrNull
        val sampleRate = this["maximum_sampling_rate"]?.jsonPrimitive?.contentOrNull
            ?.toDoubleOrNull()
        val qualityLabel = when {
            bitDepth != null && bitDepth > 0 && sampleRate != null && sampleRate > 0 ->
                "$bitDepth-bit · ${formatKhz(sampleRate)} kHz"
            bitDepth != null && bitDepth > 0 -> "$bitDepth-bit"
            else -> null
        }
        return QobuzTrack(
            id = id,
            title = if (version != null) "$title ($version)" else title,
            artist = artist,
            album = albumTitle,
            artworkUrl = artwork,
            durationSec = durationSec,
            explicit = explicit,
            isrc = isrc,
            qualityLabel = qualityLabel,
        )
    }

    private fun formatKhz(khz: Double): String =
        if (khz == khz.toLong().toDouble()) khz.toLong().toString()
        else ("%.1f".format(java.util.Locale.US, khz))

    internal fun normalizeIsrc(raw: String?): String? =
        raw?.trim()
            ?.uppercase(java.util.Locale.ROOT)
            ?.takeIf { ISRC.matches(it) }

    // ── The gateway: tickets and downloads ──────────────────────────────

    /** What the gateway said a stream request came to. */
    sealed interface DownloadOutcome {
        data class Found(val download: QobuzDownload) : DownloadOutcome

        /** No usable session — the listener has a verification to complete first. */
        data object NeedsVerification : DownloadOutcome

        /** The gateway was asked properly and had nothing playable for this track. */
        data class Miss(val reason: String) : DownloadOutcome
    }

    /**
     * The extension's download path (`fetchProviderDownloadInfo`), over
     * [ZarzSession]:
     *
     *  1. A one-use ticket: signed `POST /tickets`, whose `resource_hash`
     *     is the SHA-256 of `qbz:track:<the track's open.qobuz.com URL>`.
     *     The bare track id looks like the obvious thing to hash and is
     *     wrong — the gateway derives the hash from the URL the download
     *     body carries, and minting over the id earned every download a
     *     403 "Ticket resource mismatch" in the extension's own history.
     *     The comment there is the only reason this is right here.
     *  2. Signed `POST /dl/qbz` with that ticket in `X-Zarz-Ticket`,
     *     asking for one quality; the answer carries the direct URL plus
     *     the rendition's bit depth and sampling rate.
     *
     * [qualityCodes] is the fallback chain to walk — Qobuz's own format
     * ids, best first (27 hi-res max, 7 hi-res, 6 CD) — mapped to the
     * gateway's quality names the way the extension maps them.
     */
    suspend fun resolveDownload(trackId: String, qualityCodes: List<Int>): DownloadOutcome {
        val trackUrl = "https://open.qobuz.com/track/$trackId"
        for (code in qualityCodes) {
            when (val outcome = downloadAt(trackId, trackUrl, code)) {
                is DownloadOutcome.Found -> return outcome
                is DownloadOutcome.NeedsVerification -> return outcome
                is DownloadOutcome.Miss -> {
                    TrackLog.d(TAG, "  · Qobuz gateway @$code: ${outcome.reason}")
                }
            }
        }
        return DownloadOutcome.Miss("no quality in $qualityCodes answered with a playable URL")
    }

    private suspend fun downloadAt(trackId: String, trackUrl: String, qualityCode: Int): DownloadOutcome {
        val ticket = try {
            mintTicket(trackUrl)
        } catch (e: ZarzSession.VerificationRequired) {
            return DownloadOutcome.NeedsVerification
        } ?: return DownloadOutcome.Miss("ticket request failed")
        val body = buildJsonObject {
            put("quality", gatewayQualityName(qualityCode))
            put("upload_to_r2", false)
            put("id", trackId)
            put("type", "track")
            put("url", trackUrl)
        }.toString()
        val response = try {
            ZarzSession.signedRequest(
                "POST", "/dl/qbz", body,
                extraHeaders = mapOf("X-Zarz-Ticket" to ticket),
            )
        } catch (e: ZarzSession.VerificationRequired) {
            return DownloadOutcome.NeedsVerification
        }
        if (response.status == 401) {
            // The gateway refusing the session itself, as opposed to the
            // track: forget the session so health and the next attempt
            // both start from the verification step.
            ZarzSession.flagVerificationRequired()
            return DownloadOutcome.NeedsVerification
        }
        if (response.status !in 200..299) {
            return DownloadOutcome.Miss("gateway answered HTTP ${response.status}")
        }
        val root = response.json ?: return DownloadOutcome.Miss("gateway answer was not JSON")
        val nested = root["data"]?.jsonObject
        val url = listOf(root, nested)
            .firstNotNullOfOrNull { obj ->
                obj?.let {
                    it["download_url"]?.jsonPrimitive?.contentOrNull
                        ?: it["url"]?.jsonPrimitive?.contentOrNull
                        ?: it["link"]?.jsonPrimitive?.contentOrNull
                }
            }?.takeIf { it.isNotBlank() }
            ?: return DownloadOutcome.Miss("gateway answer carried no download URL")
        var sampleRate = listOf(root, nested).firstNotNullOfOrNull {
            it?.get("sampling_rate")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        }
        // The extension normalises a rate stated in kHz the same way.
        if (sampleRate != null && sampleRate > 0 && sampleRate < 1000) sampleRate *= 1000
        val bitDepth = listOf(root, nested).firstNotNullOfOrNull {
            it?.get("bit_depth")?.jsonPrimitive?.intOrNull
        }
        return DownloadOutcome.Found(
            QobuzDownload(url = url, bitDepth = bitDepth, sampleRateHz = sampleRate?.toInt()),
        )
    }

    /**
     * Signed `POST /tickets` for one track. Null is a gateway failure the
     * caller reports as a miss; a missing session throws through
     * [ZarzSession.VerificationRequired] so it is never mistaken for one.
     */
    private suspend fun mintTicket(trackUrl: String): String? {
        // See resolveDownload's note: the hash is over the track URL the
        // download body will carry, lowercased — never the bare id.
        val resourceHash = ZarzSession.sha256Hex("qbz:track:${trackUrl.lowercase()}")
        val body = buildJsonObject {
            put("capability", "download_ticket")
            put("provider", "qbz")
            put("resource_hash", resourceHash)
        }.toString()
        val response = ZarzSession.signedRequest("POST", "/tickets", body)
        if (response.status == 401) {
            ZarzSession.flagVerificationRequired()
            throw ZarzSession.VerificationRequired()
        }
        if (response.status !in 200..299) {
            TrackLog.w(TAG, "  ✗ Qobuz ticket http ${response.status}")
            return null
        }
        val root = response.json ?: return null
        return root["ticket_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: root["ticket"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** The extension's quality-name mapping for the gateway (`mapMusicDLQuality`). */
    private fun gatewayQualityName(qualityCode: Int): String = when (qualityCode) {
        27 -> "hi-res-max"
        7 -> "hi-res"
        else -> "cd"
    }
}
