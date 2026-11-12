package dev.phoneagent.app.adapters.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.SystemIntentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

/** 64 KiB of adversarial text. */
private const val LONG = 64 * 1024

/** Stack of the thread the checks run on: recursion once per repeat fails on it at any length tried here. */
private const val SMALL_STACK = 256L * 1024

/**
 * The regexes of app/src/main against long text from the model, the screen or the package
 * manager (fix list fwd, the regex sweep): a regex that repeats a group (`(…)+`, `(?:…)*`)
 * recurses once per repeat on the JVM, which a few thousand repeats overflow. Each check runs on a
 * thread with a small stack, after a short warm-up on the test thread.
 *
 * - link schemes `[a-z][a-z0-9+.-]*` (model): a class repeated, no group;
 * - dial numbers `[0-9+*#() -]{1,32}` (model): bounded, no group;
 * - alarm times `([01]?[0-9]|2[0-3]):([0-5][0-9])` (model): groups, none repeated;
 * - package names (model's settings package, the package manager's apps): split at the dots, each
 *   part a class repeated ([PackageNames]); it was `[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+`;
 * - field formatting `[\s\-()]` and `\s` (a field's text on screen): one character each.
 *
 * Model fields of system intents are limited to [LaunchIntents.MAX_FIELD] before any of these
 * run, so they are tried at that length and at [LONG].
 */
@RunWith(AndroidJUnit4::class)
class LongTextRegexTest {
    /** Runs [check] on a thread with [SMALL_STACK] and returns what it returned, or throws what it threw. */
    private fun <T> onSmallStack(check: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, { result = runCatching(check) }, "small-stack", SMALL_STACK)
        thread.start()
        thread.join()
        return checkNotNull(result).getOrThrow()
    }

    private fun system(
        kind: SystemIntentKind,
        vararg fields: Pair<String, String>,
    ) = LaunchIntents.system(LaunchTarget.SystemIntent(kind, fields.toMap()), ZoneOffset.UTC)

    /** A package name of [length] characters: one letter, then ".b" repeated. */
    private fun dotted(length: Int): String = "a" + ".b".repeat((length - 1) / 2)

    @Test
    fun aSettingsPackageOfManyPartsIsCheckedWithoutRecursion() {
        val short = system(SystemIntentKind.SETTINGS, "page" to "app_details", "package" to "com.example.app")
        assertTrue(short is LaunchIntents.Built.Ready)

        val longest = dotted(LaunchIntents.MAX_FIELD)
        val built = onSmallStack { system(SystemIntentKind.SETTINGS, "page" to "app_details", "package" to longest) }
        assertTrue("valid at the longest a field may be: $built", built is LaunchIntents.Built.Ready)
        val broken =
            onSmallStack { system(SystemIntentKind.SETTINGS, "page" to "app_details", "package" to "$longest!") }
        assertEquals(
            "settings page app_details needs a valid 'package'",
            (broken as LaunchIntents.Built.Invalid).reason,
        )
    }

    @Test
    fun packageNamesAreDottedJavaNames() {
        val valid = listOf("com.example.app", "a.b", "A_1.b2", "com.example.app_2")
        val invalid = listOf("android", "", ".a.b", "a.b.", "a..b", "1a.b", "a.1b", "a.b-c", "a b.c", "_a.b")

        val wrong =
            valid.filterNot(PackageNames::isValid).map { "valid: '$it'" } +
                invalid.filter(PackageNames::isValid).map { "invalid: '$it'" }

        assertEquals(emptyList<String>(), wrong)
        assertTrue(onSmallStack { PackageNames.isValid(dotted(LONG)) })
        assertFalse(onSmallStack { PackageNames.isValid(dotted(LONG) + "!") })
    }

    @Test
    fun theOtherRegexesTakeLongTextWithoutRecursion() {
        val letters = "a" + "b".repeat(LONG)
        val tooLong = "1".repeat(LONG)
        val longest = "1".repeat(LaunchIntents.MAX_FIELD)
        val spaced = " -()".repeat(LONG / 4)
        val failures = ArrayList<String>()

        fun check(
            what: String,
            ok: Boolean,
        ) {
            if (!ok) failures += what
        }
        check("warm-up", TextEntry.Formatting.SEPARATORS.matches("1 2", "12"))
        onSmallStack {
            val scheme = LaunchIntents.link(LaunchTarget.Link("$letters:x"))
            check(
                "a long scheme is read: $scheme",
                (scheme as? LaunchIntents.Built.Invalid)?.reason?.contains("are not opened") == true,
            )
            val brokenScheme = LaunchIntents.link(LaunchTarget.Link("$letters!:x"))
            check("a long broken scheme", brokenScheme is LaunchIntents.Built.Invalid)
            for (field in listOf(longest, tooLong)) {
                check(
                    "dial ${field.length}",
                    system(SystemIntentKind.DIAL, "number" to field) is LaunchIntents.Built.Invalid,
                )
                check(
                    "alarm ${field.length}",
                    system(SystemIntentKind.ALARM, "time" to field) is LaunchIntents.Built.Invalid,
                )
                check(
                    "alarm 12: ${field.length}",
                    system(SystemIntentKind.ALARM, "time" to "12:$field") is LaunchIntents.Built.Invalid,
                )
            }
            check("separators", TextEntry.Formatting.SEPARATORS.matches("1$spaced", "1"))
            check("spaces", TextEntry.Formatting.SPACES.matches("1" + " ".repeat(LONG), "1"))
        }

        assertEquals(emptyList<String>(), failures)
    }
}
