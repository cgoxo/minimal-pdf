# Troubleshooting

**`adb devices` shows nothing** — try another cable (charge-only cables are common), and
check the phone's USB mode is not "charging only". Re-check USB debugging is on.

**Android Studio shows dozens of red errors but the app builds and runs fine** — trust
Gradle, not the editor. `./gradlew assembleDebug` is the source of truth; the editor uses its
own bundled Kotlin/Compose analyzer, and when that disagrees with the project's version you get
phantom errors like *"Argument type mismatch: … but ComposableFunction1<PaddingValues, Unit>
was expected"* on ordinary `Scaffold { }` and `Column { }` calls. In order: **File → Invalidate
Caches… → Invalidate and Restart**; check **Settings → Languages & Frameworks → Kotlin → K2
mode** is enabled; check the Jetpack Compose plugin is enabled. If it persists, it is your
Studio build (this project has been developed against one bundling a *dev* Kotlin, 2.4.255-dev,
while the project itself uses stable 2.4.10) and the red squiggles are cosmetic.

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

**The system back gesture fires while dragging a crop corner** — the drawing surface is inset
20 dp from the screen edges and marked with `systemGestureExclusion()`, but Android caps how
much edge a window may claim. If a corner still lands under the back strip, drag the *opposite*
corner instead, or turn off gesture navigation in Settings → System → Gestures.

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

[← Documentation index](README.md) · [Project README](../README.md)
