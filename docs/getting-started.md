# Getting started

### What you need

| Thing | Why | Notes |
|---|---|---|
| **Android Studio** | The IDE. Also installs the Android SDK and `adb`. | Already installed on this machine. |
| **A JDK** | Gradle (the build tool) runs on Java. | Already installed. Gradle downloads the exact JDK it needs by itself. |
| **An Android phone, Android 10 or newer** | The app uses the camera, which an emulator fakes badly. | Or an emulator, see below. |
| **A USB cable** | To install the app on the phone. | Any data cable (some charge-only cables won't work). |

### Turn on developer mode on your phone (one time)

1. **Settings → About phone**
2. Tap **Build number** seven times. It will say "You are now a developer".
3. Go back to **Settings → System → Developer options**
4. Turn on **USB debugging**
5. Plug the phone into the Mac. A dialog appears on the phone: **Allow USB debugging** → tick
   "Always allow" → **Allow**.

Check the Mac can see it:

```bash
~/Library/Android/sdk/platform-tools/adb devices
```

You should see a line with your device ID and the word `device`. If it says `unauthorized`,
look at the phone screen — you missed the dialog.

### Build and install

From the project folder (`android_dev/testApplication`):

```bash
./gradlew installDebug      # builds the app and installs it on the connected phone
```

Or just build without installing:

```bash
./gradlew assembleDebug     # produces app/build/outputs/apk/debug/app-debug.apk
```

The very first build downloads Gradle, the Android plugin and all the libraries — expect a
few minutes and a lot of console output. Later builds take a few seconds.

> `./gradlew` is a small script committed into the repo ("the Gradle wrapper"). It downloads
> the exact Gradle version this project expects, so you never install Gradle yourself.

**Or, from Android Studio:** File → Open → select the `testApplication` folder → wait for
"Gradle sync" to finish (progress bar at the bottom) → pick your device in the toolbar
dropdown → press the green ▶ Run button. That does exactly the same thing.

### About emulators

An emulator is a simulated phone running on your Mac. This machine has **no emulator image
installed yet**, so right now you need a real device. To create one: Android Studio →
**Device Manager** (phone icon in the right sidebar) → **Create Device** → pick e.g. Pixel 7 →
download a system image. Note the emulator's "camera" is a fake rotating scene, so edge
detection won't do anything sensible there — everything else (home, viewer, editor, export)
works fine.

### First run

1. Tap the **+** button (bottom right).
2. Android asks for camera permission → **Allow**.
3. Point at a page on a contrasting surface. A green quadrilateral should snap to the page
   edges.
4. Tap the big white shutter. The thumbnail bottom-left shows what you captured, with a
   count badge.
5. Capture more pages, then tap the green **✓** bottom-right.
6. On the Review screen, tap a page to edit it, or tap **Save PDF**.
7. You're back home, with the PDF listed. Tap it to read it in the app.

---

[← Documentation index](README.md) · [Project README](../README.md)
