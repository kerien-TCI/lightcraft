# Photo Lab Android · Android preview

Unofficial native Android derivative of [storytold/lightcraft](https://github.com/storytold/lightcraft).
The upstream base is `ea033fbe1c6dacdc7e1e99ee5a3e9fa0ce35d78c` (0.21.1). The GameActivity/JNI
starting point is tzhazuma's Apache-2.0/MIT-licensed [Android shell contribution #2802](https://github.com/storytold/lightcraft/pull/2802),
source commit `886a4b128`; the original author is credited in NOTICE and the commit. The port adds a compact phone
catalog and tool drawer without replacing the Rust image engine. See NOTICE and the upstream
licenses for code, bundled assets, and fonts.

## Preview scope

- Android 12 or newer (`minSdk 31`), ARM64 (`arm64-v8a`), no broad storage or network permission.
- In portrait, import and navigation live above the image, while the adjustments are in a
  scrollable panel underneath it. The normal preview supports the engine UI's pinch/zoom interaction.
- Use **Import…** to select files with Android's document picker. Selected files are copied to
  private app storage; the source is never moved, replaced or opened for writing by import.
- **Edit** opens the tools for the selected photo. **Save JPEG…** renders the edited photo in
  sRGB (up to 2,048 px on the long side at quality 90), then opens Android's save dialog.
  No image is uploaded. Clear app data or uninstalling removes the private library, not separately
  saved JPEGs or source files. Back up any work before doing so.
- RAW support depends on the original LightCraft engine: importing a NEF or showing an embedded
  preview does not prove the sensor data can be developed. The current Rust decoder, tested on the desktop using CC0 Nikon Zf samples from
  [raw.pixls.us](https://raw.pixls.us/), developed lossless NEF (sample 6885) but rejected HE★
  (sample 6886). Use lossless-compressed NEF if you want to develop the sensor data. Model downloads, desktop integrations and advanced export are not preview targets.

## Build an ARM64 debug APK

Install Git, Rust 1.95, JDK 21, Android SDK 35, NDK r28c and the Android platform tools.
Run from the checkout root. If multiple NDK versions are present, set `ANDROID_NDK_HOME`
to the r28c directory.

```sh
rustup target add aarch64-linux-android
apps/lightcraft-android/scripts/build-debug.sh
# For an x86_64 emulator, install the x86_64-linux-android Rust target and pass x86_64.
adb install -r apps/lightcraft-android/android/app/build/outputs/apk/debug/app-debug.apk
```

Use the debug APK only for private testing. It is signed with a local debug key,
not a store or production identity. The APK, build cache and signing material are not source files.

## Verify on a phone

1. In portrait, import a JPEG through the native document picker. Select its thumbnail and tap
   **Edit**. Adjust exposure, then close and reopen the editor.
2. Tap **Save JPEG…**, choose a destination, and open the exported file from Android Files.
   Compare the edited pixels with the original; the source copy must be unchanged.
3. Repeat with a Nikon Zf lossless-compressed NEF and with an actual HE★ NEF. Verify a
   full RAW decode separately from the embedded preview. Try cancelling both pickers and
   relaunching the app. Check pinch zoom and that the UI does not overlap Android navigation.
