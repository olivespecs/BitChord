package com.music.bitchord.data.sources.qobuz

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The relay for the Qobuz verification grant's return trip, in the same
 * shape as the app's other link relays ([com.music.bitchord.playback.PlayerDeepLink],
 * [com.music.bitchord.data.listentogether.JamInviteLink]): the activity
 * hands every intent it is opened or re-opened with to [consume], and
 * the composition watches [pending] for one that is a grant coming home.
 *
 * The link exists because a browser is where the gateway's challenge is
 * meant to be completed. Mornye's build answers to `mornye://session-grant`;
 * BitChord's callback is [ZarzSession.CALLBACK_URL], and the manifest
 * filter on MainActivity is what lets the system deliver it. The intent
 * is parked rather than acted on here: trading the grant is
 * [ZarzSession.completeGrantFromCallback]'s work, and that is a network
 * call the composition owns.
 */
object QobuzGrantLink {
    private val _pending = MutableStateFlow<String?>(null)

    /** The raw callback link most recently delivered, or null. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun consume(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data?.toString() ?: return
        if (data.startsWith(ZarzSession.CALLBACK_URL)) {
            _pending.value = data
        }
    }

    /** Marks the parked link spent. Called by whoever traded the grant. */
    fun handled() {
        _pending.value = null
    }
}
