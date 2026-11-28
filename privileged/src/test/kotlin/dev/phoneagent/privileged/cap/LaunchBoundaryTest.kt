package dev.phoneagent.privileged.cap

import android.Manifest
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import dev.phoneagent.privileged.StartResult
import dev.phoneagent.privileged.cap.CapabilityHarness.Companion.APP
import dev.phoneagent.privileged.cap.CapabilityHarness.Companion.APP_UID
import dev.phoneagent.privileged.cap.CapabilityHarness.Companion.filter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppOpsManager

/**
 * The launch boundary (ADR privileged display and input, decision 1): the helper starts only the
 * activity the app could start with `startActivity` itself, and lends it nothing of the shell's.
 * Every case runs on every start path, through the Proxy, PrivilegedCore and Launcher: the direct
 * `startActivityAsUser` and the `am start` fallback, each warm and cold (force-stop first). The
 * expectations follow the activity manager's own checks for the app's uid
 * (`ActivityTaskSupervisor.checkStartAnyActivityPermission`), and each refusal names its reason.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LaunchBoundaryTest(
    private val path: Path,
) {
    /** A way the helper puts an activity on a display. */
    enum class Path(
        val direct: Boolean,
        val cold: Boolean,
    ) {
        DIRECT(direct = true, cold = false),
        DIRECT_COLD(direct = true, cold = true),
        AM(direct = false, cold = false),
        AM_COLD(direct = false, cold = true),
    }

    @get:Rule
    val folder = TemporaryFolder()
    private lateinit var helper: CapabilityHarness
    private var display = -1

    @Before
    fun setUp() {
        helper = CapabilityHarness(stateDir = folder.root)
        display = helper.display()
        assertTrue("display created: $display", display >= 0)
        helper.bridge.atm.startUnavailable = !path.direct
    }

    @After
    fun close() {
        helper.close()
    }

    /** What reached the platform: the intent given to the direct start, or the `am start` command. */
    private data class Launch(
        val component: ComponentName?,
        val flags: Int,
        val user: Int,
    )

    private fun launches(): List<Launch> =
        if (path.direct) {
            helper.bridge.atm.started
                .map { (intent, user) -> Launch(intent.component, intent.flags, user) }
        } else {
            amCommands("start").map { command ->
                Launch(
                    command.after("-n")?.let(ComponentName::unflattenFromString),
                    command.after("-f")?.toInt() ?: 0,
                    checkNotNull(command.after("--user")).toInt(),
                )
            }
        }

    private fun amCommands(verb: String): List<List<String>> =
        helper.commands.of("am").filter { it.getOrNull(1) == verb }

    private fun List<String>.after(option: String): String? = indexOf(option).takeIf { it >= 0 }?.let { get(it + 1) }

    private fun targets() = LaunchTargets(helper.context, ServedApp(APP, APP_UID))

    private fun start(
        intent: Intent,
        userId: Int = 0,
    ): Int {
        helper.commands.commands.clear()
        helper.bridge.atm.started
            .clear()
        return helper.core.startActivity(display, intent, userId, path.cold)
    }

    /** [intent] is refused for [reason]: nothing starts and nothing is force-stopped. */
    private fun assertRefused(
        what: String,
        intent: Intent,
        reason: LaunchRefusal,
        userId: Int = 0,
    ) {
        assertEquals(what, reason.result, start(intent, userId))
        assertEquals(
            "$what: nothing reached the activity manager",
            emptyList<Pair<Intent, Int>>(),
            helper.bridge.atm.started,
        )
        assertEquals("$what: no am command ran", emptyList<List<String>>(), helper.commands.of("am"))
        if (reason != LaunchRefusal.BAD_REQUEST && reason != LaunchRefusal.NESTED_INTENT) {
            assertEquals(
                "$what: the reason",
                reason,
                (targets().check(intent) as? LaunchTargets.Target.Refused)?.reason,
            )
        }
    }

    /** [intent] starts [checked] and nothing else, in user 0, with no URI grant; a cold start stops it first. */
    private fun assertStarts(
        what: String,
        intent: Intent,
        checked: ComponentName,
        userId: Int = 0,
    ) {
        var startedBeforeStop = -1
        helper.commands.answer = { command ->
            if (command.getOrNull(1) == "force-stop") startedBeforeStop = launches().size
            CommandResult(0, "")
        }
        assertEquals(what, StartResult.OK, start(intent, userId))
        assertEquals("$what: exactly the checked activity, once", listOf(checked), launches().map { it.component })
        val launch = launches().single()
        assertEquals("$what: in the helper's user", 0, launch.user)
        assertEquals("$what: no URI grant", 0, launch.flags and URI_GRANTS)
        assertEquals(
            "$what: the root of a new task",
            Intent.FLAG_ACTIVITY_NEW_TASK,
            launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
        if (!path.direct) {
            assertEquals(
                "$what: only am start ran",
                emptyList<Pair<Intent, Int>>(),
                helper.bridge.atm.started,
            )
        }
        val stops =
            if (path.cold) {
                listOf(
                    listOf("am", "force-stop", "--user", "0", checked.packageName),
                )
            } else {
                emptyList()
            }
        assertEquals("$what: force-stop", stops, amCommands("force-stop"))
        if (path.cold) assertEquals("$what: stopped before it started", 0, startedBeforeStop)
        val allowed = targets().check(intent) as? LaunchTargets.Target.Allowed
        assertEquals("$what: the check allows it", checked, allowed?.activity)
    }

    private fun component(intent: ComponentName): Intent = Intent().setComponent(intent)

    // ---- exported ----

    @Test
    fun anotherAppsUnexportedActivityIsRefused() {
        val hidden = helper.install(OTHER, activity = "$OTHER.Internal", exported = false)
        assertRefused("another app's unexported activity", component(hidden), LaunchRefusal.NOT_EXPORTED)
    }

    @Test
    fun theAppsOwnUnexportedActivityStarts() {
        val own = helper.install(APP, activity = "$APP.Hidden", exported = false)
        assertStarts("the app's own unexported activity", component(own), own)
    }

    @Test
    fun anotherPackageWithTheAppsAppIdIsTheAppsOwn() {
        val shared = helper.install(SHARED, exported = false, permission = GUARD, uid = APP_UID)
        assertStarts("another package, the app's app id", component(shared), shared)
    }

    // ---- permissions ----

    @Test
    fun anActivityGuardedByAPermissionTheAppLacksIsRefused() {
        val vault = helper.install(OTHER, permission = GUARD)
        assertRefused("guarded activity", component(vault), LaunchRefusal.PERMISSION_NOT_HELD)
    }

    @Test
    fun anActivityGuardedByAPermissionTheAppHoldsStarts() {
        val vault = helper.install(OTHER, permission = GUARD)
        helper.grantApp(GUARD)
        assertStarts("guarded activity, permission held", component(vault), vault)
    }

    @Test
    fun theAppsOwnActivityStartsWithoutThePermissionItRequires() {
        val own = helper.install(APP, activity = "$APP.Vault", permission = GUARD)
        assertStarts("the app's own guarded activity", component(own), own)
    }

    @Test
    fun aCallIsRefusedWhenTheAppLacksCallPhone() {
        installDialer(guarded = true)
        assertRefused("ACTION_CALL tel:", call(), LaunchRefusal.PERMISSION_NOT_HELD)
    }

    @Test
    fun aCallStartsExactlyTheCheckedActivityOnceTheAppHoldsCallPhone() {
        val dialer = installDialer(guarded = true)
        helper.grantApp(Manifest.permission.CALL_PHONE)
        assertStarts("ACTION_CALL tel:, CALL_PHONE held", call(), dialer)
    }

    @Test
    fun anActivityGuardedOnlyByItsApplicationsPermissionIsRefused() {
        val wallet = helper.install(OTHER, appPermission = GUARD)
        assertRefused("application-level permission", component(wallet), LaunchRefusal.PERMISSION_NOT_HELD)
    }

    @Test
    fun anActivityGuardedOnlyByItsApplicationsPermissionStartsOnceTheAppHoldsIt() {
        val wallet = helper.install(OTHER, appPermission = GUARD)
        helper.grantApp(GUARD)
        assertStarts("application-level permission held", component(wallet), wallet)
    }

    @Test
    fun anActivityThatClearsItsApplicationsPermissionStarts() {
        val open = helper.install(OTHER, permission = "", appPermission = GUARD)
        assertStarts("android:permission=\"\" under an application permission", component(open), open)
    }

    @Test
    fun aHeldPermissionWhoseAppOpIsNotAllowedIsRefused() {
        installDialer(guarded = true)
        helper.grantApp(Manifest.permission.CALL_PHONE)
        for (mode in listOf(AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_DEFAULT)) {
            callPhoneOp(mode)
            assertRefused("CALL_PHONE held, app op mode $mode", call(), LaunchRefusal.APP_OP_DENIED)
        }
    }

    @Test
    fun theAppsOwnActivityIsRefusedWhenTheAppOpOfItsPermissionIsNotAllowed() {
        val own = helper.install(APP, activity = "$APP.Caller", permission = Manifest.permission.CALL_PHONE)
        helper.grantApp(Manifest.permission.CALL_PHONE)
        callPhoneOp(AppOpsManager.MODE_IGNORED)
        assertRefused("the app's own activity, app op ignored", component(own), LaunchRefusal.APP_OP_DENIED)
    }

    @Test
    @Config(shadows = [UnreadableAppOps::class])
    fun anAppOpTheHelperMayNotReadIsRefused() {
        installDialer(guarded = true)
        helper.grantApp(Manifest.permission.CALL_PHONE)
        assertRefused("CALL_PHONE held, app op unreadable", call(), LaunchRefusal.APP_OP_DENIED)
    }

    // ---- actions tied to a runtime permission ----

    @Test
    fun aCallToAnUnguardedHandlerIsRefusedWhenTheAppRequestsCallPhoneButLacksIt() {
        installDialer(guarded = false)
        helper.appRequests(Manifest.permission.CALL_PHONE)
        assertRefused("ACTION_CALL, CALL_PHONE requested", call(), LaunchRefusal.ACTION_PERMISSION_NOT_HELD)
    }

    @Test
    fun aCallToAnUnguardedHandlerIsRefusedWhenTheAppOpOfCallPhoneIsNotAllowed() {
        installDialer(guarded = false)
        helper.appRequests(Manifest.permission.CALL_PHONE)
        helper.grantApp(Manifest.permission.CALL_PHONE)
        callPhoneOp(AppOpsManager.MODE_IGNORED)
        assertRefused("ACTION_CALL, app op ignored", call(), LaunchRefusal.APP_OP_DENIED)
    }

    @Test
    fun aCallToAnUnguardedHandlerStartsWhenTheAppHoldsCallPhoneAndItsAppOpIsAllowed() {
        val dialer = installDialer(guarded = false)
        helper.appRequests(Manifest.permission.CALL_PHONE)
        helper.grantApp(Manifest.permission.CALL_PHONE)
        callPhoneOp(AppOpsManager.MODE_ALLOWED)
        assertStarts("ACTION_CALL, CALL_PHONE held, app op allowed", call(), dialer)
    }

    @Test
    fun aCallToAnUnguardedHandlerStartsWhenTheAppDoesNotRequestCallPhone() {
        val dialer = installDialer(guarded = false)
        assertStarts("ACTION_CALL, CALL_PHONE not requested", call(), dialer)
    }

    /** The platform's table and [LaunchTargets.ACTION_PERMISSIONS]: an entry missing from either fails. */
    @Test
    fun everyActionTiedToARuntimePermissionIsRefusedWhenTheAppRequestsItButLacksIt() {
        val actions = PLATFORM_ACTION_PERMISSIONS.keys + LaunchTargets.ACTION_PERMISSIONS.keys
        actions.forEachIndexed { handler, action ->
            val permission = PLATFORM_ACTION_PERMISSIONS[action] ?: LaunchTargets.ACTION_PERMISSIONS.getValue(action)
            helper.install("com.example.handler$handler", filter = filter(action))
            helper.appRequests(permission)
            assertRefused("$action, $permission requested", Intent(action), LaunchRefusal.ACTION_PERMISSION_NOT_HELD)
        }
    }

    // ---- activity-alias ----

    @Test
    fun anExportedAliasOfAnUnexportedActivityStarts() {
        helper.install(OTHER, activity = EDITOR, exported = false)
        val alias = helper.install(OTHER, activity = "$OTHER.QuickNote", exported = true, targetActivity = EDITOR)
        assertStarts("exported alias, unexported target", component(alias), alias)
    }

    @Test
    fun anUnexportedAliasOfAnExportedActivityIsRefused() {
        helper.install(OTHER, activity = EDITOR, exported = true)
        val alias = helper.install(OTHER, activity = "$OTHER.QuickNote", exported = false, targetActivity = EDITOR)
        assertRefused("unexported alias", component(alias), LaunchRefusal.NOT_EXPORTED)
    }

    @Test
    fun anAliasWithItsOwnPermissionNeedsIt() {
        helper.install(OTHER, activity = EDITOR)
        val alias = helper.install(OTHER, activity = "$OTHER.QuickNote", permission = GUARD, targetActivity = EDITOR)
        assertRefused("alias with a permission", component(alias), LaunchRefusal.PERMISSION_NOT_HELD)
    }

    @Test
    fun anAliasNeedsNeitherItsTargetsNorItsApplicationsPermission() {
        helper.install(OTHER, activity = EDITOR, permission = GUARD, appPermission = GUARD)
        val alias = helper.install(OTHER, activity = "$OTHER.QuickNote", targetActivity = EDITOR)
        assertStarts("alias without a permission", component(alias), alias)
    }

    // ---- resolution ----

    @Test
    fun anIntentWithSeveralHandlersIsRefused() {
        val view = filter(Intent.ACTION_VIEW, scheme = "https")
        helper.install("com.example.browser", filter = view)
        helper.install("com.example.reader", filter = view)
        assertRefused("two handlers", Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)), LaunchRefusal.SEVERAL_HANDLERS)
    }

    @Test
    fun nothingToHandleTheIntentIsNotFound() {
        val missing = ComponentName("com.example.gone", "com.example.gone.Main")
        assertRefused("missing component", component(missing), LaunchRefusal.NOT_FOUND)
        assertRefused("no handler", Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)), LaunchRefusal.NOT_FOUND)
    }

    // ---- URI grants ----

    @Test
    fun everyUriGrantFlagIsClearedBeforeTheStart() {
        val settings = installSettings()
        for (flag in GRANT_FLAGS + URI_GRANTS) {
            assertStarts("flags $flag", component(settings).setData(Uri.parse(DOC)).addFlags(flag), settings)
        }
    }

    @Test
    fun clipDataIsStartedWithoutGrantsOrNotAtAll() {
        val settings = installSettings()
        val intent =
            Intent(Settings.ACTION_SETTINGS).apply {
                clipData = ClipData.newRawUri("", Uri.parse(DOC))
                addFlags(URI_GRANTS)
            }
        if (path.direct) {
            assertStarts("ClipData", intent, settings)
            assertNotNull(
                "the ClipData itself is kept",
                helper.bridge.atm.started
                    .single()
                    .first.clipData,
            )
        } else {
            assertEquals("am start cannot carry ClipData", StartResult.ERROR, start(intent))
            assertEquals("no am start ran", emptyList<List<String>>(), amCommands("start"))
        }
    }

    // ---- what the helper does not even look at ----

    @Test
    fun anIntentThatCarriesAnotherIntentIsRefused() {
        installSettings()
        val inner = Intent(Settings.ACTION_SETTINGS)
        assertRefused(
            "EXTRA_INTENT",
            Intent(Settings.ACTION_SETTINGS).putExtra(Intent.EXTRA_INTENT, inner),
            LaunchRefusal.NESTED_INTENT,
        )
        assertRefused(
            "selector",
            Intent(Settings.ACTION_SETTINGS).also { it.selector = inner },
            LaunchRefusal.NESTED_INTENT,
        )
    }

    @Test
    fun anotherUserIsRefused() {
        installSettings()
        assertRefused("user 10", Intent(Settings.ACTION_SETTINGS), LaunchRefusal.BAD_REQUEST, userId = OTHER_USER)
    }

    @Test
    fun theCurrentUserStartsInTheHelpersUser() {
        val settings = installSettings()
        assertStarts("USER_CURRENT", Intent(Settings.ACTION_SETTINGS), settings, userId = USER_CURRENT)
    }

    private fun installDialer(guarded: Boolean): ComponentName =
        helper.install(
            DIALER,
            activity = "$DIALER.components.UserCallActivity",
            permission = Manifest.permission.CALL_PHONE.takeIf { guarded },
            filter = filter(Intent.ACTION_CALL, scheme = "tel"),
        )

    private fun call(): Intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:10086"))

    private fun callPhoneOp(mode: Int) {
        val appOps = helper.context.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_CALL_PHONE, APP_UID, APP, mode)
    }

    private fun installSettings(): ComponentName =
        helper.install("com.android.settings", filter = filter(Settings.ACTION_SETTINGS))

    /** An `AppOpsManager` that refuses to tell the helper another app's modes. */
    @Implements(AppOpsManager::class)
    class UnreadableAppOps : ShadowAppOpsManager() {
        // The shadow's method is deprecated with the platform's; this one only replaces it.
        @Suppress("OVERRIDE_DEPRECATION")
        @Implementation
        override fun checkOpNoThrow(
            op: String?,
            uid: Int,
            packageName: String?,
        ): Int = throw SecurityException("the shell may not read app ops on this build")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun paths(): List<Array<Any>> = Path.entries.map { arrayOf(it) }

        private const val OTHER = "com.example.other"
        private const val SHARED = "$APP.extra"
        private const val EDITOR = "$OTHER.Editor"
        private const val DIALER = "com.android.server.telecom"
        private const val GUARD = "com.example.permission.OPEN"
        private const val PAGE = "https://example.com/"
        private const val DOC = "content://com.example.files/doc/1"
        private const val OTHER_USER = 10

        /** `UserHandle.USER_CURRENT`. */
        private const val USER_CURRENT = -2

        private val GRANT_FLAGS =
            listOf(
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        private val URI_GRANTS = GRANT_FLAGS.reduce(Int::or)

        /** `ActivityTaskSupervisor.ACTION_TO_RUNTIME_PERMISSION` (API 36), written out. */
        private val PLATFORM_ACTION_PERMISSIONS =
            mapOf(
                MediaStore.ACTION_IMAGE_CAPTURE to Manifest.permission.CAMERA,
                "android.provider.action.MOTION_PHOTO_CAPTURE" to Manifest.permission.CAMERA,
                MediaStore.ACTION_VIDEO_CAPTURE to Manifest.permission.CAMERA,
                Intent.ACTION_CALL to Manifest.permission.CALL_PHONE,
            )
    }
}
