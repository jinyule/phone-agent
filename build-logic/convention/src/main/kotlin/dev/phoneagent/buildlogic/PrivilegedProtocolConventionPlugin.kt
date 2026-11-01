package dev.phoneagent.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * `:privileged` only: `verifyPrivilegedProtocol` (part of `check`, and run by the CI
 * `privileged-build` job) fails when the AIDL changed without bumping `PrivilegedProtocol.VERSION`.
 */
class PrivilegedProtocolConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            val verify =
                tasks.register<VerifyPrivilegedProtocolTask>("verifyPrivilegedProtocol") {
                    description = "Fails when the privileged AIDL changed without bumping PrivilegedProtocol.VERSION."
                    group = "verification"
                    aidlDir.set(layout.projectDirectory.dir("src/main/aidl"))
                    protocolSource.set(
                        layout.projectDirectory.file("src/main/kotlin/dev/phoneagent/privileged/PrivilegedProtocol.kt"),
                    )
                    history.set(layout.projectDirectory.file("protocol-history.txt"))
                    marker.set(layout.buildDirectory.file("privileged-protocol/verified"))
                }
            tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
        }
}
