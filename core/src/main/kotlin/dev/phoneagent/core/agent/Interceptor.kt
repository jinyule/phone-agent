package dev.phoneagent.core.agent

/**
 * One link of an interception chain at a fixed point of the main loop (phase1 §3.8). The loop
 * can only be extended through these points: `preStep`, `request`, `requestError`,
 * `toolPreExecute`, `toolExecute`, `toolPostExecute`, `turnStopping`.
 *
 * An interceptor either calls [Chain.proceed] (possibly with a changed input, possibly changing
 * the output) or returns without calling it, which cuts the rest of the chain. A thrown exception
 * ends the current turn only, never the task runtime. Must propagate `CancellationException`.
 */
public fun interface Interceptor<I, O> {
    /** Handles [input]; call [chain] to continue. */
    public suspend fun intercept(
        input: I,
        chain: Chain<I, O>,
    ): O
}

/** The rest of an interception chain. */
public fun interface Chain<I, O> {
    /** Runs the remaining interceptors and the terminal step with [input]. */
    public suspend fun proceed(input: I): O
}
