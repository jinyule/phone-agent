package dev.phoneagent.app.helper

import android.content.Context
import dev.phoneagent.privileged.handoff.NonceSlots
import dev.phoneagent.privileged.handoff.NonceStore

/**
 * [NonceStore] in private SharedPreferences. Writes are synchronous (`commit`): the app can be
 * killed right after handing a helper its next nonce, and the next process must still know it.
 */
internal class PrefsNonceStore(
    context: Context,
) : NonceStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): NonceSlots =
        NonceSlots(
            resume = prefs.getString(RESUME, null),
            launch = prefs.getString(LAUNCH, null),
            launchAtMs = prefs.getLong(LAUNCH_AT, 0),
            armedHash = prefs.getString(ARMED, null),
            armedAtMs = prefs.getLong(ARMED_AT, 0),
        )

    override fun save(slots: NonceSlots) {
        val written =
            prefs
                .edit()
                .putString(RESUME, slots.resume)
                .putString(LAUNCH, slots.launch)
                .putLong(LAUNCH_AT, slots.launchAtMs)
                .putString(ARMED, slots.armedHash)
                .putLong(ARMED_AT, slots.armedAtMs)
                .commit()
        check(written) { "could not persist helper nonces" }
    }

    private companion object {
        const val PREFS = "helper_handoff"
        const val RESUME = "resume"
        const val LAUNCH = "launch"
        const val LAUNCH_AT = "launchAt"
        const val ARMED = "armed"
        const val ARMED_AT = "armedAt"
    }
}
