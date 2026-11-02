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
import android.view.InputEvent
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.privileged.IPrivilegedCore
import dev.phoneagent.privileged.OverTheWire
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

    /** What `startActivityAsUser` answers; anything but an Int is a framework that changed. */
    var startResult: Any? = 0

    /** Makes `startActivityAsUser` fail through reflection, so the helper falls back to `am start`. */
    var startUnavailable = false

    /** How many more `getTasks` answers fail while they are read, as a list torn mid-read would. */
    var tornReads = 0

    /** Whether `registerTaskStackListener` accepts a listener; it throws otherwise, so the watch polls. */
    var acceptsListener = false

    /** The `ITaskStackListener` registered last. */
    var listener: Any? = null

    @Suppress("UNUSED_PARAMETER", "unused") // Signature of IActivityTaskManager.getTasks.
    fun getTasks(
        maxNum: Int,
        filterOnlyVisibleRecents: Boolean,
        keepIntentExtra: Boolean,
        displayId: Int,
    ): List<ActivityManager.RunningTaskInfo> {
        if (tornReads == 0) return tasks.toList()
        tornReads--
        return object : AbstractList<ActivityManager.RunningTaskInfo>() {
            override val size = 1

            override fun get(index: Int): ActivityManager.RunningTaskInfo =
                error("the task list changed while it was read")
        }
    }

    @Suppress("unused") // Signature of IActivityTaskManager.registerTaskStackListener.
    fun registerTaskStackListener(listener: Any?) {
        check(acceptsListener) { "no task stack listeners on this fake" }
        this.listener = listener
    }

    @Suppress("UNUSED_PARAMETER", "unused") // Signature of IActivityTaskManager.unregisterTaskStackListener.
    fun unregisterTaskStackListener(listener: Any?) {
        this.listener = null
    }

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
    ): Any? {
        check(!startUnavailable) { "startActivityAsUser is not available on this build" }
        started += intent to userId
        return startResult
    }
}

/** An `IActivityTaskManager` of a build without `startActivityAsUser`; the rest is [atm]. */
internal class ActivityTaskManagerWithoutStart(
    private val atm: FakeActivityTaskManager,
) {
    @Suppress("unused") // Signature of IActivityTaskManager.getTasks.
    fun getTasks(
        maxNum: Int,
        filterOnlyVisibleRecents: Boolean,
        keepIntentExtra: Boolean,
        displayId: Int,
    ): List<ActivityManager.RunningTaskInfo> =
        atm.getTasks(maxNum, filterOnlyVisibleRecents, keepIntentExtra, displayId)

    @Suppress("unused") // Signature of IActivityTaskManager.registerTaskStackListener.
    fun registerTaskStackListener(listener: Any?) = atm.registerTaskStackListener(listener)

    @Suppress("unused") // Signature of IActivityTaskManager.unregisterTaskStackListener.
    fun unregisterTaskStackListener(listener: Any?) = atm.unregisterTaskStackListener(listener)
}

/** Stands in for `IWindowManager`. */
internal class FakeWindowManager {
    /** What `isKeyguardLocked` answers; null makes it throw, as a dead or changed service would. */
    var keyguardLocked: Boolean? = false

    /** When set, what `isKeyguardLocked` answers instead: a framework whose answer changed type. */
    var keyguardAnswer: Any? = null

    /** Whether `setDisplayImePolicy` takes effect. */
    var imeAccepted = true
    private val imePolicies = mutableMapOf<Int, Int>()

    /** When set, what `getDisplayImePolicy` answers instead of the policy that was set. */
    var imeReadBack: Any? = null

    /** Makes `getDisplayImePolicy` throw, as a ROM that changed or guarded it would. */
    var imeGetterFails = false

    fun isKeyguardLocked(): Any = keyguardAnswer ?: keyguardLocked ?: error("IWindowManager went away")

    fun setDisplayImePolicy(
        displayId: Int,
        imePolicy: Int,
    ) {
        if (imeAccepted) imePolicies[displayId] = imePolicy
    }

    fun getDisplayImePolicy(displayId: Int): Any {
        check(!imeGetterFails) { "getDisplayImePolicy is guarded on this ROM" }
        return imeReadBack ?: imePolicies[displayId] ?: IME_POLICY_FALLBACK
    }

    private companion object {
        /** `WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY`: the keyboard opens on the main screen. */
        const val IME_POLICY_FALLBACK = 1
    }
}

/** An `IWindowManager` of a ROM that dropped `getDisplayImePolicy`; the rest is [wm]. */
internal class WindowManagerWithoutImeGetter(
    private val wm: FakeWindowManager,
) {
    fun isKeyguardLocked(): Any = wm.isKeyguardLocked()

    fun setDisplayImePolicy(
        displayId: Int,
        imePolicy: Int,
    ) {
        wm.setDisplayImePolicy(displayId, imePolicy)
    }
}

