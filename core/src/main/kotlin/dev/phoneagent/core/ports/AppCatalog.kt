package dev.phoneagent.core.ports

import kotlinx.serialization.Serializable

/**
 * The apps installed for the current user (phase1 §5 `phone_apps`, `phone_launch app=<name>`).
 * Implemented by the app; reached through `ToolContext.apps`. `phone_launch` with an app name
 * resolves it here to a package name before calling [ScreenPort.launch].
 */
public interface AppCatalog {
    /**
     * Every installed app, in no particular order. Never throws except `CancellationException`;
     * when the list cannot be read it returns what it could read (possibly nothing).
     */
    public suspend fun apps(): List<AppEntry>
}

/**
 * One installed app.
 *
 * @property packageName its package, a valid Java package name.
 * @property label the name the user sees, non-blank (implementations fall back to [packageName]).
 * @property launchable whether it has a launcher entry that `phone_launch` can start.
 */
@Serializable
public data class AppEntry(
    val packageName: String,
    val label: String,
    val launchable: Boolean,
) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name '$packageName'" }
        require(label.isNotBlank()) { "App $packageName: label must not be blank" }
    }
}
