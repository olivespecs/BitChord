package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.qobuz.QobuzApi
import com.music.bitchord.data.sources.qobuz.ZarzSession

private const val TAG = "BitChord"

/**
 * Qobuz, as a native source — ported from Mornye's `qobuz-web` extension
 * (mornyeapp/Mornye-Extension, LGPL-3.0) into BitChord (GPL-3.0).
 *
 * Search and playback are different doors, and the source is honest about
 * which is which:
 *
 *  - **Search** walks through the front door. Qobuz's public catalogue
 *    answers anyone, with ISRCs — see [QobuzApi.searchTracks]. It works
 *    with no setup at all, so Qobuz rows appear in search even before
 *    anything else is configured.
 *  - **Playback** needs the gateway session Mornye's playback stands
 *    behind — a one-time browser verification, then signed requests; see
 *    [ZarzSession]. Without a session [stream] is a plain miss and
 *    [health] says exactly what is missing and where to fix it, so the
 *    resolver steps over Qobuz to the next source instead of failing the
 *    track. What it will never do is hand over the 30-second sample
 *    Qobuz's public signed endpoint offers: a sample served as the track
 *    is a failure wearing a success's clothes.
 *
 * Enabled by default: search needs nothing, so there is nothing to
 * gate, and an unverified Qobuz costs the walk one fast miss per track
 * rather than anything worse. The switch on the Sources screen remains
 * the listener's to turn off.
 */
class QobuzSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.QOBUZ
    override val displayName: String get() = config.label.ifBlank { SourceKind.QOBUZ.label }

    override suspend fun health(): SourceHealth {
        if (ZarzSession.hasUsableSession()) return SourceHealth.Ok("Verified — lossless playback available")
        // Being unreachable is not the problem and a retry won't fix it:
        // this is Rejected, with the remedy on the row itself — the
        // Sources screen renders this line under the source's name, and
        // tapping the row opens the verification.
        return SourceHealth.Rejected(
            "Qobuz needs a one-time verification before it can play — tap this row to verify.",
        )
    }

    /** [waitForAll] and [request] are moot: one public endpoint, answered at every tier alike. */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        if (query.isBlank()) return emptyList()
        TrackLog.d(TAG, "▶ Qobuz searchTracks() query=\"$query\" limit=$limit")
        val results = runCatching { QobuzApi.searchTracks(query, limit) }
            .getOrElse {
                TrackLog.w(TAG, "  ✗ Qobuz search failed: ${it.message}")
                return emptyList()
            }
        TrackLog.d(TAG, "  ✓ Qobuz returned ${results.size} tracks")
        return results.map { track ->
            Song(
                videoId = SourceRegistry.trackKey(config.id, track.id),
                title = track.title,
                artist = track.artist,
                albumName = track.album,
                thumbnailUrl = track.artworkUrl,
                durationText = track.durationSec?.let { sec -> "%d:%02d".format(sec / 60, sec % 60) },
                isExplicit = track.explicit,
                isrc = track.isrc,
                // The best the catalogue holds for this recording, stated
                // the way the module source states its tiers. The stream
                // actually served is re-stated by [stream] from the
                // gateway's own answer, which is the number that counts.
                sourceQuality = track.qualityLabel,
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        TrackLog.d(TAG, "▶ Qobuz stream() trackId=$trackId request=$request")
        // The chain the extension walks (`qualityFallbackChain`): a
        // lossless ask starts at hi-res max and steps down to CD rather
        // than failing; a capped ask is CD only, because everything above
        // it is a bigger FLAC than the cap exists to avoid.
        val chain = when (request) {
            is StreamRequest.Lossless -> listOf(27, 7, 6)
            is StreamRequest.Capped -> listOf(6)
            is StreamRequest.Best -> listOf(27, 7, 6)
        }
        return when (val outcome = runCatching { QobuzApi.resolveDownload(trackId, chain) }
            .getOrElse {
                TrackLog.w(TAG, "  ✗ Qobuz stream failed: ${it.message}")
                return null
            }) {
            is QobuzApi.DownloadOutcome.Found -> {
                TrackLog.d(
                    TAG,
                    "  ✓ Qobuz ${outcome.download.bitDepth ?: "?"}-bit/" +
                        "${outcome.download.sampleRateHz ?: "?"}Hz FLAC for $trackId",
                )
                SourceStream(
                    url = outcome.download.url,
                    format = StreamFormat(
                        codec = "flac",
                        bitDepth = outcome.download.bitDepth,
                        sampleRateHz = outcome.download.sampleRateHz,
                    ),
                    // Qobuz's rows carry their length in search; the
                    // gateway's answer doesn't restate it, so nothing is
                    // claimed here beyond what the response itself said.
                )
            }
            is QobuzApi.DownloadOutcome.NeedsVerification -> {
                TrackLog.w(TAG, "  ✗ Qobuz stream: gateway session missing or refused — verification needed")
                null
            }
            is QobuzApi.DownloadOutcome.Miss -> {
                TrackLog.w(TAG, "  ✗ Qobuz stream miss for $trackId: ${outcome.reason}")
                null
            }
        }
    }
}
