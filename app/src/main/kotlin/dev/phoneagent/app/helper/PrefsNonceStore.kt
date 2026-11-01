package dev.phoneagent.app.helper

import android.content.Context
import android.content.SharedPreferences
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
            launchBoot = prefs.intOrNull(LAUNCH_BOOT),
            armedHash = prefs.getString(ARMED, null),
            armedAtMs = prefs.getLong(ARMED_AT, 0),
            armedBoot = prefs.intOrNull(ARMED_BOOT),
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
                .putIntOrRemove(LAUNCH_BOOT, slots.launchBoot)
                .putIntOrRemove(ARMED_BOOT, slots.armedBoot)
                .commit()
        check(written) { "could not persist helper nonces" }
    }

    private fun SharedPreferences.intOrNull(key: String): Int? = if (contains(key)) getInt(key, 0) else null

    private fun SharedPreferences.Editor.putIntOrRemove(
        key: String,
        value: Int?,
    ): SharedPreferences.Editor = if (value == null) remove(key) else putInt(key, value)

    private companion object {
        const val PREFS = "helper_handoff"
        const val RESUME = "resume"
        const val LAUNCH = "launch"
        const val LAUNCH_AT = "launchAt"
        const val ARMED = "armed"
        const val ARMED_AT = "armedAt"
        const val LAUNCH_BOOT = "launchBoot"
        const val ARMED_BOOT = "armedBoot"
    }
}
