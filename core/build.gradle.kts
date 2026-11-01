plugins {
    id("phoneagent.kotlin.jvm")
    id("phoneagent.coverage")
    id("phoneagent.session-format")
    // Only :core persists JSON (session log, profiles), so the plugin is applied here, not in the convention.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Part of core's public API (JsonObject in contracts, StateFlow in ports).
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    // Protocol adapters (core.model.*); versions are fixed here so adapter work packages share them.
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.mockwebserver3.junit5)
    testImplementation(libs.konsist)
}
