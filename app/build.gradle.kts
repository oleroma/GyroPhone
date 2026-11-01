import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.oleroma.gyrophone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oleroma.gyrophone"
        // The reference phone ships Android 16 (API 36); API 35 keeps one step of headroom for testing.
        minSdk = 35
        targetSdk = 36
        // The build hook stamps each build: 0.1.<build number>-<commit>. Plain Gradle builds are 0.1.0-dev.
        val run = System.getenv("GP_BUILD")?.toIntOrNull()
        val sha = System.getenv("GP_COMMIT")?.take(7)
        versionCode = run ?: 1
        versionName = if (run != null) "0.1.$run" + (sha?.let { "-$it" } ?: "") else "0.1.0-dev"
    }

    signingConfigs {
        // Shared debug key committed to the repo so every build can update the installed app
        // in place (a fresh per-runner debug key forces an uninstall, wiping saved profiles).
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug signing so CI release builds install directly on the test phone.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.zxing.android.embedded)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
