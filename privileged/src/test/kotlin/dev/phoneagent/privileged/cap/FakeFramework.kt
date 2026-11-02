package dev.phoneagent.privileged.cap

import android.app.ActivityManager
import android.app.TaskInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.privileged.IPrivilegedCore
import dev.phoneagent.privileged.helper.PrivilegedCore
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBinder
import java.io.File

/**
 * Stands in for `IActivityTaskManager`: [SystemBridge] finds methods by name and parameter count,
 * so these have the platform's names and arities.
 */
internal class FakeActivityTaskManager {
    /** What `getTasks` reports, most recently used first. */
    val tasks = mutableListOf<ActivityManager.RunningTaskInfo>()

    /** Every intent `startActivityAsUser` was asked to start, with the user it was asked for. */
    val started = mutableListOf<Pair<Intent, Int>>()
    var startResult = 0

    @Suppress("UNUSED_PARAMETER", "unused") // Signature of IActivityTaskManager.getTasks.
    fun getTasks(
        maxNum: Int,
        filterOnlyVisibleRecents: Boolean,
        keepIntentExtra: Boolean,
        displayId: Int,
    ): List<ActivityManager.RunningTaskInfo> = tasks.toList()

    // LongParameterList: the twelve parameters of IActivityTaskManager.startActivityAsUser.
    @Suppress("UNUSED_PARAMETER", "unused", "LongParameterList")
    fun startActivityAsUser(
        caller: Any?,
        callingPackage: String?,
        callingFeatureId: String?,
        intent: Intent,
        resolvedType: String?,
        resultTo: Any?,
        resultWho: String?,
        requestCode: Int,
        flags: Int,
        profilerInfo: Any?,
        options: Bundle?,
        userId: Int,
    ): Int {
        started += intent to userId
        return startResult
    }
}

/** Stands in for `IWindowManager`. */
internal class FakeWindowManager {
    /** What `isKeyguardLocked` answers; null makes it throw, as a dead or changed service would. */
    var keyguardLocked: Boolean? = false

    /** Whether `setDisplayImePolicy` takes effect. */
    var imeAccepted = true
    private val imePolicies = mutableMapOf<Int, Int>()

    fun isKeyguardLocked(): Boolean = keyguardLocked ?: error("IWindowManager went away")

    fun setDisplayImePolicy(
        displayId: Int,
        imePolicy: Int,
    ) {
        if (imeAccepted) imePolicies[displayId] = imePolicy
    }

    fun getDisplayImePolicy(displayId: Int): Int = imePolicies[displayId] ?: IME_POLICY_FALLBACK

    private companion object {
        /** `WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY`: the keyboard opens on the main screen. */
        const val IME_POLICY_FALLBACK = 1
    }
}

internal class FakeBridge(
    context: Context,
    val atm: FakeActivityTaskManager = FakeActivityTaskManager(),
    val wm: FakeWindowManager = FakeWindowManager(),
) : SystemBridge(context) {
    override val activityTaskManager: Any get() = atm
    override val windowManager: Any get() = wm
}

/** Records every command instead of running it; [answer] decides the result. */
internal class RecordingCommands : CommandRunner {
    val commands = mutableListOf<List<String>>()
    var answer: (List<String>) -> CommandResult = { CommandResult(0, "") }

    override fun run(command: List<String>): CommandResult {
        commands += command
        return answer(command)
    }

    /** Commands whose program is [program]. */
    fun of(program: String): List<List<String>> = commands.filter { it.firstOrNull() == program }
}

/**
 * The capabilities wired as on a device ([AndroidCapabilities] behind [PrivilegedCore]), reached
 * the way the app reaches them: through the generated Proxy and a real transact, as the app's uid.
 * Only the framework services ([FakeBridge]) and the shell commands ([RecordingCommands]) are fake.
 */
internal class CapabilityHarness(
    sdkInt: Int = 36,
    val stateDir: File,
    /** Answers the commands, including those the helper runs while it starts. */
    val commands: RecordingCommands = RecordingCommands(),
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val bridge = FakeBridge(context)
    internal val capabilities =
        AndroidCapabilities.create(context, ServedApp(APP, APP_UID), Platform(bridge, sdkInt, commands, stateDir))
    private val readers = mutableListOf<ImageReader>()

    /** The helper as the app sees it. */
    val core: IPrivilegedCore =
        IPrivilegedCore.Stub.asInterface(RemoteOnly(PrivilegedCore(APP_UID, capabilities) {}))

    init {
        ShadowBinder.setCallingUid(APP_UID)
    }

    /** A new agent display, created through the core. */
    fun display(): Int {
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2).also { readers += it }
        return core.createDisplay(reader.surface, WIDTH, HEIGHT, DPI)
    }

    fun close() {
        readers.forEach(ImageReader::close)
        ShadowBinder.reset()
    }

    /** Installs [packageName] with one activity, [exported] and guarded by [permission], matching [filter]. */
    fun install(
        packageName: String,
        activity: String = "$packageName.Main",
        exported: Boolean = true,
        permission: String? = null,
        filter: IntentFilter? = null,
    ): ComponentName {
        val app =
            ApplicationInfo().apply {
                this.packageName = packageName
                uid = OTHER_UID
            }
        val packages = shadowOf(context.packageManager)
        packages.installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = app
            },
        )
        val component = ComponentName(packageName, activity)
        packages.addOrUpdateActivity(
            ActivityInfo().apply {
                name = activity
                this.packageName = packageName
                applicationInfo = app
                this.exported = exported
                this.permission = permission
            },
        )
        if (filter != null) packages.addIntentFilterForActivity(component, filter)
        return component
    }

    /** Grants [permission] to the app's uid, as the package manager would. */
    fun grantApp(permission: String) {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(NO_PID, APP_UID, permission)
    }

    companion object {
        const val APP = "dev.phoneagent"
        const val APP_UID = 10_214
        const val OTHER_UID = 10_300
        const val NO_PID = -1
        const val WIDTH = 1_080
        const val HEIGHT = 2_340
        const val DPI = 440

        /** An intent filter for [action] in the default category, as `startActivity` resolves. */
        fun filter(
            action: String,
            scheme: String? = null,
        ): IntentFilter =
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                if (scheme != null) addDataScheme(scheme)
            }

        /** A task row as `getTasks` reports it. */
        fun task(
            taskId: Int,
            displayId: Int,
            top: ComponentName,
            visible: Boolean = true,
        ): ActivityManager.RunningTaskInfo =
            ActivityManager.RunningTaskInfo().apply {
                set("taskId", taskId)
                set("displayId", displayId)
                set("topActivity", top)
                set("baseActivity", top)
                set("userId", 0)
                set("isVisible", visible)
                set("topActivityType", 1)
            }

        private fun TaskInfo.set(
            field: String,
            value: Any,
        ) {
            TaskInfo::class.java.getField(field).set(this, value)
        }
    }
}

/** A binder whose local interface is hidden, so `asInterface` builds the generated Proxy around it. */
private class RemoteOnly(
    private val binder: IBinder,
) : IBinder by binder {
    override fun queryLocalInterface(descriptor: String): IInterface? = null
}
