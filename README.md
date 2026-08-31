<div align="center">

<img src="docs/icon.png" width="112" alt="MinimalPDF icon">

# MinimalPDF

**A free, ad-free document scanner and PDF creator for Android.**

Point the camera at a page — it finds the edges, straightens the page, cleans it up, and saves
a PDF to `Documents/minimalPdf`. Read, annotate, sign and re-edit it, all inside the app.

[**⬇ Download the APK**](https://github.com/cgoxo/minimal-pdf/releases/latest) · 3.9 MB · Android 10+

</div>

---

## Features

- **Scan** with live page-edge detection, and drag the corners when it guesses wrong
- **Filters** — Original, Greyscale, B&W and a shadow-flattening Document mode, plus Enhance
- **Draw** with a brush: colour picker, presets, an eyedropper, adjustable size, undo
- **Text and signatures** — drag, resize, rotate. Signatures can be drawn on a pad or
  **scanned off paper**, with the ink lifted onto a transparent background
- **Non-destructive** — pages store instructions, never flattened pixels, so any page of any
  document can be reopened and re-edited with every slider where you left it
- **In-app viewer** — no hand-off to Drive or anything else
- No ads, no accounts, no subscriptions, no analytics. One permission: `CAMERA`

## Build it

```bash
git clone https://github.com/cgoxo/minimal-pdf.git
cd minimal-pdf
./gradlew installDebug     # with a phone connected over USB
```

Or open the folder in Android Studio and press ▶. Full walkthrough:
[Getting started](docs/getting-started.md).

## Documentation

Written for someone who has never built an Android app — see **[docs/](docs/README.md)**.

| | |
|---|---|
| [Getting started](docs/getting-started.md) | Requirements, USB debugging, first run |
| [Android basics](docs/android-basics.md) | The vocabulary, tied to this codebase |
| [Project structure](docs/project-structure.md) | Every file, annotated |
| [How it works](docs/how-it-works.md) | One page followed from camera to PDF |
| [Key ideas](docs/key-ideas.md) | The five ideas behind most of the code |
| [Architecture](docs/architecture.md) | Views vs logic, and is this MVVM? |
| [Compose previews](docs/previews.md) | Rendering UI without a device |
| [Troubleshooting](docs/troubleshooting.md) | When something goes wrong |
| [Exercises](docs/exercises.md) | Ten changes to make, easiest first |
| [Known limitations](docs/limitations.md) | What it does not do |

## How it's built

Kotlin and Jetpack Compose, `minSdk 29`. The interesting parts are written against the Android
platform rather than handed to a third-party SDK — **no OpenCV, no ML Kit, no PDF library**:

| | |
|---|---|
| Page detection | Sobel + a gradient-guided Hough transform, by hand |
| Perspective correction | `Matrix.setPolyToPoly` |
| Filters | `ColorMatrix`, plus summed-area tables for local-mean work |
| PDF writing | `android.graphics.pdf.PdfDocument` |
| PDF viewing | `android.graphics.pdf.PdfRenderer` |
| Camera | CameraX |
