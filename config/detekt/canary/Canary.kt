// Known-bad code. Not part of any module: `detektCanary` analyses it with the project config and
// `verifyDetektCanary` asserts that every rule in expected-rules.txt reports at least one finding.
package canary

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

public class UndocumentedCanary {
    public val undocumentedProperty: Int = 1

    public fun undocumentedFunction(): Int = undocumentedProperty
}

internal fun unsafeCall(text: String?): Int = text!!.length

internal fun globalLaunch() {
    GlobalScope.launch { delay(1) }
}

internal suspend fun swallowCancellation(): Result<Unit> = runCatching { delay(1) }

internal fun blocking(): Unit = runBlocking { delay(1) }

internal fun sleeping(): Unit = Thread.sleep(1)

internal fun emptyCatch(path: String) {
    try {
        throw IOException(path)
    } catch (e: IOException) {
    }
}

internal fun genericCatch(path: String): Int = try {
    path.length
} catch (e: Exception) {
    0
}

internal fun swallowedCause(path: String): Int = try {
    throw IOException(path)
} catch (e: IOException) {
    throw IllegalStateException("read failed")
}

internal enum class Light { RED, GREEN }

internal fun elseBranch(light: Light): Int = when (light) {
    Light.RED -> 1
    else -> 2
}
