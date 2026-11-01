package dev.phoneagent.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service behind every `ScreenHost` (phase1 §2.2): reads window trees on all
 * displays, dispatches display-targeted gestures, takes screenshots and republishes events per
 * display. Configuration is `res/xml/agent_accessibility_service.xml`.
 *
 * Lifecycle: on connect it creates a [ServiceScreenHost] and registers it with
 * [AgentAccessibility]; on unbind or destroy it unregisters and closes it (see there why the
 * registration is not a leak). Events before connect or after unbind are dropped.
 */
internal class AgentAccessibilityService : AccessibilityService() {
    private var screenHost: ServiceScreenHost? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Many apps mark result text as not important for accessibility; the XML asks for those
        // views too, but a stale cached service config may not, so request it again at runtime.
        // Adapted from Mika (Apache-2.0, see NOTICE). Modified by PhoneAgent contributors.
        serviceInfo?.let { info ->
            if (info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS == 0) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        }
        release()
        val host = ServiceScreenHost(this)
        screenHost = host
        AgentAccessibility.attach(host)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        screenHost?.onEvent(event)
    }

    private fun release() {
        screenHost?.let {
            AgentAccessibility.detach(it)
            it.close()
        }
        screenHost = null
    }
}
