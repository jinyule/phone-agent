package dev.phoneagent.core.ports

/**
 * Wall-clock source for timestamps written to the session log. Inject it; never read the system
 * clock directly in core, so replay can pin time. Timeouts and waits use coroutine time
 * (`delay`, `withTimeout`), not this clock.
 */
public fun interface Clock {
    /** Milliseconds since the Unix epoch. */
    public fun epochMillis(): Long

    /** Shared instances. */
    public companion object {
        /** The JVM wall clock. */
        public val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
