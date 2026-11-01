package dev.phoneagent.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** Android SDK levels shared by every Android module (phase1-architecture D2). */
internal object Sdk {
    const val COMPILE = 37

    /** Kept one level behind COMPILE until the API 37 behaviour changes are evaluated (ADR 2026-11-01). */
    const val TARGET = 36
    const val MIN = 30
}

/** Bytecode level for every module; Android consumes core's classes, so core cannot go higher. */
internal const val JVM_TARGET = 17

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("libs.versions.toml has no library '$alias'") }

internal fun VersionCatalog.versionOf(alias: String): String =
    findVersion(
        alias,
    ).orElseThrow { IllegalStateException("libs.versions.toml has no version '$alias'") }.requiredVersion

/**
 * Makes dependency resolution reproducible without lockfiles: every version comes from the
 * catalog, so dynamic (`1.+`) and changing (`-SNAPSHOT`) versions are rejected outright.
 */
internal fun Project.rejectNonReproducibleVersions() {
    configurations.configureEach {
        resolutionStrategy {
            failOnDynamicVersions()
            failOnChangingVersions()
        }
    }
}
