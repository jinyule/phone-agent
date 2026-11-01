package dev.phoneagent.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Android application (`:app`): Compose, version from gradle.properties, strict lint. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply(QualityConventionPlugin::class.java)

            val version = AppVersion.parse(providers.gradleProperty("phoneagent.versionName").get())
            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig {
                    targetSdk = Sdk.TARGET
                    versionCode = version.versionCode
                    versionName = version.versionName
                }
                buildFeatures {
                    compose = true
                }
                testOptions {
                    unitTests.isIncludeAndroidResources = true
                }
            }
            configureKotlinAndroid(explicitApi = false)
            tasks.withType<Test>().configureEach {
                // Robolectric (SDK 36 sandbox) reaches into FileDescriptor internals on JDK 21+.
                jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--enable-native-access=ALL-UNNAMED",
                )
            }
        }
}

/** Android library (`:privileged`): explicit API like `:core`, strict lint. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply(QualityConventionPlugin::class.java)

            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                lint.targetSdk = Sdk.TARGET
            }
            configureKotlinAndroid(explicitApi = true)
        }
}

private fun configureAndroidCommon(android: CommonExtension) {
    android.compileSdk = Sdk.COMPILE
    android.defaultConfig.minSdk = Sdk.MIN
    android.compileOptions.sourceCompatibility = JavaVersion.toVersion(JVM_TARGET)
    android.compileOptions.targetCompatibility = JavaVersion.toVersion(JVM_TARGET)
    android.lint.apply {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        error += "HardcodedText"
        // Dependency freshness is Dependabot's job; targetSdk lags compileSdk on purpose (Sdk.TARGET).
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

private fun Project.configureKotlinAndroid(explicitApi: Boolean) {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(JVM_TARGET.toString()))
            allWarningsAsErrors.set(true)
            // Production sources only; test sources keep implicit visibility.
            if (explicitApi && !name.contains("Test")) freeCompilerArgs.add("-Xexplicit-api=strict")
        }
    }
}
