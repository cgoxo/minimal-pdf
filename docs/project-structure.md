# Project structure

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

[← Documentation index](README.md) · [Project README](../README.md)
