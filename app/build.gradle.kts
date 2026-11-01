plugins {
    id("phoneagent.android.application")
}

android {
    namespace = "dev.phoneagent.app"
    defaultConfig {
        applicationId = "dev.phoneagent"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":privileged"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    constraints {
        implementation(libs.androidx.lifecycle.runtime) {
            because("lifecycle 2.9.x ships lint checks that AGP 9.5 lint rejects (ObsoleteLintCustomCheck)")
        }
    }

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
