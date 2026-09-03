# Known limitations

Honest list, so nothing surprises you:

- **Not everything has been run on hardware.** The app has been installed and exercised on a
  device, but the most recent batch — form-field detection, search, multi-select, text
  rotation, tap-to-focus — was built with no device attached and has only been verified by
  unit test and compiler. Expect a rough edge or two there; start with Logcat.
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
  dark marks are in the frame. Photograph a page with a paragraph on it and you'll get the
  paragraph as a "signature". Frame just the signature. The trim ignores isolated specks — a
  pixel needs two ink neighbours before it counts towards the bounds — but a genuine second
  mark inside the box will still be included, because it has no way to know you didn't mean it.
- **Form-field detection reads shapes, not characters.** `FieldDetector` finds blank rules and
  boxes geometrically. It has no idea what any field is *called*, cannot tell a signature line
  from a date line, and will miss a field drawn in a way it does not recognise — a shaded box
  with no border, say. Labelling fields would need OCR, which this app deliberately does not
  carry. See exercise 10.
- **No sorting or folders.** Documents are listed newest-first, always. There is search (by
  name only — the app cannot search *inside* a document, again because there is no OCR) and
  there is multi-select for sharing and deleting, but no way to organise or re-order.
- **The app can only see PDFs it owns.** Scoped storage tracks ownership per file, and
  ownership can be lost — an uninstall, a file replaced by another process, a copy pushed over
  `adb`. When that happens the app goes permanently blind to a PDF sitting in `Documents/Scanly`
  with its own name on it, and the document reverts to a draft. The pages are never lost, so
  saving again produces a fresh, owned PDF; the orphaned file has to be removed by hand.
- **PDFs exported before the rename stay in `Documents/minimalPdf`.** Reads span both folders
  so nothing disappears, but nothing is moved either — re-saving a document is what migrates
  its PDF across.
- **Release builds need a keystore.** `app/build.gradle.kts` reads `keystore.properties` from
  the project root, which is gitignored along with `*.jks` — so a fresh clone builds and runs
  debug fine, but cannot produce a signed release until you generate your own key:
  `keytool -genkeypair -keystore ~/keystores/scanly-release.jks -alias scanly
  -keyalg RSA -keysize 4096 -validity 10000`. Losing that key means never being able to ship
  an update that upgrades an existing install.
- **`minSdk 29`** means Android 9 and older can't install it.

---

[← Documentation index](README.md) · [Project README](../README.md)
