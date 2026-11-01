package dev.phoneagent.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Pure Kotlin/JVM module (`:core`): explicit API, warnings as errors, JUnit Platform, quality gates. */
class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("java-library")
            pluginManager.apply(QualityConventionPlugin::class.java)

            extensions.configure<KotlinJvmProjectExtension> {
                explicitApi()
                compilerOptions {
                    jvmTarget.set(JvmTarget.fromTarget(JVM_TARGET.toString()))
                    freeCompilerArgs.add("-Xjdk-release=$JVM_TARGET")
                    allWarningsAsErrors.set(true)
                }
            }
            tasks.withType<JavaCompile>().configureEach {
                options.release.set(JVM_TARGET)
            }

            dependencies {
                add("testImplementation", platform(libs.library("junit-bom")))
                add("testImplementation", libs.library("junit-jupiter"))
                add("testRuntimeOnly", libs.library("junit-platform-launcher"))
            }
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
                testLogging {
                    exceptionFormat = TestExceptionFormat.FULL
                    events("failed")
                }
            }
        }
}
