<div align="center">

<img src="docs/icon.png" width="112" alt="Scanly icon">

# Scanly

**A free, ad-free document scanner and PDF creator for Android.**

Point the camera at a page — it finds the edges, straightens the page, cleans it up, and saves
a PDF to `Documents/Scanly`. Read, annotate, sign and re-edit it, all inside the app.

[**⬇ Download the APK**](https://github.com/cgoxo/minimal-pdf/releases/latest) · 4.0 MB · Android 10+

</div>

---

## Why "Scanly"

**Scanner + friendly.** The name is the whole design brief.

Every free scanner app on the store is a scanner *first* and something else second — a
subscription funnel, an ad surface, an account you did not want, a cloud you did not ask to
upload your bank statements to. The scanning part is usually fine. Everything wrapped around
it is not.

Scanly is the scanner without the wrapper. No ads, no accounts, no subscriptions, no
analytics, one permission. It opens straight to your documents, it saves a real PDF to a
folder you can see in Files, and it never asks you for anything.

The friendly half runs deeper than what is missing, though. Nothing you do is final: a page
stores your crop, your filter and your annotations as *instructions*, so any page of any
document reopens months later with every slider exactly where you left it — there is no
version of this app where you have to rescan because you flattened something by accident.
Captures land looking like a scan rather than a photo, so the common case needs no work at
all, and the uncommon case is one tap away. And when something does go wrong — a PDF deleted
from the folder, a page detected badly — it tells you and hands you the way back, instead of
showing an error and stopping.

Small, quiet, and on your side. That is the friendly part.

---

## Features

- **Scan** with live page-edge detection, tap-to-focus, a flash toggle, and drag the corners
  when it guesses wrong
- **Import** photos from the gallery, or an existing PDF — its pages come in editable
- **Filters** — captures land as **Colour doc** by default: paper whitened and shadows
  flattened, colour kept. Original, Greyscale, B&W and Document are one tap away
- **Draw** and **Text** share a brush colour picker, presets, an eyedropper that samples
  straight off the page, and undo
- **Text and signatures** — drag, resize, rotate. Signatures can be drawn on a pad or
  **scanned off paper**, with the ink lifted onto a transparent background
- **Non-destructive** — pages store instructions, never flattened pixels, so any page of any
  document can be reopened and re-edited with every slider where you left it
- **Fill a scanned form** — Scanly finds the blank rules and boxes on a page and lets you
  tap one to type into it, skipping any it can see are already filled in
- **Search** your documents, and select several at once to share or delete
- **In-app viewer** — no hand-off to Drive or anything else
- **A4 output** — every page is exported as a real A4 sheet, portrait or landscape
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
| Page detection | Sobel + a gradient-guided Hough transform, by hand, with edge-polarity and border-contrast checks to tell a page from what is printed on it |
| Form-field detection | Local-mean ink mask, long thin horizontal runs merged into rules, rules paired into boxes, anything already written in discarded |
| Perspective correction | `Matrix.setPolyToPoly` |
| Filters | `ColorMatrix`, plus summed-area tables for local-mean work |
| PDF writing | `android.graphics.pdf.PdfDocument` |
| PDF viewing / import | `android.graphics.pdf.PdfRenderer` |
| Camera | CameraX |
