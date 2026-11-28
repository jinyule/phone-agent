package dev.phoneagent.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import dev.detekt.gradle.Detekt
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Android application (`:app`): Compose, version from gradle.properties, strict lint, and unit
 * tests for every build type: AGP only creates them for the tested one (debug) by default. The
 * release ones run only the release checks ([runOnlyReleaseChecksOnRelease]).
 */
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
            extensions.configure<ApplicationAndroidComponentsExtension> {
                beforeVariants { variant ->
                    variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
                }
            }
            configureKotlinAndroid(explicitApi = false)
            configureRobolectricJvm()
            runOnlyReleaseChecksOnRelease()
            resolveModuleJavaInDetekt()
        }
}

/**
 * Contract: the release variant's unit tests only check how release differs from debug (that no
 * debug-only entry point ships); every other test runs on debug only, so the Robolectric suite
 * does not run twice. `testReleaseUnitTest` runs the `<namespace>.release` package
 * (`src/testRelease`) and nothing else, and fails when that package holds no test.
 */
private fun Project.runOnlyReleaseChecksOnRelease() {
    val android = extensions.getByType<ApplicationExtension>()
    tasks.withType<Test>().configureEach {
        if (name == RELEASE_UNIT_TESTS) {
            // Read when the task is configured: the module's build script has set it by then.
            val namespace = checkNotNull(android.namespace) { "the app module sets android.namespace" }
            filter {
                includeTestsMatching("$namespace.release.*")
                isFailOnNoMatchingTests = true
            }
        }
    }
}

private const val RELEASE_UNIT_TESTS = "testReleaseUnitTest"

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
            configureRobolectricJvm()
            resolveModuleJavaInDetekt()
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

private fun Project.configureRobolectricJvm() {
    tasks.withType<Test>().configureEach {
        // Robolectric (SDK 36 sandbox) reaches into FileDescriptor internals on JDK 21+.
        jvmArgs(
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
        )
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

/**
 * detekt's Android tasks take their classpath from the variant's Kotlin compile task, whose
 * `libraries` leave out the Java the module compiles itself (AIDL stubs): Kotlin reads that Java as
 * sources. Without javac's output, code touching those types is analysed unresolved and the
 * type-resolution rules skip it silently. So each `detekt<Variant>` gets the same libraries plus
 * that output; `:privileged:verifyDetektCanary` fails if the AIDL types stop resolving.
 */
private fun Project.resolveModuleJavaInDetekt() {
    tasks.withType<Detekt>().configureEach {
        val variant = name.removePrefix("detekt")
        val kotlinc = "compile${variant}Kotlin"
        val javac = "compile${variant}JavaWithJavac"
        if (kotlinc in tasks.names && javac in tasks.names) {
            classpath.setFrom(
                tasks.named<KotlinCompile>(kotlinc).map { it.libraries },
                tasks.named<JavaCompile>(javac).flatMap { it.destinationDirectory },
            )
        }
    }
}
