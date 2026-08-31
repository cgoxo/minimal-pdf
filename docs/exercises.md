# Exercises

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
   keeps the original name once exported — see [Known limitations](limitations.md).
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

[← Documentation index](README.md) · [Project README](../README.md)
