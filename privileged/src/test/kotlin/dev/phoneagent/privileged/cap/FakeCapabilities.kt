package dev.phoneagent.privileged.cap

import android.content.Intent
import android.os.Bundle
import android.view.Surface

/** Records what [dev.phoneagent.privileged.helper.PrivilegedCore] delegates, and what to answer. */
internal class FakeCapabilities : Capabilities {
    val calls = mutableListOf<String>()
    var releases = 0
    var probeResult: Bundle = Bundle()
    var startResult = 0
    var topTaskResult = 7
    var moveResult = true
    var strokeResult = true
    var keyResult = true
    var muteResult = true
    var grantResult = 0
    var arrivalsResult: Bundle = Bundle()
    var displayIdResult = 42

    override val displays: DisplayCaps =
        object : DisplayCaps {
            override fun createDisplay(
                surface: Surface?,
                width: Int,
                height: Int,
                dpi: Int,
            ): Int {
                calls += "createDisplay(${surface == null}, $width, $height, $dpi)"
                return displayIdResult
            }

            override fun releaseDisplay(displayId: Int) {
                calls += "releaseDisplay($displayId)"
            }
        }

    override val tasks: TaskCaps =
        object : TaskCaps {
            override fun startActivity(
                displayId: Int,
                intent: Intent?,
                userId: Int,
                cold: Boolean,
            ): Int {
                calls += "startActivity($displayId, ${intent?.action}, $userId, $cold)"
                return startResult
            }

            override fun topTask(displayId: Int): Int {
                calls += "topTask($displayId)"
                return topTaskResult
            }

            override fun moveTask(
                taskId: Int,
                displayId: Int,
            ): Boolean {
                calls += "moveTask($taskId, $displayId)"
                return moveResult
            }

            override fun watchMainScreen(windowMs: Int): Int {
                calls += "watchMainScreen($windowMs)"
                return topTaskResult
            }

            override fun mainArrivals(sinceUptimeMs: Long): Bundle {
                calls += "mainArrivals($sinceUptimeMs)"
                return arrivalsResult
            }
        }

    override val input: InputCaps =
        object : InputCaps {
            override fun injectStroke(
                displayId: Int,
                xs: IntArray?,
                ys: IntArray?,
                times: LongArray?,
            ): Boolean {
                calls += "injectStroke($displayId, ${xs?.toList()}, ${ys?.toList()}, ${times?.toList()})"
                return strokeResult
            }

            override fun injectKey(
                displayId: Int,
                keyCode: Int,
            ): Boolean {
                calls += "injectKey($displayId, $keyCode)"
                return keyResult
            }
        }

    override val system: SystemCaps =
        object : SystemCaps {
            override fun setAudioMuted(
                pkg: String?,
                muted: Boolean,
            ): Boolean {
                calls += "setAudioMuted($pkg, $muted)"
                return muteResult
            }

            override fun grantSelf(permission: String?): Int {
                calls += "grantSelf($permission)"
                return grantResult
            }
        }

    override fun probe(): Bundle {
        calls += "probe()"
        return probeResult
    }

    override fun releaseAll(): String {
        releases++
        calls += "releaseAll()"
        return "nothing"
    }
}
