# Android basics

You'll meet these words constantly. Here they are with the place you can see each one in
*this* project.

**Activity** — one screen-sized entry point into an app; the thing Android launches when you
tap the icon. Modern apps often have exactly one. Ours is `MainActivity.kt`.

**Composable / Jetpack Compose** — Compose is the modern way to build Android UI: you write
Kotlin functions annotated `@Composable` that *describe* what the screen should look like for
the current data, and the framework redraws when the data changes. The old way was XML layout
files; we don't use those. Every file under `ui/` is Compose.

**Recomposition** — when data a composable reads changes, Compose re-runs that function to
produce the new UI. This happens constantly, which is why you must not do slow work (like
decoding a bitmap) directly inside a composable.

**Gradle** — the build tool. It reads `build.gradle.kts` files, downloads libraries, compiles
Kotlin, and packages everything into an APK.

**AGP (Android Gradle Plugin)** — the Gradle plugin that knows how to build *Android*
specifically. Version 9.3.2 here. Since AGP 9 it compiles Kotlin itself, which is why you
won't find a `kotlin-android` plugin in our build file.

**APK** — the installable app file. `app/build/outputs/apk/debug/app-debug.apk`.
The debug APK is fat (~73 MB) because it carries debugging and Compose tooling and is never
shrunk. `./gradlew assembleRelease` produces a signed, R8-shrunk build of **under 4 MB** — most
of the difference is the thousands of unused vectors in `material-icons-extended` being
dropped. Keep rules for the JSON persistence layer live in `app/proguard-rules.pro`.

**SDK / API level** — every Android version has a number. Android 10 = API 29,
Android 15 = API 35, and so on. Three settings in `app/build.gradle.kts` use them:

| Setting | Ours | Meaning |
|---|---|---|
| `minSdk` | 29 | Oldest Android we support. Phones older than Android 10 can't install it. |
| `targetSdk` | 37 | "I have tested against this version's behaviour rules." Affects how Android treats you. |
| `compileSdk` | 37 | Which version's APIs you're allowed to *call* at compile time. |

We chose `minSdk 29` deliberately: from Android 10 onward, saving into the shared
`Documents` folder needs no storage permission at all, which removes a whole legacy code
path. See [Key ideas](key-ideas.md).

**AndroidManifest.xml** — the app's ID card. Declares the app name, icon, which permissions
it wants, and which Activity is the launcher. Ours is 28 lines and declares exactly one
permission: `CAMERA`.

**`res/`** — resources: icons, colours, strings, themes. Referenced from code as
`R.string.app_name` and from XML as `@string/app_name`. We keep almost nothing here because
Compose puts colours and dimensions in Kotlin instead.

**Permission** — some capabilities need the user's explicit consent at runtime. We request
`CAMERA` in `CameraScreen.kt` with `rememberLauncherForActivityResult`.

**Coroutine / suspend / Dispatchers** — Kotlin's way of doing background work.
`Dispatchers.Default` = a pool of threads for CPU work (our image processing),
`Dispatchers.IO` = for disk/network waiting, and the **main thread** is the only one allowed
to touch the UI. Blocking the main thread for more than ~16 ms drops a frame; for a second or
more the system shows "app not responding".

**Logcat** — the device's log stream, where crashes and stack traces appear. In Android
Studio it's the "Logcat" tab at the bottom. From the terminal:

```bash
~/Library/Android/sdk/platform-tools/adb logcat --pid=$(~/Library/Android/sdk/platform-tools/adb shell pidof com.minimal.pdfcreate)
```

**Bitmap** — an uncompressed image in memory. Cost is `width × height × 4` bytes. A 12-megapixel
photo is ~48 MB *decoded*, which is why we always decode downscaled — see
[Key ideas](key-ideas.md).

---

[← Documentation index](README.md) · [Project README](../README.md)
