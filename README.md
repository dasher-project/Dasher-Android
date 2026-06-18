# Dasher-Android

Official Android frontend for [Dasher v6](https://dasher.at), built on the
[DasherCore](https://github.com/dasher-project/DasherCore) **C API** via JNI.

> **Status:** Phase 0 (foundation scaffold). See [`PLAN.md`](./PLAN.md) for the
> full feature-parity roadmap against the cross-platform
> [feature matrix](https://dasher.at/status/).

## Architecture

DasherCore is consumed through its public C ABI (`dasher.h`) — the same
integration pattern as Dasher-Windows. A thin JNI shim marshals the frame
command buffer and engine callbacks to Kotlin; the UI is Jetpack Compose.

```
Kotlin (Compose)  ──JNI──▶  libdasher.so (DasherCore CAPI)  +  libdasher_jni.so (shim)
```

## Build

**Prerequisites:** Android Studio (Ladyfish or newer), which supplies JDK 17,
Android SDK 35, and NDK 27.0.12077973.

1. Open this directory in Android Studio. It will:
   - install the required SDK/NDK/CMake,
   - generate the Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`),
   - initialize the `DasherCore` submodule.
2. From the CLI:
   ```
   ./gradlew :app:assembleDebug
   ./gradlew :app:installDebug
   ```

### Submodule

DasherCore is pinned (not `main`) as a git submodule:

```
git submodule add https://github.com/dasher-project/DasherCore.git third_party/DasherCore
```

## Repository layout

```
app/src/main/
├── cpp/                      Native layer
│   ├── CMakeLists.txt        Builds DasherCore CAPI + JNI shim
│   ├── jni_bridge.cpp        Thin JNI bindings over dasher.h
│   └── asset_copier.{h,cpp}  Copies bundled Data/ to filesDir on first run
├── java/org/dasherproject/android/
│   ├── NativeBridge.kt       Kotlin external decls (1:1 with dasher.h)
│   ├── DasherEngine.kt       Choreographer frame loop + input translation
│   ├── MainActivity.kt       Compose app shell
│   └── ui/
│       ├── DasherCanvasView.kt   Decodes [op,a,b,c,d,argb] → Android Canvas
│       └── theme/                Compose theme from dasher-design-guide tokens
└── assets/                   Symlink/srcDir to third_party/DasherCore/Data
```

## License

MIT. See [`LICENSE`](./LICENSE).
