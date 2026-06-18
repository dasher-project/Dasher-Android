# Dasher-Android — Roadmap & Feature-Parity Plan

Official Android frontend for Dasher v6, built on the **DasherCore C API**
(`DasherCore/Src/dasher.h`) via JNI. This document is the row-by-row parity
plan against the cross-platform [feature matrix](../website/src/data/feature-status.json)
(rendered at <https://dasher.at/status/>).

> **Note:** Android is currently **not a column** in `feature-status.json`.
> Phase 1's exit criteria includes adding `android` to the `platforms` array
> and populating every row. This plan is the source material for that update.

---

## Architectural decision

| | Choice |
|---|---|
| **Engine integration** | DasherCore **C API** (`dasher.h`) via a thin JNI shim — same approach as Dasher-Windows (`NativeBridge.cs`). |
| **Why not the old bridge** | `Dasher-Mobile/` (janmurin2's contribution) hand-rolls `AndroidDasherInterface` subclassing `CDashIntfScreenMsgs`. That predates the C API, diverges from every other frontend, and exposes only a fraction of the engine. The C API is the maintained FFI surface. |
| **What we reuse from Dasher-Mobile** | The command-buffer canvas renderer (`DasherCanvasView`), the Choreographer frame loop, tilt-sensor plumbing, the IME-service lifecycle, and the Compose settings shell — repointed onto the C API. |
| **Package / applicationId** | `org.dasherproject.android` (one-line change in `app/build.gradle.kts` if a different ID is preferred). |
| **Reference systems** | Dasher-Windows (same Pattern-B/C-API integration), Dasher-Apple (most complete feature set). |

### Integration architecture

```
┌───────────────────────────────┐     ┌──────────────────────────┐
│  Kotlin (Jetpack Compose)     │     │  Native (NDK / C++)      │
│                               │     │                          │
│  NativeBridge.kt              │     │  libdasher.so            │
│    external fun dasher_*()    │────►│   (DasherCore CAPI build)│
│    1:1 with dasher.h          │ JNI │                          │
│                               │     │  libdasher_jni.so        │
│  DasherEngine                 │◄────│   jni_bridge.cpp         │
│    Choreographer frame loop   │     │   (thin: marshals frame  │
│    touch/tilt → dasher_mouse  │     │    buffers + callbacks)  │
│                               │     │   asset_copier.cpp       │
│  DasherCanvasView             │     │   (assets → filesDir on  │
│    decodes [op,a,b,c,d,argb]  │     │    first run, so         │
│    → Android Canvas           │     │    dasher_create gets a  │
│                               │     │    real filesystem path) │
└───────────────────────────────┘     └──────────────────────────┘
```

The C API's `dasher_frame()` returns the **same** `[op,a,b,c,d,argb]` command
buffer protocol that `DasherCanvasView` already decodes — so the renderer
ports across with no logic changes.

---

## Phase 0 — Foundation (this scaffold)

**Goal:** a project that opens in Android Studio, compiles, launches, loads
DasherCore, and renders the zooming canvas with touch input.

| Item | Status |
|---|---|
| Gradle/KTS project, NDK 27, SDK 35, minSdk 24 | scaffold |
| DasherCore git submodule (`dasher-project/DasherCore`) | scaffold |
| CMake: builds DasherCore with `BUILD_CAPI ON` → `libdasher.so` | scaffold |
| `jni_bridge.cpp` — thin JNI shim over `dasher.h` | scaffold |
| `NativeBridge.kt` — Kotlin `external` decls (1:1 with `dasher_*`) | scaffold |
| `DasherEngine.kt` — Choreographer loop, touch → `dasher_mouse_*` | scaffold |
| `DasherCanvasView.kt` — command-buffer renderer (ported) | scaffold |
| `MainActivity.kt` — Compose shell, canvas + output text | scaffold |
| Asset copier: copies `Data/` to filesDir on first run | scaffold |
| Local git repo, initial commit | scaffold |

**Build verification:** blocked on this machine — no JDK / Android SDK / NDK /
Gradle installed (only CMake 4.3.1). Open in Android Studio to let it install
the toolchain + generate the Gradle wrapper jar, then `./gradlew :app:assembleDebug`.

---

## Phase 1 — Core parity baseline  *(~20 matrix rows → shipped)*

Minimum lovable Dasher on Android. Roughly what Dasher-Mobile ships today,
but on the C API and with the full parameter surface exposed.

| Feature (matrix id) | Android target | Notes |
|---|---|---|
| `canvas-rendering` | **shipped** | C-API `dasher_frame` + ported canvas |
| `continuous-mode` | **shipped** | Default filter behaviour |
| `touch-input` | **shipped** | `dasher_mouse_move/down/up` from `MotionEvent` |
| `tilt-input` | **shipped** | Port `TiltInputProvider` (game-rotation-vector sensor) |
| `ppm-language-model` | **shipped** | Default LM (id 0) |
| `alternative-language-models` | **shipped** | `dasher_get_language_model_*`; KenLM via bundled `.binary` |
| `adaptive-learning` | **shipped** | `BP_LM_ADAPTIVE` (key 15) |
| `alphabets-and-languages` | **shipped** | `dasher_get_alphabet_*` (622 alphabets) |
| `cjk-pinyin-conversion` | **shipped** | Engine-side, free |
| `colour-palettes` | **shipped** | `dasher_get/set_palette_*` |
| `custom-fonts` | **shipped** | `SP_DASHER_FONT`, `LP_DASHER_FONTSIZE` |
| `screen-orientation` | **shipped** | `BP_ORIENT_L_R` |
| `dark-mode` | **shipped** | Palette + Android night theme |
| `settings-persistence` | **shipped** | `dasher_save_settings` (XML in filesDir) |
| `speed-control` | **shipped** | `dasher_get/set_speed_percent` |
| `copy-to-clipboard` | **shipped** | `ClipboardManager` |
| `paste-from-clipboard` | **shipped** | Output buffer + training |
| `pointer-eye-gaze-input` | **beta** | Touch-as-pointer now; real eye gaze waits on socket input (Phase 4) |

**Exit criteria:** app is usable end-to-end; Android added to
`feature-status.json` with the above statuses; local commits on a
`phase-1-core-parity` branch.

---

## Phase 2 — Android-IME parity  *(system keyboard + output richness)*

The Android analog of Apple's iOS keyboard extension. This is where Android
can **lead** — the IME *is* system-wide direct text entry.

| Feature (matrix id) | Android target | Notes |
|---|---|---|
| `direct-text-injection` | **shipped** | IME `InputConnection` — parity advantage over iOS (no Accessibility API needed) |
| `ios-keyboard-extension` → Android IME | **shipped** | Port `DasherImeService`; the matrix row is iOS-specific but the capability is the IME |
| `app-group-sharing` → shared app/IME settings | **shipped** | SharedPreferences + `dasher_save_settings` to a shared file |
| `spoken-output-tts` | **shipped** | `android.speech.tts` wired to `dasher_set_speak_callback` (Android TTS is strong) |
| `speak-on-stop` | **shipped** | `BP_SPEAK_ALL_ON_STOP` + speak callback |
| `haptic-feedback` | **shipped** | `VibratorManager` on selection/rumble events |
| `control-mode` | **shipped** | `BP_CONTROL_MODE` + `control.xml` (Windows was first v6 frontend to wire it) |
| `undo-redo` | **beta** | Engine output buffer; UI buttons |
| `save-to-file` | **beta** | Scoped storage / SAF |
| `pdf-screenshot-export` | planned | Low priority — Android uses share-sheet instead |

**Exit criteria:** user can enable Dasher as a system keyboard and type into
any app; speech + haptics + control mode work.

---

## Phase 3 — Customization & training parity  *(settings from the manifest)*

| Feature (matrix id) | Android target | Notes |
|---|---|---|
| `dynamic-settings-discovery` | **shipped** | `dasher_get_parameter_info` → build Compose controls dynamically (mirror GTK/Windows) |
| `grouped-settings-ui` | **shipped** | 5 tabs from `settings_manifest.json` groups (RFC 0006) |
| `localization` | **shipped** | `dasher_set_locale` (33 locales) + Android `values-<locale>` (RFC 0003) |
| `button-key-remapping` | **beta** | Android `KeyEvent` capture → `dasher_key_event` |
| `live-settings-preview` | planned | Mini-canvas in settings (all platforms still planned) |
| `guided-onboarding` | planned | First-run flow (RFC 0004) |
| `game-mode-training` | **shipped** | `dasher_enter_game_mode` family — fully C-API supported |
| `custom-training-text` | **shipped** | `SP_GAME_TEXT_FILE` + SAF picker |

**Exit criteria:** settings UI is generated from the engine schema, not
hardcoded; game mode playable; localized.

---

## Phase 4 — Advanced input parity  *(assistive access methods)*

| Feature (matrix id) | Android target | Notes |
|---|---|---|
| `switch-scanning` | **shipped** | One-/Two-Button Dynamic filters + `dasher_key_event` (keys 1-4); switch-profile UI |
| `dwell-selection` | **shipped** | Dwell filter + Android-side dwell indicator |
| `click-to-zoom` | **beta** | Click filter |
| `direct-mode` | **beta** | Direct-boxes filter; text lands via IME |
| `joystick-gamepad` | **beta** | Android `MotionEvent` SOURCE_GAMEPAD / `InputDevice` |
| `socket-input` | **beta** | UDP coordinate protocol (eye-tracker conduit) — reuses v5 design |
| `pointer-eye-gaze-input` | **shipped** | Promote from beta once socket input lands |
| `analytics` | **shipped** | `posthog-android` (RFC 0001), opt-in |
| `lucide-icons` | **shipped** | Compose icon set (RFC 0002) |
| `hand-tracking` | not-supported | No OS hand-tracking pointer (MLKit possible but out of scope) |
| `v5-migration` | n/a | No v5 Android app in this lineage to migrate from |

**Exit criteria:** full assistive input menu; eye-gaze trackers work via
socket input; analytics opt-in shipped.

---

## Cross-cutting work (every phase)

- **`feature-status.json`** — update Android column as each phase lands; the
  website matrix is the public source of truth.
- **TalkBack / accessibility audit** — content descriptions on canvas regions,
  switch-access interop, minimum 48dp touch targets (per `dasher-design-guide`).
- **Tests** — Kotlin unit tests for the JNI surface; instrumented tests for
  the canvas renderer; golden-output tests via `dasher_get_probabilities` etc.
  (the C API exposes diagnostic hooks specifically for this).
- **Design tokens** — map `dasher-design-guide/DESIGN.md` to Compose theme
  (`Color.kt`/`Theme.kt`/`Type.kt`) — the scaffold ships a first cut.

---

## Local workflow (no GitHub push)

```
git init                          # in Dasher-Android/
git submodule add ../DasherCore   # or the https URL; pinned, not main
git checkout -b main
git add -A && git commit -m "Phase 0: scaffold Dasher-Android on DasherCore C API"
# phase branches:
git checkout -b phase-1-core-parity
```

Phases are developed on branches; merge to `main` locally only until the
repo is published to `dasher-project/Dasher-Android`.
