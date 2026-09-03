# Key ideas

## Five ideas that explain most of the code

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
for this reason: `THUMB_DIM 400`, `EDIT_DIM 1600`, `EXPORT_DIM 3000`. The capture itself is
kept at up to 3200 px (`CaptureSaver.MAX_DIM`), so the export is limited by the photo rather
than by the pipeline. Those numbers are the quality/memory dial: raising them sharpens the
output and costs RAM in exactly the ratio above. The manifest sets `android:largeHeap="true"`
to buy headroom for them.

### 4. Slow work never runs on the main thread

The main thread draws the UI 60+ times a second. Anything slow goes to a coroutine:
`withContext(Dispatchers.Default)` for image processing, `Dispatchers.IO` for files.
In Compose the tidy way is `produceState(key1, key2) { ... }` — it launches the work, and if
a key changes (you moved the slider again) it **cancels** the previous run instead of
queueing another. That single behaviour is what keeps the editor responsive.

The filter sliders take it one step further, because filtering a 1600px page is a few hundred
milliseconds of honest work and a finger produces dozens of values a second. So the page is
rendered **twice**: immediately against a 720px copy, which is what tracks your finger, and at
full resolution only after the value has been still for 260 ms. Because `produceState` cancels
on every change, the expensive render starts *zero* times during a drag — the cancellation
happens inside the `delay`, before any work begins:

```kotlin
value = null
delay(SETTLE_MS)          // ← cancelled here if the slider moves again
value = withContext(Dispatchers.Default) { Filters.apply(base, filter) }
```

The same debounce-by-cancellation trick saves the page to disk (`LaunchedEffect` + `delay(400)`),
for a completely different reason. Worth recognising as a pattern.

### 5. State flows one way

Data lives in `DocumentRepository` and is exposed as a `StateFlow`. Screens read it and
render; user actions call the repository, which writes to disk and emits a new value, which
re-renders the screens. The UI never holds the truth — it only displays it. Editor edits are
written back with a small debounce (`LaunchedEffect` + `delay(400)`), so dragging a slider
doesn't hammer the disk, and leaving via the system back gesture never loses work.

## Where your files actually live on the phone

Two different places, on purpose:

| What | Where | Who can see it | Survives uninstall? |
|---|---|---|---|
| Original captures + `manifest.json` | app-private `filesDir/docs/<docId>/` | only this app, no permission needed | no |
| Signatures | app-private `filesDir/signatures/` | only this app | no |
| **The exported PDFs** | **`Documents/Scanly/`** | any app, the Files app, your computer | yes |

The rule since Android 10 ("scoped storage"): an app can freely write into a public
collection via **MediaStore**, and can always read back **its own** entries there without any
storage permission. That is why this app asks for camera access and nothing else.

Note the word *own*. Ownership is tracked per file by package name, and it can be lost — an
uninstall, or a file replaced by another process. When that happens the app can no longer see
a PDF it wrote itself, sitting in a folder you can see, with its name on it. `refresh()`
reconciles against the folder and reverts such documents to drafts rather than leaving a card
that opens nothing; because the pages themselves are private and were never lost, saving again
fixes it. See [Known limitations](limitations.md).

Reads also span the old `Documents/minimalPdf` folder, so PDFs exported before the rename are
still found. Writes only ever go to the new one.

Writing a file via MediaStore is a three-step dance you can read in `PdfStore.write`:
insert a row with `IS_PENDING = 1` → write the bytes to the returned URI → set
`IS_PENDING = 0` to publish it. The flag stops other apps from seeing a half-written file.

To look at the private folder from your Mac (debug builds only):

```bash
adb shell run-as com.minimal.pdfcreate ls -R files
```

And the PDFs are simply visible in the phone's **Files** app under **Documents → Scanly**.

---

[← Documentation index](README.md) · [Project README](../README.md)
