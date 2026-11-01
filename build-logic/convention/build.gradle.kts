plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("root") {
            id = "phoneagent.root"
            implementationClass = "dev.phoneagent.buildlogic.RootConventionPlugin"
        }
        register("kotlinJvm") {
            id = "phoneagent.kotlin.jvm"
            implementationClass = "dev.phoneagent.buildlogic.KotlinJvmConventionPlugin"
        }
        register("androidApplication") {
            id = "phoneagent.android.application"
            implementationClass = "dev.phoneagent.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "phoneagent.android.library"
            implementationClass = "dev.phoneagent.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("quality") {
            id = "phoneagent.quality"
            implementationClass = "dev.phoneagent.buildlogic.QualityConventionPlugin"
        }
        register("coverage") {
            id = "phoneagent.coverage"
            implementationClass = "dev.phoneagent.buildlogic.CoverageConventionPlugin"
        }
    }
}
