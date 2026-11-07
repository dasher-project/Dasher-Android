import org.gradle.api.tasks.Copy

// ── Version from the v* tag (single source of truth) ────────────────────────
// versionName = the most recent v* tag (via git describe); versionCode = the
// number of commits since the tag began, plus a base, so it is deterministic
// and strictly monotonic across builds without manual bumping — versionName
// sat at "0.1.0" for eight releases because nothing forced the bump.
// Tarball/no-git builds fall back to the constants below; keep them at the
// last released values.
fun gitVersionName(fallback: String): String {
    val describe = try {
        val p = ProcessBuilder("git", "describe", "--tags", "--match", "v*")
            .directory(rootDir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }
    return if (describe.startsWith("v")) describe.removePrefix("v") else fallback
}

// RFC 0022 item 4: the running engine's version, derived from the pinned
// submodule (the engine is bundled into the APK, so the pin IS what runs).
// Report the exact describe string (e.g. "v0.2.24-4-gc7899b65") — pin commits
// past a tag matter when triaging field reports.
fun engineVersion(): String {
    val describe = try {
        val p = ProcessBuilder("git", "describe", "--tags")
            .directory(File(rootDir, "third_party/DasherCore")).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }
    // CI checks out submodules shallow (no tags): describe then fails and its
    // merged stderr would become the value — multi-line garbage that broke the
    // generated BuildConfig string literal. Accept only a real describe
    // ("vX.Y[.Z][-N-gSHA][-dirty]"); anything else degrades to "unknown".
    return if (Regex("""^v\d+(\.\d+)*(-\d+-g[0-9a-f]+)?(-dirty)?$""").matches(describe)) describe
    else "unknown"
}

fun gitVersionCode(base: Int, fallback: Int): Int {
    // Count commits since the first commit reachable from HEAD; the base keeps
    // historical codes (0.1.8 shipped as 8) below any git-derived value.
    val count = try {
        val p = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootDir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().toIntOrNull().also { p.waitFor() }
    } catch (_: Exception) { null }
    return base + (count ?: (fallback - base))
}

// RFC 0022 item 3: CAPI compatibility gate. The frontend declares the
// minimum DasherCore C-API level it requires; the build FAILS if the pinned
// engine's dasher.h is older — "can I bump the engine?" becomes a check
// instead of changelog archaeology. Bump this only for engine versions whose
// behavioural changes the frontend has adapted to (per DasherCore release
// notes), not on every engine bump.
fun requireCapiVersion(required: Int) {
    val header = File(rootDir, "third_party/DasherCore/src/dasher.h")
    if (!header.exists()) throw GradleException("CAPI gate: ${header} missing — submodule not checked out?")
    val m = Regex("#define\\s+DASHER_CAPI_VERSION\\s+(\\d+)").find(header.readText())
        ?: throw GradleException("CAPI gate: DASHER_CAPI_VERSION not found in the pinned dasher.h")
    val pinned = m.groupValues[1].toInt()
    if (pinned < required) {
        throw GradleException(
            "CAPI gate: pinned DasherCore exposes CAPI version $pinned, this frontend requires >= $required. " +
            "Bump third_party/DasherCore (see .github/workflows/engine-bump.yml) or lower the requirement after adapting.")
    }
    logger.lifecycle("CAPI gate: pinned DasherCore CAPI version $pinned (required >= $required) — OK")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// RFC 0022 item 3 — fails configuration when the pin is too old.
requireCapiVersion(3)

android {
    namespace = "at.dasher.android"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "at.dasher.android"
        minSdk = 24
        targetSdk = 36
        versionCode = gitVersionCode(base = 1000, fallback = 8)
        versionName = gitVersionName(fallback = "0.1.8")
        buildConfigField("String", "ENGINE_VERSION", "\"${engineVersion()}\"")

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
            // Upload the ProGuard mapping to PostHog (#44): crash stacks
            // are unreadable without it — every frame arrives as
            // "resolve_failure: No proguard map uploaded".
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
        buildConfig = true
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
            // libdasher is only built for arm64-v8a + x86_64 (see externalNativeBuild
            // abiFilters above). Some AARs (androidx.graphics.path) ship native libs for
            // armeabi-v7a/x86 too; if those leak through, a 32-bit emulator/device selects
            // that ABI (because e.g. libandroidx.graphics.path.so is present) and then
            // crashes with UnsatisfiedLinkError loading libdasher_jni. Strip the partial ABIs
            // so the APK only advertises ABIs we fully support.
            excludes += setOf("**/armeabi-v7a/**", "**/x86/**")
        }
    }

    lint {
        // Don't gate release builds on lint (lintVitalAnalyzeRelease crashes on some
        // library combinations). The per-PR `build` workflow is the compile gate.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Mirror DasherCore's locale files (strings_*.json + the canonical locales.json
// from RFC 0003 / DasherCore #53) into a Strings/ asset dir, auto-synced from the
// submodule (no duplicated files to maintain). locales.json drives the app's
// language picker so it can never drift from the shipped translations.
val syncDasherStrings by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("../third_party/DasherCore/Strings"))
    include("strings_*.json", "locales.json")
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
