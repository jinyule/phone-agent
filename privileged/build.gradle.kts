plugins {
    id("phoneagent.android.library")
    id("phoneagent.privileged.protocol")
}

android {
    namespace = "dev.phoneagent.privileged"
    buildFeatures {
        aidl = true
    }
    defaultConfig {
        // app_process looks HelperMain up by name; keep it if the app ever enables R8.
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
