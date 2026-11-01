package dev.phoneagent.app.adapters.screen

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.net.toUri
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.SystemIntentKind
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Builds the Intent for a [LaunchTarget] (phase1 §5 `phone_launch`, `phone_intent`). Nothing here
 * accepts an arbitrary Intent: links are VIEW + BROWSABLE without component or selector, and system
 * intents come from a fixed table whose field keys are those of contracts addendum #1 §13.
 * Field values come from the model, so they are validated here and rejected with a reason the model
 * can act on.
 */
internal object LaunchIntents {
    /** Result of a build. */
    sealed interface Built {
        /** Ready to start; [expectPackage] is the package that should come to the front, when known. */
        class Ready(
            val intent: Intent,
            val expectPackage: String?,
        ) : Built

        /** The target is invalid; [reason] is for the model, in English. */
        data class Invalid(
            val reason: String,
        ) : Built
    }

    /**
     * The only schemes `phone_launch link=` opens, with why each is safe to hand to the system as a
     * browsable VIEW. The URI is never parsed into an Intent (no `Intent.parseUri`), so a link
     * cannot name a component, extras or flags. App-specific deep-link schemes are not listed: each
     * one needs its own review before it is added here.
     */
    val LINK_SCHEMES: Map<String, String> =
        mapOf(
            "https" to "a web page in the user's browser",
            "http" to "a web page in the user's browser",
            "geo" to "a place in the maps app; navigation still needs the user",
            "mailto" to "the mail composer; sending is a separate, confirmed step",
            "market" to "an app's page in the store; installing is a separate step",
        )

    /** Why the schemes models most often try are refused (others get the generic reason). */
    private val REFUSED_SCHEMES: Map<String, String> =
        mapOf(
            "intent" to "it can start any component of any app",
            "android-app" to "it can start any component of any app",
            "content" to "it reads data from other apps",
            "file" to "it reads local files",
            "javascript" to "it runs code",
            "data" to "it embeds content instead of linking to it",
        )

    /** Longest value of one system intent field. */
    const val MAX_FIELD: Int = 4_000

    private val SCHEME = Regex("[a-z][a-z0-9+.-]*")

    private class Spec(
        val required: Set<String>,
        val optional: Set<String> = emptySet(),
    ) {
        fun describe(): String {
            val extra = if (optional.isEmpty()) "" else ", optional ${optional.sorted()}"
            return "required ${required.sorted()}$extra"
        }
    }

    private val SPECS: Map<SystemIntentKind, Spec> =
        mapOf(
            SystemIntentKind.ALARM to Spec(setOf("time"), setOf("label")),
            SystemIntentKind.TIMER to Spec(setOf("seconds"), setOf("label")),
            SystemIntentKind.CALENDAR to Spec(setOf("title", "start"), setOf("end", "location")),
            SystemIntentKind.SHARE to Spec(setOf("text")),
            SystemIntentKind.DIAL to Spec(setOf("number")),
            SystemIntentKind.NAVIGATE to Spec(setOf("place")),
            SystemIntentKind.SETTINGS to Spec(setOf("page"), setOf("package")),
        )

    /** The launcher intent of [target]'s package; [launchIntent] is `ScreenSystem.launchIntent`. */
    fun app(
        target: LaunchTarget.App,
        launchIntent: (String) -> Intent?,
    ): Built {
        val intent = launchIntent(target.packageName)
        // A cold start clears the task so the app opens at its first screen; killing the process
        // needs the privileged helper (M2).
        val reset = if (target.cold) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        return if (intent == null) {
            Built.Invalid("${target.packageName} is not installed or has no launcher activity")
        } else {
            Built.Ready(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or reset), target.packageName)
        }
    }

    /**
     * A browsable VIEW intent for [target] when its scheme is in [LINK_SCHEMES]; anything else is
     * Invalid with the reason.
     */
    fun link(target: LaunchTarget.Link): Built {
        val uri = target.uri.trim().toUri()
        val scheme = uri.scheme?.lowercase()
        val web = scheme == "http" || scheme == "https"
        return when {
            scheme == null || !SCHEME.matches(scheme) -> {
                Built.Invalid("link needs an absolute URI with a scheme, got '${target.uri}'")
            }

            scheme !in LINK_SCHEMES -> {
                val why = REFUSED_SCHEMES[scheme] ?: "it is not a web or known deep link"
                val allowed = LINK_SCHEMES.keys.sorted()
                Built.Invalid("links with scheme '$scheme' are not opened: $why; allowed: $allowed")
            }

            web && uri.host.isNullOrEmpty() -> {
                Built.Invalid("web link has no host: '${target.uri}'")
            }

            else -> {
                // Built from the parsed URI only: no component, selector, package, extras or grant flags.
                val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                Built.Ready(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null)
            }
        }
    }

    /** The whitelisted intent for [target], in time zone [zone] for calendar times. */
    fun system(
        target: LaunchTarget.SystemIntent,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Built {
        val spec = SPECS.getValue(target.kind)
        val unknown = target.fields.keys - spec.required - spec.optional
        val missing = spec.required - target.fields.keys
        val fields = target.fields.mapValues { it.value.trim() }
        val built =
            when {
                unknown.isNotEmpty() || missing.isNotEmpty() -> {
                    val gaps =
                        (if (missing.isEmpty()) "" else "; missing ${missing.sorted()}") +
                            (if (unknown.isEmpty()) "" else "; unknown ${unknown.sorted()}")
                    Built.Invalid("${target.kind.name.lowercase()} takes fields ${spec.describe()}$gaps")
                }

                fields.values.any { it.length > MAX_FIELD } -> {
                    Built.Invalid("intent fields are limited to $MAX_FIELD characters")
                }

                else -> {
                    SystemIntents.build(target.kind, fields, zone)
                }
            }
        if (built is Built.Ready) built.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return built
    }
}

