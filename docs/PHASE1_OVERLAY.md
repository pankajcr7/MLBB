# Phase 1 — overlay and draft detection

Phase 0 built the brain and a manual board. Phase 1 adds a second, faster **input
method**: read the draft off the screen and render suggestions on top of the game. The
engine does not change.

---

## Component plan

```
DraftOverlayService : Service                    (foregroundServiceType="mediaProjection")
  ├── ScreenCapturer        MediaProjection → VirtualDisplay → ImageReader, ~1 fps
  ├── DraftScreenDetector   is the draft screen even open? (one UI anchor template)
  ├── SlotExtractor         crop the ban/pick ROIs from the frame
  ├── HeroMatcher           perceptual hash → nearest hero in a local hash table
  ├── DraftStateTracker     debounce, confirm, and diff into engine DraftState
  └── OverlayWindow         WindowManager + ComposeView, bubble + expandable panel
                                    ↓
                            DraftEngine (unchanged, from :engine)
```

## 1. Screen capture

```kotlin
val projection = manager.getMediaProjection(resultCode, data)
val reader = ImageReader.newInstance(w / 3, h / 3, PixelFormat.RGBA_8888, 2)
projection.createVirtualDisplay(
    "draft", w / 3, h / 3, densityDpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
)
```

Notes that will bite otherwise:

- **Android 14+** requires a `mediaProjection`-typed foreground service **and** fresh
  user consent per session. There is no silent auto-start — design for a "Start before
  your match" button and accept it.
- Capture at **1 fps and one-third resolution.** A draft screen changes every few
  seconds; 60 fps capture buys nothing and drains battery.
- Always `close()` the `Image`. Leaking two frames stalls the `ImageReader` permanently.
- Run detection off the main thread; only the resulting state hits the UI.

## 2. Is the draft screen open?

Don't run slot extraction on every frame of a match. Match **one fixed UI anchor**
(a corner of the draft chrome) and idle cheaply when it isn't found. This one check is
the difference between a service that costs ~1% battery and one that costs 15%.

## 3. Identify heroes — hash, don't OCR

**Do not OCR hero names.** They are localised, small, and often not rendered during the
pick phase.

Instead, for each ban/pick region of interest:

1. Crop the portrait.
2. Downscale to 9×8 greyscale.
3. Compute a **dHash** (64-bit: each bit is "is this pixel brighter than the next").
4. Find the nearest hero by **Hamming distance** against a bundled hash table.
5. Reject the match above a distance threshold and fall back to manual input rather than
   guessing.

Why this over OCR or template matching:

| | dHash | OCR | OpenCV template match |
|---|---|---|---|
| Speed | ~1 ms/slot | 30–100 ms | 10–50 ms |
| Works in any client language | yes | no | yes |
| APK cost | a few KB of hashes | ML Kit model | ~40 MB native libs |
| Ships Moonton artwork | **no** | no | yes |

That last row matters: **ship hashes, not images.** It is smaller and it keeps game
assets out of the repo.

Build the hash table with an offline tool (a `:tools` JVM module) that reads portraits
locally and emits `data/hashes.json` keyed by the same hero `id` the dataset already
uses. Store several hashes per hero to cover skins if portraits vary.

## 4. Device variance

Phones differ in resolution, aspect ratio and notch. Do not hardcode pixel rectangles.

- Express every ROI as **fractions of the captured frame** (`x`, `y`, `w`, `h` in 0..1).
- Anchor those fractions to the detected UI anchor, so a different aspect ratio shifts
  the whole grid rather than breaking it.
- Ship a **calibration screen** as the fallback: show the captured frame, let the user
  drag a grid over the ten pick slots once, store it. This is the escape hatch that keeps
  the feature working on devices you have never seen.

## 5. State tracking

Raw per-frame matches are noisy. Debounce before touching the engine:

- Require the **same hero in the same slot across 2 consecutive frames** before
  committing.
- Never un-commit a slot — draft picks are append-only. This alone removes most flicker.
- Diff into `DraftState` via the existing `withBan` / `withPick`, then re-run the engine.
  A full scoring pass over 75 heroes is sub-millisecond, so just recompute everything.

## 6. The overlay window

```kotlin
val params = WindowManager.LayoutParams(
    WRAP_CONTENT, WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,   // requires API 26+, hence minSdk 26
    FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
)
```

- **`FLAG_NOT_FOCUSABLE` is mandatory.** A focusable overlay steals input from MLBB and
  will get the app uninstalled.
- Two states: a small **draggable bubble** (touchable, tiny hit area) that expands into a
  **panel** (touchable only while open). Collapse it on the first frame where the draft
  screen is no longer detected.
- Compose works in an overlay via `ComposeView`, but the window needs a
  `ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner` and
  `ViewTreeSavedStateRegistryOwner` — attach a small `LifecycleOwner` implementation held
  by the service, or the first composition crashes.
- Reuse the Phase 0 composables (`SuggestionCard`, `CompPanel`) unchanged. This is
  exactly why the dark theme is fixed rather than following system light mode.
- Add **text-to-speech for the top pick.** Under a draft timer, nobody reads a panel.

## 7. Manifest additions

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".overlay.DraftOverlayService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false" />
```

`SYSTEM_ALERT_WINDOW` is not grantable by dialog — send the user to
`Settings.ACTION_MANAGE_OVERLAY_PERMISSION` and check
`Settings.canDrawOverlays(context)`.

## Suggested build order

1. **Capture spike** — service + `MediaProjection` + save one frame to disk. Confirms
   MLBB isn't setting `FLAG_SECURE` on your device before you build anything else.
2. **Overlay spike** — bubble that expands to a panel over the game, input passthrough
   verified.
3. **Hash tool + table** — offline `:tools` module, then match slots from the saved frames.
4. **Wire the tracker to the engine** — the payoff step, and the cheapest one.
5. **Calibration screen** — as soon as it fails on a second device.

Keep manual input working the whole way through. It is the fallback when detection is
unsure, and it is the reason the app still has value if detection ever has to be removed.
