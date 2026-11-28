package dev.phoneagent.buildlogic

import dev.detekt.gradle.Detekt
import org.gradle.api.Project
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * `:privileged` `verifyDetektCanary` (part of `check`, and of the CI `static` job's unqualified
 * `verifyDetektCanary`): fails unless detekt, on `detektDebug`'s classpath, resolves the
 * AIDL-generated types (config/detekt/canary-aidl). Kept apart from the protocol plugin so that
 * plugin still loads where detekt is not on the classpath.
 */
internal fun Project.registerAidlDetektCanary() {
    val rootDir = isolated.rootProject.projectDirectory
    val canaryDir = rootDir.dir("config/detekt/canary-aidl")
    val canary =
        tasks.register<Detekt>("detektCanary") {
            description = "Runs detekt on config/detekt/canary-aidl with the classpath of detektDebug."
            setSource(files(canaryDir.dir("canary")))
            config.setFrom(rootDir.file("config/detekt/detekt.yml"))
            buildUponDefaultConfig.set(true)
            classpath.setFrom(tasks.named<Detekt>("detektDebug").map { it.classpath })
            jvmTarget.set(JVM_TARGET.toString())
            basePath.set(rootDir.asFile.absolutePath)
            ignoreFailures.set(true)
            reports {
                checkstyle.required.set(true)
                html.required.set(false)
                sarif.required.set(false)
                markdown.required.set(false)
            }
        }
    val verify =
        tasks.register<VerifyDetektCanaryTask>("verifyDetektCanary") {
            description = "Fails unless every rule in config/detekt/canary-aidl/expected-rules.txt fired."
            group = "verification"
            report.set(canary.flatMap { it.reports.checkstyle.outputLocation })
            expectedRules.set(canaryDir.file("expected-rules.txt"))
            marker.set(layout.buildDirectory.file("detekt-canary/verified"))
        }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
}
