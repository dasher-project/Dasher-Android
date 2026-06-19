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

**STATUS: COMPLETE** — `./gradlew :app:assembleDebug` builds a 53 MB APK
(`libdasher.so` + `libdasher_jni.so` for arm64-v8a + x86_64, bundled alphabet/
colour assets). The two DasherCore Android portability fixes landed on `main`
via [PR #21](https://github.com/dasher-project/DasherCore/pull/21) (merged):
`I18n.h` (Android joins the no-op `_()` branch) and `UserLocation.cpp`
(dropped unused `<sys/timeb.h>`). The submodule now pins `main` directly.

| Item | Status |
|---|---|
| Gradle/KTS project, NDK 27, SDK 35, minSdk 24 | ✅ done |
| DasherCore git submodule (`dasher-project/DasherCore`) | ✅ done (feat branch, PR #21) |
| CMake: builds DasherCore with `BUILD_CAPI ON` → `libdasher.so` | ✅ done |
| `jni_bridge.cpp` — thin JNI shim over `dasher.h` | ✅ done |
| `NativeBridge.kt` — Kotlin `external` decls (1:1 with `dasher_*`) | ✅ done |
| `DasherEngine.kt` — Choreographer loop, touch → `dasher_mouse_*` | ✅ done |
| `DasherCanvasView.kt` — command-buffer renderer (ported) | ✅ done |
| `MainActivity.kt` — Compose shell, canvas + output text | ✅ done |
| Asset copier: copies `Data/` to filesDir on first run | ✅ done |
| Local git repo, initial commit | ✅ done |

---

## Phase 1 — Core parity baseline *(in progress)*

Status after the initial push:

| Feature (matrix id) | Android | How |
|---|---|---|
| `canvas-rendering` | ✅ shipped | C-API `dasher_frame` + ported canvas (bold+subpixel glyphs) |
| `continuous-mode` | ✅ shipped | Default filter, touch pointer |
| `touch-input` | ✅ shipped | `dasher_mouse_move/down/up` |
| `tilt-input` | ✅ shipped | `TiltInputProvider` (game-rotation-vector) → `onTiltNormalized` |
| `ppm-language-model` | ✅ shipped | Default LM |
| `alternative-language-models` | ✅ shipped | LM id set via JNI |
| `adaptive-learning` | ✅ shipped | `BP_LM_ADAPTIVE` toggle (settings) |
| `alphabets-and-languages` | ✅ shipped | alphabet picker over `dasher_get_alphabet_*` |
| `cjk-pinyin-conversion` | ✅ shipped | engine-side |
| `colour-palettes` | ✅ shipped | palette picker + `dasher_set_palette` |
| `screen-orientation` | ✅ shipped | `BP_ORIENT_L_R` toggle (settings) |
| `dark-mode` | ✅ shipped | palette + Material3 dynamic/night theme |
| `settings-persistence` | ✅ shipped | `dasher_save_settings` on every change |
| `speed-control` | ✅ shipped | status-bar slider + `BP_AUTO_SPEEDCONTROL` |
| `copy-to-clipboard` | ✅ shipped | ClipboardManager "Copy all" **and** `dasher_set_clipboard_callback` (copy control nodes, copy-on-stop) |
| `control-mode` | ✅ shipped | `BP_CONTROL_MODE` toggle (settings) |
| `spoken-output-tts` | ✅ shipped | `dasher_set_speak_callback` → Android `TextToSpeech` |
| `speak-on-stop` | ✅ shipped | `BP_SPEAK_ALL_ON_STOP` fires the speak callback |
| `custom-fonts` | partial | UI uses bundled Inter; canvas `SP_DASHER_FONT` wiring = Phase 3 |
| `pointer-eye-gaze-input` | beta | touch-as-pointer now; real eye gaze = Phase 4 (socket input) |
| `direct-text-injection` | ✅ shipped | **DasherImeService** → host `InputConnection` (system keyboard). Android parity advantage over iOS (no Accessibility API needed) |
| `ios-keyboard-extension` → Android IME | ✅ shipped | enable "Dasher Keyboard" in system settings; low-memory mode |
| `app-group-sharing` | ✅ shipped | IME + app share `dasher_settings.xml` + data (same `filesDir`) |
| `save-to-file` | ✅ shipped | toolbar Save → Storage Access Framework |

Phase 2 deferred: `undo-redo` (no append/undo API in the C surface — needs a
DasherCore addition); `haptic-feedback` is gamepad rumble = Phase 4.

Engine→frontend callbacks (`dasher_set_clipboard/speak/message/output_callback`)
are wired via `JNI_OnLoad` + C wrappers → `NativeBridge.onX(...)`. See
`DasherCore/docs/CUSTOM_ACTIONS.md`. There is **no paste blocker**: paste is a
frontend clipboard-read like other platforms (not a DasherCore concern).

**Still open in Phase 1:** wire the canvas glyph font to `SP_DASHER_FONT`;
on-device smoke test.

**feature-status.json:** adding an `android` column to
`website/src/data/feature-status.json` is a deliberate public change to the
*website* repo — deferred until reviewed.

---

## Settings, theming & fonts — architecture (from the Apple/Windows review)

The current "Quick settings" dialog (a handful of hand-coded toggles) is a
**placeholder**. Both reference frontends generate settings from the engine
schema, not by hand — Android must do the same to stay in sync with DasherCore.
This is the core Phase-3 deliverable.

### Manifest-driven settings (mirror DasherApple `DasherSettingsView` / Dasher-Windows `SettingsPanel`)
- Iterate `dasher_get_parameter_count` / `dasher_get_parameter_info`; each entry
  carries `key, name, desc, group, subgroup, type, uiType, min, max, step, advanced`.
- Bucket by `group` → **tabs**: `Input, Language, Customization, Output, Game Mode`
  (+ synthetic `Speech`, `Privacy`), with the same tab order Apple/Windows use.
- Render each row by `uiType`: **Switch / Slider / Step / Enum(StringDropdown) /
  TextField**, plus special-cased **font** and **colour-palette** pickers.
- Resolve **every** parameter key at runtime via `nativeFindParameterKey("BP_*")`
  — no hardcoded integer keys (per Apple `f057748`).
- **Input tab** filtered by the active input filter (`SP_INPUT_FILTER` → subgroup);
  **Language tab** filtered by the active language model.
- Done → `dasher_save_settings`.

### Hand-built specials layered above the manifest (these are NOT auto-generated)
- **Colour palette** swatch strip (`dasher_get_palette_*` / preview colours) in Customization.
- **Canvas (Dasher) font** picker — `SP_DASHER_FONT` with a curated Android-safe
  preset list (`System, Sans Serif, Serif, Monospace, Roboto, …`; `"System"`→empty)
  + `LP_DASHER_FONTSIZE` slider 8–72. Read each frame in `DasherCanvasView` and
  map the name → `Typeface` (empty → `Typeface.DEFAULT_BOLD`).
- **Writing-area / output font** — local setting (DataStore) `FontFamily` + size,
  mirroring Dasher-Windows `OutputTextSettings` (Apple lacks this — Android can match Windows).
- **App locale** picker (9 locales) → `dasher_set_locale`, then **rebuild the
  parameter list** so labels re-fetch translated. Requires bundling
  `DasherCore/Strings/strings_*.json` (33 files) as assets.
- **Speech** tab (engine/voice/rate/pitch over the existing `dasher_set_speak_callback` → Android TTS).
- **Privacy** tab (analytics opt-in + reset ID).

### Dark mode — OS-driven, no manual toggle (matches Apple + Windows)
- Already correct in principle: `DasherAndroidTheme` follows `isSystemInDarkTheme()`
  with the design-guide light/dark token tables (`Color.kt`), `dynamicColor=false`.
- Add the few missing tokens to match Windows' table (TextSecondary/Muted,
  ControlBg/Hover) and ensure all chrome uses them (no hardcoded colours).
- The **canvas** colours stay separate — they come from DasherCore **palettes**
  (`dasher_set_palette`), never the UI theme.

### Control mode — toolbar quick-toggle (matches Apple + Windows)
- Single `BP_CONTROL_MODE` bool exposed as a **toolbar button** (icon
  `mouse-pointer-click`), not buried in the grid. Two-way sync via the
  parameter-change callback (`dasher_set_parameter_callback`) so flipping it in
  settings updates the toolbar, like Windows.
- Bundle `DasherCore/Data/control/` (`control.xml`) — already shipped via assets.

### Analytics (RFC 0001) — shared schema
- `posthog-android`, EU host, opt-in, same shared project token as Apple/Windows.
- Auto-inject `platform="android"`, `app_variant="dasher-android"`, `app_version`,
  `os_version` on every event. **Reuse the exact event names/props** from the
  shared `analytics-events.json` (`app_launched`, `settings_viewed{tab_name}`,
  `alphabet_selected`, `input_method_changed{method}`, …) for cross-frontend
  comparability.

### Lucide icons — shared name map
- Already use `com.composables:icons-lucide-cmp`. Adopt the same `DasherIcon`
  name constants Apple/Windows use (RFC 0002) so the three frontends stay aligned.

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

## Phase 3 — Customization, settings & training parity

> Driven by the **Settings, theming & fonts architecture** section above.
> Current state: a placeholder "Quick settings" dialog (6 toggles) — the real,
> manifest-driven UI is the main Phase-3 build.

| Feature (matrix id) | Android status | Plan |
|---|---|---|
| `dynamic-settings-discovery` | **next** | iterate `dasher_get_parameter_info`; render by `uiType`; runtime `findParameterKey` |
| `grouped-settings-ui` | **next** | tabs Input/Language/Customization/Output/Game Mode (+Speech/Privacy); Input filtered by active filter, Language by active LM |
| `custom-fonts` | **next** | canvas font `SP_DASHER_FONT` (curated list) + `LP_DASHER_FONTSIZE`; output-area font as a local setting (match Windows) |
| `localization` | **next** | bundle `DasherCore/Strings/strings_*.json`; 9-locale picker → `dasher_set_locale` → rebuild labels (RFC 0003) |
| `dark-mode` | ✅ shipped | already OS-driven via design-guide tokens; add remaining tokens |
| `control-mode` | partial → **next** | promote to toolbar toggle + parameter-change-callback two-way sync (matches Apple/Windows) |
| `game-mode-training` | **next** | toolbar toggle, target bar (correct/wrong/remaining), `dasher_game_*` |
| `custom-training-text` | **next** | `SP_GAME_TEXT_FILE` + SAF picker |
| `button-key-remapping` | planned | Android `KeyEvent` capture → `dasher_key_event` (switch profiles) |
| `live-settings-preview` | planned | mini-canvas in settings (all platforms still planned) |
| `guided-onboarding` | planned | first-run flow (RFC 0004); analytics opt-in |
| `analytics` | **next** | `posthog-android`, shared `analytics-events.json` schema, `platform=android` (RFC 0001) |

**Exit criteria:** settings UI generated from the engine schema (no hand-coded
per-parameter rows); canvas + output fonts configurable; locale picker works;
control mode + game mode in the toolbar; analytics opt-in.

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
