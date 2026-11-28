// Known-bad code. Not part of any module: `:privileged:detektCanary` analyses it with the classpath
// of `:privileged:detektDebug`, and `:privileged:verifyDetektCanary` asserts that every rule in
// ../expected-rules.txt reports at least one finding. Every finding needs the AIDL-generated
// IPrivilegedCore to resolve, so the check fails as soon as that classpath loses javac's output.
package canary

import dev.phoneagent.privileged.IPrivilegedCore

internal fun castThatCannotSucceed(core: IPrivilegedCore.Stub): String = core as String

internal fun safeCallOnBinderResult(core: IPrivilegedCore.Stub): Boolean? = core.pingBinder()?.not()

internal fun notNullOnAidlResult(core: IPrivilegedCore): Int = core.protocolVersion()!!
