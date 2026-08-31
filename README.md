# MinimalPDF

A small, free, no-nonsense document scanner for Android. Point the camera at a page, it
finds the edges, straightens the page, cleans it up, and saves a PDF into
`Documents/minimalPdf` on your phone. You can then open, draw on, sign and re-edit it —
all inside the app.

**This project exists to learn Android development.** So most of the interesting parts
(edge detection, image filters, PDF writing, PDF viewing) are written directly against the
Android platform instead of being handed off to a big third-party library. The code is
meant to be read.

If you have never built an Android app before, start at [Part 0](#part-0--run-it-first)
and read this file top to bottom. It assumes no prior Android knowledge.

---

## Contents

- [Part 0 — Run it first](#part-0--run-it-first)
- [Part 1 — Android vocabulary in ten minutes](#part-1--android-vocabulary-in-ten-minutes)
- [Part 2 — What all these files are](#part-2--what-all-these-files-are)
- [Part 3 — Follow one page from camera to PDF](#part-3--follow-one-page-from-camera-to-pdf)
- [Part 4 — The five screens](#part-4--the-five-screens)
- [Part 5 — Five ideas that explain most of the code](#part-5--five-ideas-that-explain-most-of-the-code)
- [Part 6 — Where your files actually live on the phone](#part-6--where-your-files-actually-live-on-the-phone)
- [Part 7 — When something goes wrong](#part-7--when-something-goes-wrong)
- [Part 8 — Exercises, easiest first](#part-8--exercises-easiest-first)
- [Part 9 — Known limitations](#part-9--known-limitations)
- [Part 10 — Architecture: where views end and logic begins](#part-10--architecture-where-views-end-and-logic-begins)
- [Part 11 — Composable previews](#part-11--composable-previews)

---

## Part 0 — Run it first

### What you need

| Thing | Why | Notes |
|---|---|---|
| **Android Studio** | The IDE. Also installs the Android SDK and `adb`. | Already installed on this machine. |
| **A JDK** | Gradle (the build tool) runs on Java. | Already installed. Gradle downloads the exact JDK it needs by itself. |
| **An Android phone, Android 10 or newer** | The app uses the camera, which an emulator fakes badly. | Or an emulator, see below. |
| **A USB cable** | To install the app on the phone. | Any data cable (some charge-only cables won't work). |

### Turn on developer mode on your phone (one time)

1. **Settings → About phone**
2. Tap **Build number** seven times. It will say "You are now a developer".
3. Go back to **Settings → System → Developer options**
4. Turn on **USB debugging**
5. Plug the phone into the Mac. A dialog appears on the phone: **Allow USB debugging** → tick
   "Always allow" → **Allow**.

Check the Mac can see it:

```bash
~/Library/Android/sdk/platform-tools/adb devices
```

You should see a line with your device ID and the word `device`. If it says `unauthorized`,
look at the phone screen — you missed the dialog.

### Build and install

From the project folder (`android_dev/testApplication`):

```bash
./gradlew installDebug      # builds the app and installs it on the connected phone
```

Or just build without installing:

```bash
./gradlew assembleDebug     # produces app/build/outputs/apk/debug/app-debug.apk
```

The very first build downloads Gradle, the Android plugin and all the libraries — expect a
few minutes and a lot of console output. Later builds take a few seconds.

> `./gradlew` is a small script committed into the repo ("the Gradle wrapper"). It downloads
> the exact Gradle version this project expects, so you never install Gradle yourself.

**Or, from Android Studio:** File → Open → select the `testApplication` folder → wait for
"Gradle sync" to finish (progress bar at the bottom) → pick your device in the toolbar
dropdown → press the green ▶ Run button. That does exactly the same thing.

### About emulators

An emulator is a simulated phone running on your Mac. This machine has **no emulator image
installed yet**, so right now you need a real device. To create one: Android Studio →
**Device Manager** (phone icon in the right sidebar) → **Create Device** → pick e.g. Pixel 7 →
download a system image. Note the emulator's "camera" is a fake rotating scene, so edge
detection won't do anything sensible there — everything else (home, viewer, editor, export)
works fine.

### First run

1. Tap the **+** button (bottom right).
2. Android asks for camera permission → **Allow**.
3. Point at a page on a contrasting surface. A green quadrilateral should snap to the page
   edges.
4. Tap the big white shutter. The thumbnail bottom-left shows what you captured, with a
   count badge.
5. Capture more pages, then tap the green **✓** bottom-right.
6. On the Review screen, tap a page to edit it, or tap **Save PDF**.
7. You're back home, with the PDF listed. Tap it to read it in the app.

---

## Part 1 — Android vocabulary in ten minutes

You'll meet these words constantly. Here they are with the place you can see each one in
*this* project.

**Activity** — one screen-sized entry point into an app; the thing Android launches when you
tap the icon. Modern apps often have exactly one. Ours is `MainActivity.kt`.

**Composable / Jetpack Compose** — Compose is the modern way to build Android UI: you write
Kotlin functions annotated `@Composable` that *describe* what the screen should look like for
the current data, and the framework redraws when the data changes. The old way was XML layout
files; we don't use those. Every file under `ui/` is Compose.

**Recomposition** — when data a composable reads changes, Compose re-runs that function to
produce the new UI. This happens constantly, which is why you must not do slow work (like
decoding a bitmap) directly inside a composable.

**Gradle** — the build tool. It reads `build.gradle.kts` files, downloads libraries, compiles
Kotlin, and packages everything into an APK.

**AGP (Android Gradle Plugin)** — the Gradle plugin that knows how to build *Android*
specifically. Version 9.3.2 here. Since AGP 9 it compiles Kotlin itself, which is why you
won't find a `kotlin-android` plugin in our build file.

**APK** — the installable app file. `app/build/outputs/apk/debug/app-debug.apk`.
The debug APK is fat (~76 MB) because it carries debugging and Compose tooling; a release
build with shrinking enabled is a fraction of that.

**SDK / API level** — every Android version has a number. Android 10 = API 29,
Android 15 = API 35, and so on. Three settings in `app/build.gradle.kts` use them:

| Setting | Ours | Meaning |
|---|---|---|
| `minSdk` | 29 | Oldest Android we support. Phones older than Android 10 can't install it. |
| `targetSdk` | 37 | "I have tested against this version's behaviour rules." Affects how Android treats you. |
| `compileSdk` | 37 | Which version's APIs you're allowed to *call* at compile time. |

We chose `minSdk 29` deliberately: from Android 10 onward, saving into the shared
`Documents` folder needs no storage permission at all, which removes a whole legacy code
path. See [Part 6](#part-6--where-your-files-actually-live-on-the-phone).

**AndroidManifest.xml** — the app's ID card. Declares the app name, icon, which permissions
it wants, and which Activity is the launcher. Ours is 28 lines and declares exactly one
permission: `CAMERA`.

**`res/`** — resources: icons, colours, strings, themes. Referenced from code as
`R.string.app_name` and from XML as `@string/app_name`. We keep almost nothing here because
Compose puts colours and dimensions in Kotlin instead.

**Permission** — some capabilities need the user's explicit consent at runtime. We request
`CAMERA` in `CameraScreen.kt` with `rememberLauncherForActivityResult`.

**Coroutine / suspend / Dispatchers** — Kotlin's way of doing background work.
`Dispatchers.Default` = a pool of threads for CPU work (our image processing),
`Dispatchers.IO` = for disk/network waiting, and the **main thread** is the only one allowed
to touch the UI. Blocking the main thread for more than ~16 ms drops a frame; for a second or
more the system shows "app not responding".

**Logcat** — the device's log stream, where crashes and stack traces appear. In Android
Studio it's the "Logcat" tab at the bottom. From the terminal:

```bash
~/Library/Android/sdk/platform-tools/adb logcat --pid=$(~/Library/Android/sdk/platform-tools/adb shell pidof com.minimal.pdfcreate)
```

**Bitmap** — an uncompressed image in memory. Cost is `width × height × 4` bytes. A 12-megapixel
photo is ~48 MB *decoded*, which is why we always decode downscaled — see
[Part 5](#part-5--five-ideas-that-explain-most-of-the-code).

---

## Part 2 — What all these files are

```
testApplication/
├── gradlew, gradlew.bat, gradle/wrapper/   the Gradle wrapper: run builds without installing Gradle
├── settings.gradle.kts                     names the project, lists its modules (just ":app")
├── build.gradle.kts                        top-level build config (declares plugins, applies none)
├── gradle/libs.versions.toml               ⭐ every library + version, in one place ("version catalog")
├── gradle.properties                       JVM memory, Gradle feature flags
├── local.properties                        path to your Android SDK — machine specific, never commit
└── app/                                    the one and only module (an "app module")
    ├── build.gradle.kts                    ⭐ how THIS module is built: sdk levels, dependencies
    └── src/main/
        ├── AndroidManifest.xml             permissions + launcher activity
        ├── res/                            icons, strings, themes
        └── java/com/minimal/pdfcreate/     ⭐ all our Kotlin (yes, it lives in a folder called "java")
```

The Kotlin, grouped by job:

```
com/minimal/pdfcreate/
├── MainActivity.kt        the single Activity + the navigation graph (which screen leads where)
├── AppContainer.kt        creates the one shared DocumentRepository
│
├── data/                  ── what a document IS, and how it is stored ──
│   ├── Model.kt           ScanDocument, Page, Stroke, Overlay, Quad, FilterSettings
│   ├── DocumentRepository.kt   reads/writes documents as JSON in the app's private folder
│   └── PdfStore.kt        writes/lists/deletes PDFs in the shared Documents/minimalPdf folder
│
├── imaging/               ── pixels ──
│   ├── CaptureSaver.kt    turns the camera's JPEG bytes into an upright, sane-sized bitmap/file
│   ├── EdgeDetector.kt    finds the four corners of a page in a frame
│   ├── Filters.kt         brightness/contrast/greyscale/B&W/document look
│   ├── LocalMean.kt       greyscale + summed-area table: fast "how bright is the paper here?"
│   ├── SignatureExtractor.kt  lifts ink off a photo of paper into a transparent PNG
│   └── PageRenderer.kt    ⭐ the render pipeline everything else calls
│
├── pdf/
│   └── PdfExporter.kt     draws the rendered pages into a real PDF file
│
└── ui/                    ── screens ──
    ├── theme/Theme.kt     colours, light/dark
    ├── common/            shared helpers: the colour picker, thumbnails, geometry maths
    ├── home/HomeScreen.kt        list of documents + the ➕ button
    ├── camera/CameraScreen.kt    live capture with the green edge overlay
    ├── review/ReviewScreen.kt    page list, reorder, delete, "Save PDF"
    ├── editor/                   filter / draw / text / sign / crop
    │   └── SignatureScanDialog.kt  photograph a signature and lift it off the paper
    └── viewer/PdfViewerScreen.kt in-app PDF reader
```

**Suggested reading order if you want to understand the codebase:**
`Model.kt` → `DocumentRepository.kt` → `PageRenderer.kt` → `HomeScreen.kt` →
`CameraScreen.kt` → `PageEditorScreen.kt`. About 3,000 lines total, so it is genuinely
readable in an afternoon.

---

## Part 3 — Follow one page from camera to PDF

This is the whole app in one story. Every step names the file, so you can open it and read
along.

**1. You open the camera.** `CameraScreen.kt` asks CameraX to start three things at once
(they're called *use cases*): a `Preview` (what you see), an `ImageCapture` (what happens
when you press the shutter), and an `ImageAnalysis` (a stream of frames for us to inspect).

**2. Every frame gets inspected.** The analysis frame arrives as raw camera data. We only
read its first "plane", the **luma** plane — brightness without colour, which is exactly what
edge detection wants and costs nothing to obtain. It goes to `EdgeDetector.detect(...)`.

**3. Finding the page.** `EdgeDetector.kt` shrinks the frame to ~240 px, blurs it, runs a
**Sobel** operator (the classic "how fast is brightness changing here, and in which
direction" filter), then a **Hough transform** to turn those edge pixels into actual straight
lines, and finally picks the strongest two vertical and two horizontal lines and intersects
them into four corners. If the result looks implausible it returns `null` instead of
guessing. The maths is explained in `01-notes.md` in the Obsidian vault.

**4. You see the green quad.** The four corners come back in **normalised coordinates** —
`(0,0)` is top-left of the frame, `(1,1)` is bottom-right, regardless of pixel size. The
overlay in `CameraScreen.kt` scales them into the preview area and draws them.

**5. You press the shutter.** CameraX hands us JPEG bytes plus a rotation angle.
`CaptureSaver.kt` decodes them downscaled to 2600 px, physically rotates the pixels upright,
and writes a JPEG into the app's private folder. (Cameras normally record rotation as EXIF
metadata *without* rotating the pixels, and `BitmapFactory` ignores EXIF — that's the classic
"why is my scan sideways" bug, killed here once and for all.)

**6. A Page is recorded.** A new `Page` (see `Model.kt`) is appended to the document, storing
the file name plus the corner quad that was on screen when you fired. The document is written
back as `manifest.json`. **The photo itself is never modified again.**

**7. You edit.** In `PageEditorScreen.kt` each tab changes one field of that `Page`:
crop quad, rotation, `FilterSettings`, a list of `Stroke`s, a list of `Overlay`s (text and
signatures). Nothing is "applied" — it's all just numbers describing what should happen.

**8. Anything that needs to show the page calls one function.**
`PageRenderer.render(...)`:

```
decode the JPEG (downscaled)
  → crop + straighten using the quad     (Matrix.setPolyToPoly)
  → rotate
  → apply the filter                     (ColorMatrix, or per-pixel for B&W/Document)
  → draw the brush strokes
  → draw the text and signature overlays
  → return a Bitmap
```

The *only* difference between a 400 px thumbnail, the 1400 px editor preview and the 2200 px
export is the `maxDim` argument. That is why everything is stored in normalised coordinates.

**9. You press Save PDF.** `PdfExporter.kt` renders each page at export size and draws it
onto a page of Android's own `PdfDocument`. PDF pages are measured in **points** (1/72 inch),
so A4 is 595 × 842 no matter how many pixels your scan has.

**10. The file lands in `Documents/minimalPdf`.** `PdfStore.kt` does this through
**MediaStore**, Android's index of shared media, which is the sanctioned way to write into a
public folder without asking for storage permission.

**11. You read it in the app.** `PdfViewerScreen.kt` opens that file with `PdfRenderer` —
the platform's own PDF rasteriser — and turns pages into bitmaps as you scroll. No Drive, no
external app, no hand-off.

---

## Part 4 — The five screens

### Home — `ui/home/HomeScreen.kt`

Lists every document, newest first, each with a thumbnail rendered through the normal
pipeline. The ➕ button creates an empty document and jumps to the camera. The ⋮ menu has
edit / rename / share / delete.

*Concepts to notice:* `Scaffold` (the standard page skeleton: top bar, content,
floating action button), `LazyColumn` (a list that only builds the rows currently visible —
Android's `RecyclerView` in Compose form), `collectAsStateWithLifecycle` (subscribing the UI
to the repository's `StateFlow` so it updates itself when documents change).

### Camera — `ui/camera/CameraScreen.kt`

Live preview, green edge overlay, thumbnail + count bottom-left, shutter in the middle,
green ✓ bottom-right.

*Concepts to notice:* runtime permission requests; `AndroidView` (how you embed an old-style
Android `View` — here CameraX's `PreviewView` — inside Compose); binding CameraX use cases to
a lifecycle so the camera automatically stops when you leave the screen; and the fact that
`image.close()` in the analyser is mandatory or the frame stream stalls.

### Review — `ui/review/ReviewScreen.kt`

Grid of pages with move-earlier / move-later / delete, a file-name field, **Add pages**, and
**Save PDF**.

*Concepts to notice:* `LazyVerticalGrid`; doing the export inside a coroutine on
`Dispatchers.IO` so the UI keeps responding while a multi-page PDF is written.

### Editor — `ui/editor/PageEditorScreen.kt` + `EditorPanel.kt`

Five tabs over a single drawing surface:

| Tab | Does |
|---|---|
| **Filter** | Original / Greyscale / B&W / Document, with brightness, contrast, saturation and threshold sliders |
| **Draw** | Freehand brush: colour picker (hue + saturation/value square + swatches), size slider, undo, clear |
| **Text** | Add a text box, drag it, change its size and colour |
| **Sign** | Two ways to get a signature: **Draw** it on a pad with your finger, or **Scan from paper** — photograph a signature written on paper and the app lifts the ink off the page. Either way it is saved as a reusable transparent PNG that can be dropped on any page and resized |
| **Crop** | Four draggable corner handles, plus auto-detect and rotate |

**Scanning a signature** (`SignatureScanDialog.kt` + `imaging/SignatureExtractor.kt`) is worth
reading on its own: a phone photo of paper is never evenly lit, so instead of one brightness
cutoff, every pixel is compared to the *local* paper brightness around it, and how much darker
it is becomes that pixel's **transparency**. Ink survives, paper disappears, and the soft edges
of the strokes are preserved rather than turned into a fax. The result is auto-trimmed to the
ink and previewed with a sensitivity slider before anything is saved.

*Concepts to notice:* one `Canvas` composable draws the image *and* the annotations in the
same coordinate space; `pointerInput` + `detectDragGestures` for the interactions;
`produceState` to compute bitmaps off the main thread and cancel superseded work; and the
split between filters that can be previewed for free (a `ColorMatrix` handed to the GPU while
drawing) and filters that need real per-pixel work (B&W, Document).

### Viewer — `ui/viewer/PdfViewerScreen.kt`

Scroll and pinch-zoom, with a share button.

*Concepts to notice:* `PdfRenderer` allows only one open page at a time and is not thread
safe, so access is serialised with a `Mutex`; pages are rasterised lazily as they scroll into
view rather than all at once.

---

## Part 5 — Five ideas that explain most of the code

### 1. Normalised coordinates

Every point we store — crop corners, brush strokes, text positions — is a fraction between
0 and 1 rather than a pixel. A stroke at `(0.5, 0.5)` is in the middle of the page whether
that page is being drawn 400 px wide on a thumbnail or 2200 px wide in the export. Without
this, annotations would drift the moment anything is resized.

### 2. Non-destructive editing

A `Page` stores *instructions*, not results: "crop with these corners, contrast 1.4, these
strokes, this signature here". The captured JPEG is read-only forever.

Three things fall out of this for free:
- Undo is `strokes.dropLast(1)` — no image history to keep.
- Re-opening a document saved last month gives you every slider exactly where you left it.
- The thumbnail, the preview and the export can't disagree, because they run the same code.

### 3. Bitmaps are expensive; always decode downscaled

`width × height × 4` bytes. Decode a 12 MP photo naively and you've allocated ~48 MB for one
image. `PageRenderer.decode` uses `inSampleSize` (decode every Nth pixel — cheap, done inside
the decoder) to get roughly the right size, then scales precisely. Three size constants exist
for this reason: `THUMB_DIM 400`, `EDIT_DIM 1400`, `EXPORT_DIM 2200`.

### 4. Slow work never runs on the main thread

The main thread draws the UI 60+ times a second. Anything slow goes to a coroutine:
`withContext(Dispatchers.Default)` for image processing, `Dispatchers.IO` for files.
In Compose the tidy way is `produceState(key1, key2) { ... }` — it launches the work, and if
a key changes (you moved the slider again) it **cancels** the previous run instead of
queueing another. That single behaviour is what keeps the editor responsive.

### 5. State flows one way

Data lives in `DocumentRepository` and is exposed as a `StateFlow`. Screens read it and
render; user actions call the repository, which writes to disk and emits a new value, which
re-renders the screens. The UI never holds the truth — it only displays it. Editor edits are
written back with a small debounce (`LaunchedEffect` + `delay(400)`), so dragging a slider
doesn't hammer the disk, and leaving via the system back gesture never loses work.

---

## Part 6 — Where your files actually live on the phone

Two different places, on purpose:

| What | Where | Who can see it | Survives uninstall? |
|---|---|---|---|
| Original captures + `manifest.json` | app-private `filesDir/docs/<docId>/` | only this app, no permission needed | no |
| Signatures | app-private `filesDir/signatures/` | only this app | no |
| **The exported PDFs** | **`Documents/minimalPdf/`** | any app, the Files app, your computer | yes |

The rule since Android 10 ("scoped storage"): an app can freely write into a public
collection via **MediaStore**, and can always read back **its own** entries there without any
storage permission. That is why this app asks for camera access and nothing else.

Writing a file via MediaStore is a three-step dance you can read in `PdfStore.write`:
insert a row with `IS_PENDING = 1` → write the bytes to the returned URI → set
`IS_PENDING = 0` to publish it. The flag stops other apps from seeing a half-written file.

To look at the private folder from your Mac (debug builds only):

```bash
adb shell run-as com.minimal.pdfcreate ls -R files
```

And the PDFs are simply visible in the phone's **Files** app under **Documents → minimalPdf**.

---

## Part 7 — When something goes wrong

**`adb devices` shows nothing** — try another cable (charge-only cables are common), and
check the phone's USB mode is not "charging only". Re-check USB debugging is on.

**Gradle sync or build fails after you edit `build.gradle.kts`** — read the *first* error,
not the last. Gradle prints a long tail of noise after the real message. `./gradlew
assembleDebug --stacktrace` gives more.

**"Plugin ... is no longer required for Kotlin support since AGP 9.0"** — you added the
`kotlin-android` plugin. AGP 9 compiles Kotlin itself; remove it.

**The build is fine but the app crashes on launch** — open Logcat and look for
`FATAL EXCEPTION`. The first line of the stack trace under it that mentions
`com.minimal.pdfcreate` is your bug.

**Camera preview is black** — check the permission was actually granted
(Settings → Apps → MinimalPDF → Permissions), and that no other app is holding the camera.

**No green quad appears** — that's the detector being honest. It needs a page whose edges
actually contrast with what's underneath: white paper on a dark desk works, white paper on a
white table does not. Capture anyway and fix the corners by hand in the **Crop** tab.

**A scanned signature is blotchy, or picks up the lines of the notebook** — drag the **Ink
sensitivity** slider down: it raises the bar for what counts as ink, so paper texture and faint
rules drop out first. If the whole thing is grey mush, the photo probably has a shadow falling
across the paper — the extractor compares each pixel to its local surroundings, but it cannot
invent contrast that isn't there. Retake in flatter light.

**Scan comes out sideways** — that would be an `CaptureSaver` bug; the rotation angle from
`imageInfo.rotationDegrees` is what to inspect.

**The PDF doesn't appear in the Files app** — MediaStore may not have indexed it. Check
`PdfStore.write` completed (no exception in Logcat) and that `IS_PENDING` was cleared.

**Everything is slow while dragging a slider** — something expensive is running on the main
thread, or a `produceState` key is missing so the work is being redone rather than cancelled.

---

## Part 8 — Exercises, easiest first

Each one is a real change with a real file to open.

1. **Change the app name and colours.** `res/values/strings.xml`, then the palettes in
   `ui/theme/Theme.kt`. Smallest possible round trip through the build.
2. **Add a "Sepia" filter mode.** Add a case to the `FilterMode` enum in `Model.kt`, a chip
   in `EditorPanel.kt`, and the maths in `Filters.kt`. Teaches you how one feature threads
   through model → UI → pixels.
3. **Add a page-number stamp on export.** In `PdfExporter.export` you already have the
   `Canvas` and the page index — draw "3 / 8" in the corner.
4. **Make the PDF page size a choice** (A4 / Letter / fit-to-image). Currently hard-coded in
   `PdfExporter`.
5. **Rename the exported PDF when the document is renamed.** Today `PdfExporter.fileName`
   keeps the original name once exported — see [Part 9](#part-9--known-limitations).
6. **Save the editor's brush colour and size between sessions.** Look up
   `SharedPreferences` or `DataStore`.
7. **Drag to reorder pages** in the Review screen, replacing the arrow buttons.
8. **Introduce a `ViewModel`.** Move the editor's state out of `remember { }` and into a
   `ViewModel`, which survives screen rotation properly. This is the standard Android
   architecture and worth doing once by hand.
9. **Swap the JSON manifest for Room**, Android's SQLite layer — entities, a DAO, a
   migration. The single biggest "real Android app" skill in this list.
10. **Add OCR** with ML Kit to make exported PDFs searchable. Hard, and genuinely useful.

---

## Part 9 — Known limitations

Honest list, so nothing surprises you:

- **Not yet run on hardware.** The app compiles and packages cleanly, but this machine has no
  device attached and no emulator image, so it has not been exercised at runtime. Expect to
  fix a rough edge or two on first run — start with Logcat.
- **Edge detection is home-grown**, so it is weaker than OpenCV's on low-contrast, cluttered
  or shadowed backgrounds. That is a deliberate trade (no 40 MB native dependency, and the
  algorithm stays readable); the manual corner handles are the safety net.
- **Editing a page does not re-export the PDF automatically.** Your edits are saved
  immediately, but the PDF on disk is only rewritten when you press **Save PDF** on the
  Review screen.
- **Renaming a document after export does not rename the PDF file** — the new name is used
  for display, the file keeps its original name.
- **No `ViewModel`.** Screen state lives in `remember { }`, which does not survive process
  death (rare, but possible if Android reclaims memory while you're in another app). Edits are
  debounced to disk, so the loss would be at most the last few hundred milliseconds. See
  exercise 8.
- **Signature scanning is ink extraction, not handwriting recognition.** It lifts *whatever*
  dark marks are in the frame, auto-trimmed to their bounding box. Photograph a page with a
  paragraph on it and you'll get the paragraph as a "signature". Frame just the signature.
- **Sorting, search, folders, multi-select** — none of it. Deliberately minimal.
- **The debug APK is ~76 MB.** That's debug tooling, not the app. Enabling shrinking in the
  release build (`optimization { enable = true }` in `app/build.gradle.kts`) will cut it
  dramatically.
- **`minSdk 29`** means Android 9 and older can't install it.

---

## Part 10 — Architecture: where views end and logic begins

### The three layers

```
        ui/            ← VIEWS. Compose only. Draws state, emits user events. No file IO,
         │                no pixel maths, no PDF knowledge.
         │  calls
         ▼
   data/ + imaging/ + pdf/     ← LOGIC. Plain Kotlin objects and classes. No Compose imports,
                                 no Context except where Android forces it (MediaStore).
```

A useful test: **open any file under `imaging/` or `pdf/` and search for the word
`Composable`. There are none.** Those files could be lifted into a command-line tool
unchanged. Conversely, no file under `ui/` decodes a bitmap, writes a file or knows what a
summed-area table is — it calls something that does.

| If you want to change… | Open |
|---|---|
| How a screen looks or behaves | `ui/<screen>/…Screen.kt` |
| A control panel's buttons and sliders | `ui/editor/EditorPanel.kt` |
| What a document *is* (fields, defaults) | `data/Model.kt` |
| How documents are saved / loaded / deleted | `data/DocumentRepository.kt` |
| Where PDFs land on the phone | `data/PdfStore.kt` |
| Anything about pixels | `imaging/` |
| The PDF file itself | `pdf/PdfExporter.kt` |
| Which screen leads to which | `MainActivity.kt` |

### Which pattern is this? (MVC, MVVM, MVI…)

Short answer: **the app follows the *data flow* of MVVM but has no ViewModel class.** It is
closer to "repository + state hoisting", which is a legitimate small-app Compose pattern —
but it is not textbook MVVM, and you should know exactly where it differs.

The vocabulary, briefly:

- **MVC** — Model / View / Controller. The old desktop and early-Android pattern: the View
  reads from the Model, a Controller mutates it. Prone to tangled two-way updates.
- **MVVM** — Model / View / **ViewModel**. The View observes an *observable state object*
  exposed by a ViewModel and sends events to it; the ViewModel owns the state and talks to
  repositories. This is Google's recommended Android architecture.
- **MVI** — one immutable state object plus a stream of intents. MVVM with stricter rules.
- **MVCC** — multi-version concurrency control. Unrelated: that's a *database* technique for
  letting readers and writers work concurrently (Postgres, InnoDB). Nothing to do with UI.

What this app actually does:

```
DocumentRepository  ──  StateFlow<List<ScanDocument>>  ──▶  HomeScreen renders it
        ▲                                                          │
        └────────────  repo.save(doc) / repo.delete(id)  ◀──────────┘
```

That *is* the unidirectional loop MVVM asks for: one source of truth, the UI renders it, the
UI never mutates state in place, and writes go back through the repository which re-emits.
`DocumentRepository` plays the "Model" role, `StateFlow` is the observable, and the screens
are the Views.

What is missing versus real MVVM:

1. **No `ViewModel` class.** Screen state (`filter`, `strokes`, `crop`, which tab is open)
   lives in `remember { }` inside the composable. `remember` survives recomposition but **not**
   configuration changes or process death — a `ViewModel` survives both.
2. **Editor state is UI state, not model state, until it is saved.** We paper over this by
   writing to disk on a 400 ms debounce, so in practice very little can be lost, but that is a
   workaround, not the pattern.
3. **Screens call the repository directly**, so there is no seam where you could unit-test the
   screen's behaviour without Compose.

Why it was built this way: fewer moving parts to read while you're learning, and no DI
framework. `AppContainer` is a hand-rolled *service locator* — one object, one repository,
created once against the application context. Hilt would replace it in a real app.

**The upgrade path**, if you want to do it as an exercise (this is exercise 8): create
`PageEditorViewModel(repo, docId, pageId) : ViewModel`, move the `var … by remember` fields
into it as a single `MutableStateFlow<PageEditorState>`, expose `onFilterChange`,
`onStrokeFinished`, `onUndo` … and have the composable do
`val state by viewModel.state.collectAsStateWithLifecycle()`. The screen becomes a pure
function of state, the way `EditorPanel` already is. Start with the editor — it is the screen
with the most state and therefore the most to gain.

---

## Part 11 — Composable previews

Yes — and there are now eleven of them in the codebase. A **preview** renders a composable
inside Android Studio's design pane, with no device, no install and no running app. It turns a
two-minute build-install-navigate loop into a two-second one.

### Seeing them

Open any of these files in Android Studio and click **Split** or **Design** (top-right of the
editor):

| File | Previews |
|---|---|
| `ui/editor/EditorPanel.kt` | all five control panels — Filter, Filter (B&W), Draw, Text, Sign, Crop |
| `ui/home/HomeScreen.kt` | document card (light **and** dark), draft card, empty state |
| `ui/common/Common.kt` | the colour picker dialog |

Above each rendered preview Studio shows buttons for **interactive mode** (click and drag the
sliders without installing anything) and **run preview on device**.

### Writing one

```kotlin
@Preview(name = "Panel · Draw", showBackground = true, widthDp = 400)
@Composable
private fun DrawPanelPreview() = PanelPreview {
    DrawPanel(
        brushColor = Color(0xFFE53935), onBrushColor = {},
        brushWidth = 0.012f, onBrushWidth = {},
        canUndo = true, onUndo = {}, onClear = {},
    )
}
```

The rules:

- The function must be `@Composable` and **take no parameters** — the preview system has no
  way to supply them. (Except via `@PreviewParameter`, which feeds it a sample provider.)
- It can be `private`, and normally lives at the bottom of the file it previews.
- Wrap the content in your theme (`MinimalPdfTheme`) or it renders with default Material
  colours and looks nothing like the app. Pass `dynamicColor = false` so the preview doesn't
  try to read the device wallpaper palette.
- Stack multiple `@Preview` annotations on one function for variants — see the light/dark pair
  on `DocumentCardPreview` (`uiMode = Configuration.UI_MODE_NIGHT_YES`).
- Useful arguments: `showBackground`, `widthDp` / `heightDp`, `uiMode`, `fontScale`,
  `device = "id:pixel_7"`, `showSystemUi = true`.

The tooling is already wired up in `app/build.gradle.kts`:
`ui-tooling-preview` (the `@Preview` annotation, ships in the APK) and
`debugImplementation(ui-tooling)` (the renderer, debug builds only).

### Why *these* composables and not the screens

**A composable is previewable exactly as far as it is honest about its inputs.** The panels
preview beautifully because they are "dumb": every value they display arrives as a parameter
and every action leaves as a callback, so a preview is just a handful of literals. `HomeScreen`
and `PageEditorScreen` cannot be previewed as-is, because they reach out to a repository, the
camera and the file system — none of which exist in the preview pane.

That is not a preview limitation, it's a design signal. The fix is the same split as Part 10:
pull the drawing into a stateless `…Content(state, callbacks)` composable and leave the wiring
in the stateful wrapper. `EmptyState` in `HomeScreen.kt` is the smallest example of that split
— it was extracted from inside `HomeScreen` precisely so it could be previewed.

For the cases where a composable legitimately touches app services, there is an escape hatch,
used in `ui/common/PageThumbnail.kt`:

```kotlin
val previewing = LocalInspectionMode.current   // true only inside the preview pane
```

`PageThumbnail` skips the repository and renders a placeholder icon when previewing, which is
what lets the whole document card render. `SignPanel` uses the same trick for its saved-
signature strip. Use it sparingly — it is for leaf components, not an excuse to skip the split.

---

## Design notes

Longer write-ups — including the Hough transform maths, the ColorMatrix contrast formula, the
summed-area table trick behind Document mode, and the homography used for straightening —
live in the Obsidian vault at `pdf_create/00-plan.md` and `pdf_create/01-notes.md`.