/** Intent builders for each [SystemIntentKind]; fields are already checked against the kind's keys. */
internal object SystemIntents {
    /**
     * Settings pages `phone_intent kind=settings` may open (addendum §13): no accessibility,
     * developer, permission or security pages.
     */
    val SETTINGS_PAGES: Map<String, String> =
        mapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
            "date" to Settings.ACTION_DATE_SETTINGS,
            "locale" to Settings.ACTION_LOCALE_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "app_details" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )

    private val PHONE_NUMBER = Regex("[0-9+*#() -]{1,32}")
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val TIME = Regex("([01]?[0-9]|2[0-3]):([0-5][0-9])")
    private const val MAX_TIMER_SECONDS = 24 * 60 * 60
    private const val LOCAL_TIME = "local time yyyy-MM-ddTHH:mm"

    /** The intent of [kind] from [fields]. */
    fun build(
        kind: SystemIntentKind,
        fields: Map<String, String>,
        zone: ZoneId,
    ): LaunchIntents.Built =
        when (kind) {
            SystemIntentKind.ALARM -> {
                alarm(fields)
            }

            SystemIntentKind.TIMER -> {
                timer(fields)
            }

            SystemIntentKind.CALENDAR -> {
                calendar(fields, zone)
            }

            SystemIntentKind.SHARE -> {
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, fields.getValue("text"))
                ready(Intent.createChooser(send, null))
            }

            SystemIntentKind.DIAL -> {
                dial(fields.getValue("number"))
            }

            SystemIntentKind.NAVIGATE -> {
                navigate(fields.getValue("place"))
            }

            SystemIntentKind.SETTINGS -> {
                settings(fields.getValue("page"), fields["package"])
            }
        }

    private fun ready(intent: Intent) = LaunchIntents.Built.Ready(intent, null)

    private fun invalid(reason: String) = LaunchIntents.Built.Invalid(reason)

    private fun alarm(fields: Map<String, String>): LaunchIntents.Built {
        val match = TIME.matchEntire(fields.getValue("time"))
        return if (match == null) {
            invalid("alarm time must be HH:mm, got '${fields["time"]}'")
        } else {
            val intent =
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, match.groupValues[1].toInt())
                    .putExtra(AlarmClock.EXTRA_MINUTES, match.groupValues[2].toInt())
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            fields["label"]?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            ready(intent)
        }
    }

    private fun timer(fields: Map<String, String>): LaunchIntents.Built {
        val seconds = fields.getValue("seconds").toIntOrNull()?.takeIf { it in 1..MAX_TIMER_SECONDS }
        return if (seconds == null) {
            invalid("timer seconds must be a whole number from 1 to $MAX_TIMER_SECONDS, got '${fields["seconds"]}'")
        } else {
            val intent =
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            fields["label"]?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            ready(intent)
        }
    }

    private fun calendar(
        fields: Map<String, String>,
        zone: ZoneId,
    ): LaunchIntents.Built {
        val start = epochMillis(fields.getValue("start"), zone)
        val endText = fields["end"]
        val end = endText?.let { epochMillis(it, zone) }
        return when {
            start == null -> {
                invalid("calendar start must be $LOCAL_TIME, got '${fields["start"]}'")
            }

            endText != null && end == null -> {
                invalid("calendar end must be $LOCAL_TIME, got '$endText'")
            }

            end != null && end < start -> {
                invalid("calendar end is before start")
            }

            else -> {
                val intent =
                    Intent(Intent.ACTION_INSERT)
                        .setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, fields.getValue("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                end?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                fields["location"]?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                ready(intent)
            }
        }
    }

    private fun dial(number: String): LaunchIntents.Built =
        if (PHONE_NUMBER.matches(number)) {
            ready(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        } else {
            invalid("dial number may contain only digits, spaces and + * # ( ) -")
        }

    private fun navigate(place: String): LaunchIntents.Built =
        if (place.isEmpty()) {
            invalid("navigate needs a place")
        } else {
            ready(Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(place)).toUri()))
        }

    private fun settings(
        page: String,
        pkg: String?,
    ): LaunchIntents.Built {
        val action = SETTINGS_PAGES[page]
        val details = page == "app_details"
        return when {
            action == null -> invalid("settings page must be one of ${SETTINGS_PAGES.keys.sorted()}, got '$page'")

            details && (
                pkg == null ||
                    !PACKAGE.matches(
                        pkg,
                    )
            ) -> invalid("settings page app_details needs a valid 'package'")

            details -> ready(Intent(action, Uri.fromParts("package", pkg, null)))

            pkg != null -> invalid("'package' is only used with page app_details")

            else -> ready(Intent(action))
        }
    }

    private fun epochMillis(
        text: String,
        zone: ZoneId,
    ): Long? =
        try {
            LocalDateTime
                .parse(text)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        } catch (expectedMalformed: DateTimeParseException) {
            // The model wrote another format; the caller asks for yyyy-MM-ddTHH:mm.
            null
        }
}
