# Architecture

### The three layers

```
        ui/            ← VIEWS. Compose only. Draws state, emits user events. No file IO,
         │                no pixel maths, no PDF knowledge.
         │  calls
         ▼
   data/ + imaging/ + pdf/     ← LOGIC. Plain Kotlin objects and classes. No Compose imports,
                                 no Context except where Android forces it (MediaStore).
```

A useful test: **open any file under `imaging/` or `pdf/` and search for the word
`Composable`. There are none.** Those files could be lifted into a command-line tool
unchanged. Conversely, no file under `ui/` decodes a bitmap, writes a file or knows what a
summed-area table is — it calls something that does.

| If you want to change… | Open |
|---|---|
| How a screen looks or behaves | `ui/<screen>/…Screen.kt` |
| A control panel's buttons and sliders | `ui/editor/EditorPanel.kt` |
| What a document *is* (fields, defaults) | `data/Model.kt` |
| How documents are saved / loaded / deleted | `data/DocumentRepository.kt` |
| Where PDFs land on the phone | `data/PdfStore.kt` |
| Anything about pixels | `imaging/` |
| The PDF file itself | `pdf/PdfExporter.kt` |
| Which screen leads to which | `MainActivity.kt` |

### Which pattern is this? (MVC, MVVM, MVI…)

Short answer: **the app follows the *data flow* of MVVM but has no ViewModel class.** It is
closer to "repository + state hoisting", which is a legitimate small-app Compose pattern —
but it is not textbook MVVM, and you should know exactly where it differs.

The vocabulary, briefly:

- **MVC** — Model / View / Controller. The old desktop and early-Android pattern: the View
  reads from the Model, a Controller mutates it. Prone to tangled two-way updates.
- **MVVM** — Model / View / **ViewModel**. The View observes an *observable state object*
  exposed by a ViewModel and sends events to it; the ViewModel owns the state and talks to
  repositories. This is Google's recommended Android architecture.
- **MVI** — one immutable state object plus a stream of intents. MVVM with stricter rules.
- **MVCC** — multi-version concurrency control. Unrelated: that's a *database* technique for
  letting readers and writers work concurrently (Postgres, InnoDB). Nothing to do with UI.

What this app actually does:

```
DocumentRepository  ──  StateFlow<List<ScanDocument>>  ──▶  HomeScreen renders it
        ▲                                                          │
        └────────────  repo.save(doc) / repo.delete(id)  ◀──────────┘
```

That *is* the unidirectional loop MVVM asks for: one source of truth, the UI renders it, the
UI never mutates state in place, and writes go back through the repository which re-emits.
`DocumentRepository` plays the "Model" role, `StateFlow` is the observable, and the screens
are the Views.

What is missing versus real MVVM:

1. **No `ViewModel` class.** Screen state (`filter`, `strokes`, `crop`, which tab is open)
   lives in `remember { }` inside the composable. `remember` survives recomposition but **not**
   configuration changes or process death — a `ViewModel` survives both.
2. **Editor state is UI state, not model state, until it is saved.** We paper over this by
   writing to disk on a 400 ms debounce, so in practice very little can be lost, but that is a
   workaround, not the pattern.
3. **Screens call the repository directly**, so there is no seam where you could unit-test the
   screen's behaviour without Compose.

Why it was built this way: fewer moving parts to read while you're learning, and no DI
framework. `AppContainer` is a hand-rolled *service locator* — one object, one repository,
created once against the application context. Hilt would replace it in a real app.

**The upgrade path**, if you want to do it as an exercise (this is exercise 8): create
`PageEditorViewModel(repo, docId, pageId) : ViewModel`, move the `var … by remember` fields
into it as a single `MutableStateFlow<PageEditorState>`, expose `onFilterChange`,
`onStrokeFinished`, `onUndo` … and have the composable do
`val state by viewModel.state.collectAsStateWithLifecycle()`. The screen becomes a pure
function of state, the way `EditorPanel` already is. Start with the editor — it is the screen
with the most state and therefore the most to gain.

---

[← Documentation index](README.md) · [Project README](../README.md)
