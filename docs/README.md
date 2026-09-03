# Scanly documentation

Written for someone who has never built an Android app. Read it in this order, or
jump to whatever you need.

| Page | What's in it |
|---|---|
| [Getting started](getting-started.md) | What you need, enabling USB debugging, building and installing, first run. |
| [Android basics](android-basics.md) | The vocabulary — Activity, Compose, Gradle, SDK levels, coroutines, Logcat — each tied to where it appears here. |
| [Project structure](project-structure.md) | Every file and folder, annotated, plus a suggested reading order. |
| [How it works](how-it-works.md) | One page followed from camera to PDF, then a tour of the five screens. |
| [Key ideas](key-ideas.md) | Normalised coordinates, non-destructive editing, bitmap memory, threading, state flow — and where files live on the phone. |
| [Architecture](architecture.md) | Where views end and logic begins, and an honest answer to "is this MVVM?". |
| [Compose previews](previews.md) | The eleven @Preview composables, how to write one, and why some screens cannot be previewed. |
| [Troubleshooting](troubleshooting.md) | Symptoms and fixes, from adb not seeing your phone to phantom errors in the IDE. |
| [Exercises](exercises.md) | Ten changes to make, easiest first, each naming the file to open. |
| [Project structure → The tests](project-structure.md#the-tests) | Seventeen unit tests that run without a device, and why they are written to embarrass the code. |
| [Known limitations](limitations.md) | What this app does not do, and what is known to be rough. |

Deeper write-ups on the algorithms — the Hough transform, the ColorMatrix contrast
formula, the summed-area table, the homography — live in the author's notes rather
than here.

These pages assume you want to *work on this code*. If instead you want to **learn app
development**, and would rather start from why any of this is hard than from the file tree,
that is a different document and it is not in this repository — ask the author.

---

[← Project README](../README.md)