/** An `IWindowManager` of a ROM that dropped `isKeyguardLocked`; the rest is [wm]. */
internal class WindowManagerWithoutKeyguard(
    private val wm: FakeWindowManager,
) {
    fun setDisplayImePolicy(
        displayId: Int,
        imePolicy: Int,
    ) {
        wm.setDisplayImePolicy(displayId, imePolicy)
    }

    fun getDisplayImePolicy(displayId: Int): Any = wm.getDisplayImePolicy(displayId)
}

/** Stands in for `InputManagerGlobal`. */
internal class FakeInputManager {
    var injected = 0

    /** What `injectInputEvent` answers; anything but a Boolean is a framework that changed. */
    var answer: Any? = true

    @Suppress("UNUSED_PARAMETER", "unused") // Signature of InputManagerGlobal.injectInputEvent.
    fun injectInputEvent(
        event: InputEvent,
        mode: Int,
    ): Any? {
        injected++
        return answer
    }
}

internal class FakeBridge(
    context: Context,
    val atm: FakeActivityTaskManager = FakeActivityTaskManager(),
    val wm: FakeWindowManager = FakeWindowManager(),
    val input: FakeInputManager = FakeInputManager(),
) : SystemBridge(context) {
    /** The class whose fields describe a task; another one is a framework that changed. */
    var taskInfoClass: Class<*> = TaskInfo::class.java

    /** When set, the `IWindowManager` the helper sees instead of [wm]. */
    var windowManagerOverride: Any? = null

    /** When set, the `IActivityTaskManager` the helper sees instead of [atm]. */
    var activityTaskManagerOverride: Any? = null

    override val activityTaskManager: Any get() = activityTaskManagerOverride ?: atm
    override val windowManager: Any get() = windowManagerOverride ?: wm
    override val inputManager: Any get() = input
    override val taskInfo: Class<*> get() = taskInfoClass
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

    /** While true, `VirtualDisplay.release` throws, as it does when the system server is gone. */
    var releaseFails = false

    /** Display ids whose release was attempted, in order, whether it worked or not. */
    val releaseAttempts = mutableListOf<Int>()

    private val platform =
        Platform(bridge, sdkInt, commands, stateDir) { display ->
            releaseAttempts += display.display.displayId
            check(!releaseFails) { "releasing display ${display.display.displayId} failed" }
            display.release()
        }
    internal val capabilities = AndroidCapabilities.create(context, ServedApp(APP, APP_UID), platform)
    private val readers = mutableListOf<ImageReader>()

    val wire = OverTheWire(PrivilegedCore(APP_UID, capabilities) {})

    /** The helper as the app sees it. */
    val core: IPrivilegedCore = IPrivilegedCore.Stub.asInterface(wire)

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

    /**
     * Adds [activity] to [packageName], installing the package first if needed: the app's own
     * package gets [APP_UID], any other [OTHER_UID]. The activity is [exported] and matches
     * [filter]; [appPermission] is the application's permission (set on first install) and
     * [targetActivity] makes it an activity-alias of that activity. [permission] is the manifest's
     * `android:permission`, resolved as the package manager resolves it (`ParsedActivityUtils`):
     * an activity that does not declare one (null) gets its application's, an alias gets only its
     * own, and an empty one (`android:permission=""`) is none. One parameter per manifest
     * attribute the launch checks read, hence the LongParameterList.
     */
    @Suppress("LongParameterList")
    fun install(
        packageName: String,
        activity: String = "$packageName.Main",
        exported: Boolean = true,
        permission: String? = null,
        filter: IntentFilter? = null,
        appPermission: String? = null,
        targetActivity: String? = null,
    ): ComponentName {
        val app = installed(packageName, appPermission)
        val component = ComponentName(packageName, activity)
        val declared = permission ?: app.permission.takeIf { targetActivity == null }
        packages.addOrUpdateActivity(
            ActivityInfo().apply {
                name = activity
                this.packageName = packageName
                applicationInfo = app
                this.exported = exported
                this.permission = declared?.ifEmpty { null }
                this.targetActivity = targetActivity
            },
        )
        if (filter != null) packages.addIntentFilterForActivity(component, filter)
        return component
    }

    /** Makes the app's manifest request [permissions] (granted or not, as [grantApp] decides). */
    fun appRequests(vararg permissions: String) {
        installed(APP, appPermission = null)
        packages.getInternalMutablePackageInfo(APP).requestedPermissions = arrayOf(*permissions)
    }

    private val packages get() = shadowOf(context.packageManager)

    private fun installed(
        packageName: String,
        appPermission: String?,
    ): ApplicationInfo {
        packages.getInternalMutablePackageInfo(packageName)?.let { return it.applicationInfo!! }
        val app =
            ApplicationInfo().apply {
                this.packageName = packageName
                uid = if (packageName == APP) APP_UID else OTHER_UID
                permission = appPermission
            }
        packages.installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = app
            },
        )
        return app
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
