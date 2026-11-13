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
        // Only phones: the native gyro buffer reader (src/main/cpp) is built for 64-bit ARM alone.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The gyro direct channel on phones that offer it only as a gralloc HardwareBuffer.
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    // The signing key lives outside the repo: the build hook passes it in ANDROID_KEYSTORE and friends
    // (on the PC from ~/.android-keys/gyrophone.env, in CI from the repo's secrets), so every build
    // is signed the same way and installs over the previous one, keeping saved profiles. A plain
    // Gradle build without them uses Android's own debug key, which can't update the installed app.
    signingConfigs {
        getByName("debug") {
            System.getenv("ANDROID_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // The build the phone runs (the build hook's assembleRelease): not debuggable, so Android
        // runs it at full speed and compiles it ahead of time with the profiles profileinstaller
        // brings (a debuggable app gets neither). No R8: shrinking needs keep rules for the
        // serialization and JNI classes, and isn't worth that risk.
        release {
            isMinifyEnabled = false
            // The same key as every build (see signingConfigs), so adb install -r updates the
            // installed app in place (debug and release builds of it alike) and keeps its data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The full checks run Android lint on the release build (lintRelease); assembleRelease
    // doesn't run it a second time.
    lint { checkReleaseBuilds = false }

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
    implementation(libs.androidx.graphics.path)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.zxing.android.embedded)
    // Installs the app's and Compose's ready-made startup profiles, so ART compiles that code ahead of time.
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
