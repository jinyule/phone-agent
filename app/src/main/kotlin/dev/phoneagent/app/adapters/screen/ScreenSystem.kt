package dev.phoneagent.app.adapters.screen

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Device services [AndroidScreen] uses besides the accessibility tree. Injected so the privileged
 * helper (M2) can take over [start] and [clipboard] for background displays, and so tests can fake them.
 */
internal interface ScreenSystem {
    /** This app's package (own-UI guard). */
    val ownPackage: String

    /** The user's clipboard. */
    val clipboard: ClipboardAccess

    /** Milliseconds since boot, not counting deep sleep (a monotonic clock). */
    fun uptimeMs(): Long

    /** The device is locked or its screen is off (phase1 D11). */
    fun locked(): Boolean

    /** Packages that host system pickers: permission dialogs, choosers, document and photo pickers. */
    fun pickerPackages(): Set<String>

    /** Whether [className] is an activity of [packageName]. */
    fun isActivity(
        packageName: String,
        className: String,
    ): Boolean

    /** The launcher intent of [packageName], or `null` when it is not installed or has no launcher activity. */
    fun launchIntent(packageName: String): Intent?

    /**
     * Package of the activity [intent] resolves to (the system resolver when ambiguous), or `null`
     * when none is visible.
     */
    fun resolvedPackage(intent: Intent): String?

    /** Starts [intent] on [displayId]. */
    fun start(
        intent: Intent,
        displayId: Int,
    ): StartResult
}

/** Result of [ScreenSystem.start]. */
internal sealed interface StartResult {
    /** The system accepted the start. */
    data object Started : StartResult

    /** No activity handles the intent. */
    data object NotFound : StartResult

    /** The system refused, e.g. a permission or background-start restriction; [detail] in English. */
    data class Denied(
        val detail: String,
    ) : StartResult
}

/**
 * The user's clipboard, saved and restored around a paste (phase1 §3.3 input fallback).
 * Implementations never throw.
 */
internal interface ClipboardAccess {
    /**
     * The current clip, or `null` when it cannot be read and so could not be restored
     * (Android 10+ hides the clipboard from apps in the background).
     */
    fun save(): SavedClip?

    /** Replaces the clip with [text]; [sensitive] hides it from clipboard previews (API 33+). */
    fun put(
        text: String,
        sensitive: Boolean,
    ): Boolean

    /** Puts [saved] back. */
    fun restore(saved: SavedClip)
}

/** A clip saved by [ClipboardAccess.save]; [clip] is `null` when the clipboard was empty. */
internal class SavedClip(
    val clip: ClipData?,
)

/** [ScreenSystem] backed by the app's own [Context] (main display, no privileged helper). */
internal class AndroidScreenSystem(
    context: Context,
) : ScreenSystem {
    private val app = context.applicationContext
    private val pm: PackageManager = app.packageManager
    private val activities = ConcurrentHashMap<String, Boolean>()
    private val pickers: Set<String> by lazy(::resolvePickers)

    override val ownPackage: String = app.packageName

    override val clipboard: ClipboardAccess = AndroidClipboard(app.getSystemService(ClipboardManager::class.java))

    override fun uptimeMs(): Long = SystemClock.uptimeMillis()

    override fun locked(): Boolean {
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        return keyguard?.isKeyguardLocked == true || power?.isInteractive == false
    }

    override fun pickerPackages(): Set<String> = pickers

    override fun isActivity(
        packageName: String,
        className: String,
    ): Boolean =
        activities.getOrPut("$packageName/$className") {
            try {
                activityInfo(ComponentName(packageName, className))
                true
            } catch (expectedNotActivity: PackageManager.NameNotFoundException) {
                // Dialogs and popups also send window-state changes; their class is not an activity.
                false
            }
        }

    override fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)

    override fun resolvedPackage(intent: Intent): String? {
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        return info?.activityInfo?.packageName
    }

    override fun start(
        intent: Intent,
        displayId: Int,
    ): StartResult {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        return try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
            StartResult.Started
        } catch (expectedNoHandler: ActivityNotFoundException) {
            // Nothing handles the intent (e.g. no calendar app): an expected outcome for the model.
            StartResult.NotFound
        } catch (refused: SecurityException) {
            // Background-start limits or a protected activity: reported, not retried.
            StartResult.Denied(refused.message ?: "SecurityException")
        }
    }

    private fun activityInfo(component: ComponentName) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
        }

    // Adapted from Mika's SystemPickers (Apache-2.0, see NOTICE). Modified by PhoneAgent contributors.
    private fun resolvePickers(): Set<String> {
        val intents =
            buildList {
                add(Intent(ACTION_REQUEST_PERMISSIONS))
                add(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain"), null))
                add(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        return intents.mapNotNull(::resolvedPackage).toSet() + SYSTEM_PACKAGE
    }

    private companion object {
        /** Hosts the platform's own resolver and chooser activities. */
        const val SYSTEM_PACKAGE = "android"
        const val ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"
    }
}

/** [ClipboardAccess] over the platform [ClipboardManager]. */
internal class AndroidClipboard(
    private val manager: ClipboardManager?,
) : ClipboardAccess {
    override fun save(): SavedClip? {
        // A background app reads null whether or not the clipboard is empty, so null cannot be restored safely.
        val clip = manager?.primaryClip ?: return null
        return SavedClip(clip)
    }

    override fun put(
        text: String,
        sensitive: Boolean,
    ): Boolean {
        val target = manager ?: return false
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        target.setPrimaryClip(clip)
        return true
    }

    override fun restore(saved: SavedClip) {
        val target = manager ?: return
        val clip = saved.clip
        if (clip != null) target.setPrimaryClip(clip) else target.clearPrimaryClip()
    }

    private companion object {
        const val CLIP_LABEL = "PhoneAgent"
    }
}
