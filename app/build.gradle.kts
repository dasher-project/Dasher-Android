import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "at.dasher.android"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "at.dasher.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                // arm64-v8a (devices) + x86_64 (emulator). Add "armeabi-v7a" if needed.
                abiFilters += setOf("arm64-v8a", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with the debug key so tag/release builds produce a directly-installable
            // (sideloadable) APK with no secrets in CI - mirrors Dasher-Windows' self-signed
            // sideload artifact. Swap in a real release keystore (from CI secrets) for Play Store.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // Bundle DasherCore's alphabets / colours / training / settings as app assets.
            // DataInstaller.kt unpacks these to filesDir on first run so dasher_create()
            // receives real filesystem paths.
            assets.srcDir("../third_party/DasherCore/Data")
            // Generated locale strings (see syncDasherStrings below) - bundled under
            // a Strings/ asset dir so dasher_set_locale finds data_dir/Strings/strings_*.json.
            assets.srcDir(layout.buildDirectory.dir("generated/dasher-assets"))
        }
    }

    packaging {
        jniLibs {
            // DasherCore's CAPI build already produces distinct libs per ABI.
            useLegacyPackaging = false
        }
    }

    lint {
        // Don't gate release builds on lint (lintVitalAnalyzeRelease crashes on some
        // library combinations). The per-PR `build` workflow is the compile gate.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Mirror DasherCore's locale files (strings_*.json) into a Strings/ asset dir,
// auto-synced from the submodule (no duplicated files to maintain).
val syncDasherStrings by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("../third_party/DasherCore/Strings"))
    include("strings_*.json")
    into(layout.buildDirectory.dir("generated/dasher-assets/Strings"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(syncDasherStrings) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.icons.lucide)
    implementation(libs.posthog.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
