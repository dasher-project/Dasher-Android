# Contributing to Dasher-Android

Thank you for improving Dasher on Android! This guide covers the specifics of
this repository. For project-wide conventions (code of conduct, security, RFCs,
decision-making), see the
[organisation CONTRIBUTING](https://github.com/dasher-project/.github/blob/main/CONTRIBUTING.md).

## Quick start

```bash
git clone --recurse-submodules https://github.com/dasher-project/Dasher-Android.git
cd Dasher-Android
```

Open the project in **Android Studio** (it installs the required SDK 35,
NDK 27.0.12077973, and CMake 3.22.1, and generates the Gradle wrapper). Then:

```bash
./gradlew :app:assembleDebug          # build the debug APK
./gradlew :app:installDebug           # install on a connected device/emulator
./gradlew :app:testDebugUnitTest      # JVM unit tests
```

> **Note on the emulator black screen:** a known issue with the Android Emulator's
> `skiagl` + NVIDIA host GPU shows a frozen black window. Test on a **physical
> device**, or set the AVD's graphics mode to **Software (SwiftShader)**.

## Architecture

Dasher-Android is a Jetpack Compose frontend over the **DasherCore C API**
(`dasher.h`) via a thin JNI shim — the same integration pattern as Dasher-Windows.

| Path | Purpose |
| :--- | :--- |
| `app/src/main/cpp/` | `CMakeLists.txt` builds DasherCore (CAPI) → `libdasher.so`, plus `libdasher_jni.so` (the JNI shim over `dasher.h`) |
| `app/src/main/java/at/dasher/android/` | Kotlin: `NativeBridge` (1:1 with `dasher.h`), `DasherEngine` (Choreographer loop), `MainActivity` (Compose UI), `DasherImeService` (system keyboard), `SettingsScreen` (manifest-driven), `AnalyticsService` (PostHog) |
| `app/src/main/java/at/dasher/android/ui/` | `DasherCanvasView` (decodes the `[op,a,b,c,d,argb]` command buffer → Android Canvas), `theme/` (design-guide tokens) |
| `third_party/DasherCore/` | **Submodule** — the C++ engine. Do **not** edit here; PR upstream at `dasher-project/DasherCore`. |

Settings are **generated from the engine schema** (`dasher_get_parameter_info`),
mirroring DasherApple / Dasher-Windows — avoid hand-coding per-parameter UI.

## Sign-off (DCO)

All commits must be signed off (`Signed-off-by:`) under the
[Developer Certificate of Origin](https://developercertificate.org/). A CI check
enforces this. Sign commits with `-s`:

```bash
git commit -s -m "your message"
```

## Code style

- Kotlin, Jetpack Compose, Material 3.
- 4-space indentation; no trailing whitespace.
- UI colours/spacing come from the design-guide tokens in `ui/theme/` and the
  `dasher-design-guide` repo — don't hardcode brand colours.
- Lucide icons (RFC 0002) via `com.composables:icons-lucide-cmp`, matching the
  icon names used by the Apple/Windows frontends.
- Native changes belong in `third_party/DasherCore` upstream, **not** in this
  repo's shim unless they're JNI marshalling.
