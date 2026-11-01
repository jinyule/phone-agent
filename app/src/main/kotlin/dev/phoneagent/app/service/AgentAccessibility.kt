package dev.phoneagent.app.service

import dev.phoneagent.app.adapters.screen.ScreenHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the rest of the app finds the connected accessibility service, as a [ScreenHost].
 *
 * The system creates and binds [AgentAccessibilityService]; nothing else can hand out its instance,
 * so the service registers its host here between `onServiceConnected` and `onUnbind`/`onDestroy`.
 * This process-wide holder does not leak the service: the reference exists only while the system
 * keeps the service bound (and therefore alive anyway), and it is cleared on unbind and destroy.
 * [detach] clears only if the host is still the registered one, so a late `onDestroy` of an old
 * instance cannot drop a newer connection. Callers read [host] per operation and must not keep the
 * instance beyond it.
 */
internal object AgentAccessibility {
    private val connected = MutableStateFlow<ScreenHost?>(null)

    /** The connected host, or `null` while accessibility is off or the service is rebinding. */
    val host: StateFlow<ScreenHost?> = connected.asStateFlow()

    /** Registers [host] as the current connection. */
    fun attach(host: ScreenHost) {
        connected.value = host
    }

    /** Clears the registration if it is still [host]. */
    fun detach(host: ScreenHost) {
        connected.compareAndSet(host, null)
    }
}
