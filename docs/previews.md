# Compose previews

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
- Wrap the content in your theme (`ScanlyTheme`) or it renders with default Material
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

That is not a preview limitation, it's a design signal. The fix is the same split described in [Architecture](architecture.md):
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

[← Documentation index](README.md) · [Project README](../README.md)
