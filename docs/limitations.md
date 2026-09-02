# Known limitations

Honest list, so nothing surprises you:

- **Not yet run on hardware.** The app compiles and packages cleanly, but this machine has no
  device attached and no emulator image, so it has not been exercised at runtime. Expect to
  fix a rough edge or two on first run — start with Logcat.
- **Edge detection is home-grown**, so it is weaker than OpenCV's on low-contrast, cluttered
  or shadowed backgrounds. That is a deliberate trade (no 40 MB native dependency, and the
  algorithm stays readable); the manual corner handles are the safety net.
- **Text overlays cannot be rotated** — only signatures can. Same mechanism, just not wired up.
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
- **Release builds need a keystore.** `app/build.gradle.kts` reads `keystore.properties` from
  the project root, which is gitignored along with `*.jks` — so a fresh clone builds and runs
  debug fine, but cannot produce a signed release until you generate your own key:
  `keytool -genkeypair -keystore ~/keystores/scanly-release.jks -alias scanly
  -keyalg RSA -keysize 4096 -validity 10000`. Losing that key means never being able to ship
  an update that upgrades an existing install.
- **`minSdk 29`** means Android 9 and older can't install it.

---

[← Documentation index](README.md) · [Project README](../README.md)
