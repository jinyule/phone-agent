package dev.phoneagent.app

import android.content.Context
import android.content.pm.PackageManager

/** The class names of every component the merged manifest of the variant under test declares. */
internal fun declaredComponents(context: Context): List<String> {
    val flags =
        PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or
            PackageManager.GET_PROVIDERS

    // Robolectric 4.17 only implements the int-flags overload for components.
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, flags)
    return listOfNotNull(info.activities, info.receivers, info.services, info.providers).flatMap { components ->
        components.map { it.name }
    }
}

/** The package of debug-only code (`src/debug`). */
internal const val DEBUG_PACKAGE = "dev.phoneagent.app.debug."

/** Debug-only classes that must not ship in release. */
internal val DEBUG_CLASSES =
    listOf(
        "dev.phoneagent.app.debug.DebugModelConfigProvider",
        "dev.phoneagent.app.debug.DebugModelConfig",
        "dev.phoneagent.app.debug.KeystoreSelfCheck",
        "dev.phoneagent.app.debug.HelperSelfCheckReceiver",
    )

/** The debug model provider's authority for the app id `dev.phoneagent`. */
internal const val DEBUG_MODEL_AUTHORITY = "dev.phoneagent.debug.model"
