# How it works

## Follow one page from camera to PDF

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
guessing. 

**4. You see the green quad.** The four corners come back in **normalised coordinates** —
`(0,0)` is top-left of the frame, `(1,1)` is bottom-right, regardless of pixel size. The
overlay in `CameraScreen.kt` scales them into the preview area and draws them.

**5. You press the shutter.** CameraX hands us JPEG bytes plus a rotation angle.
`CaptureSaver.kt` decodes them downscaled to 3200 px, physically rotates the pixels upright,
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
  → rotate                               (so the crop editor previews rotation live)
  → crop + straighten using the quad     (Matrix.setPolyToPoly)
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

**10. The file lands in `Documents/Scanly`.** `PdfStore.kt` does this through
**MediaStore**, Android's index of shared media, which is the sanctioned way to write into a
public folder without asking for storage permission.

**11. You read it in the app.** `PdfViewerScreen.kt` opens that file with `PdfRenderer` —
the platform's own PDF rasteriser — and turns pages into bitmaps as you scroll. No Drive, no
external app, no hand-off.

## The five screens

### Home — `ui/home/HomeScreen.kt`

Lists every document, newest first, each with a thumbnail rendered through the normal
pipeline. The ➕ button expands upwards into **Scan pages / Import images / Import PDF**. The
⋮ menu has edit / rename / share / delete.

A **search box** appears once there are three or more documents — below that the list is its
own index, and a box asking what you are looking for is just something else in the way.

**Long-press any card** to start selecting. The top bar then becomes the selection's own
toolbar — select-all, share, delete — so what the buttons act on is never in doubt, and the
system back gesture leaves selection mode before it leaves the screen.

*Concepts to notice:* `Scaffold` (the standard page skeleton: top bar, content,
floating action button), `LazyColumn` (a list that only builds the rows currently visible —
Android's `RecyclerView` in Compose form), `collectAsStateWithLifecycle` (subscribing the UI
to the repository's `StateFlow` so it updates itself when documents change),
`combinedClickable` (tap and long-press on the same target), and `ACTION_SEND_MULTIPLE` for
sharing several PDFs at once.

### Camera — `ui/camera/CameraScreen.kt`

Live preview, green edge overlay, thumbnail + count bottom-left, shutter in the middle,
green ✓ bottom-right. A **flash toggle** sits top-right — it arms the flash for the next
shutter press rather than burning a torch while you frame — and **tapping the preview
focuses** there, because continuous autofocus hunts on a flat page: there is little for it to
lock onto until you say which part of the frame you mean.

*Concepts to notice:* runtime permission requests; `FocusMeteringAction` and
`meteringPointFactory` for turning a tap into a focus request; `AndroidView` (how you embed an old-style
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
| **Filter** | Colour doc (the default) / Original / Greyscale / B&W / Document. Original and Greyscale get brightness, contrast and saturation; **Colour doc, B&W and Document get a single "Ink sensitivity" knob** instead — they are ink decisions, not tone curves, so three sliders was two too many |
| **Draw** | Freehand brush: a **Colour** button opening a full hue + saturation/value picker, a row of one-tap preset swatches, **Pick from image** (an eyedropper — press and hold the page; a loupe shows the colour under your finger, slide to adjust, lift to accept), size slider, undo, clear |
| **Text** | Add a text box, drag it, change its colour, and **rotate** it with a slider or ±90° buttons. Tap it to select: a dashed frame appears with a red **✕** on the top-right corner to delete it and a green **grip** on the bottom-right to resize. Size is a grip job, not a slider — the same as signatures. The eyedropper works here too, and recolours a selected box in place. **Find blank fields** looks for the places a scanned form expects you to write |
| **Sign** | Same on-page frame — drag to move, corner grip to resize, ✕ to delete — plus a rotation slider and ±90° buttons. Saved signatures can be deleted from the strip with their own ✕. Two ways to get a signature: **Draw** it on a pad with your finger, or **Scan from paper** — photograph a signature written on paper and the app lifts the ink off the page. Either way it is saved as a reusable transparent PNG that can be dropped on any page and resized |
| **Crop** | Four draggable corner handles, plus auto-detect and rotate |

**Scanning a signature** (`SignatureScanDialog.kt` + `imaging/SignatureExtractor.kt`) is worth
reading on its own: a phone photo of paper is never evenly lit, so instead of one brightness
cutoff, every pixel is compared to the *local* paper brightness around it, and how much darker
it is becomes that pixel's **transparency**. Ink survives, paper disappears, and the soft edges
of the strokes are preserved rather than turned into a fax. After the shot you drag a box around the part of the photo that is
actually the signature — everything outside it is dimmed and discarded, which is what stops
other writing on the page coming along too. The result is auto-trimmed to the ink and
previewed live as you adjust the box and the sensitivity slider, before anything is saved.

**Filling in a scanned form** (`imaging/FieldDetector.kt`) is the other algorithm worth
reading on its own. A form marks its fields the same two ways whether it was printed or
photocopied: a **rule** to write on top of, and a **box** to write inside. Both are long thin
strokes of ink with nothing beside them. So: build an ink mask the same way the signature
scanner does (each pixel against its *local* paper brightness, never one global cutoff),
collect the long horizontal runs, merge them down through the rows they repeat on into rules,
pair rules into boxes where ink runs down both ends to close them — and then throw away every
candidate whose writing space already has ink in it, because a question somebody has already
answered is not a field worth offering. Tap a highlighted blank and the text you type is sized
and seated in the space rather than dropped in the middle of the page.

It reads *shapes, not characters*. It has no idea what any field is called; that would need
text recognition, which this app deliberately does not carry.

**Pinch with two fingers to zoom and pan the page** while editing; one finger keeps drawing,
cropping and dragging overlays. A reset control appears in the top bar while zoomed.

*Concepts to notice:* one `Canvas` composable draws the image *and* the annotations in the
same coordinate space; `pointerInput` + `detectDragGestures` for the interactions;
`produceState` to compute bitmaps off the main thread and cancel superseded work; and the
split between filters that can be previewed for free (a `ColorMatrix` handed to the GPU while
drawing) and filters that need real per-pixel work (B&W, Document, Colour doc).

Those per-pixel filters are previewed **twice**: once immediately against a small copy of the
page, so the picture tracks your finger down the slider, and once at full resolution after the
value has been still for a moment. `produceState` cancels the slow render on every change, so
a drag never queues up a backlog of full-size work it will only throw away.

### Viewer — `ui/viewer/PdfViewerScreen.kt`

Scroll and pinch-zoom, with a share button.

*Concepts to notice:* `PdfRenderer` allows only one open page at a time and is not thread
safe, so access is serialised with a `Mutex`; pages are rasterised lazily as they scroll into
view rather than all at once.

Pages are rendered to **the pixels actually on screen** — the window width times a quality
factor — rather than a fixed guess, and zoom is quantised into three tiers so that pinching in
re-rasterises once per tier instead of on every frame. That is what keeps text sharp when you
zoom rather than showing you magnified mush.

---

[← Documentation index](README.md) · [Project README](../README.md)
