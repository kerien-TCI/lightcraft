# Photo Lab Android · Native Android preview

Unofficial native Android derivative of [storytold/lightcraft](https://github.com/storytold/lightcraft);
upstream base `ea033fbe1c6dacdc7e1e99ee5a3e9fa0ce35d78c` (0.21.1). The port retains the Rust
image engine and uses its own native Android Activity; the desktop UI is not compiled into the
APK. The first prototype reused tzhazuma's Apache-2.0/MIT-licensed Android contribution
[#2802](https://github.com/storytold/lightcraft/pull/2802). See NOTICE and the upstream licenses for
code, bundled assets, and fonts.

## What works

- Android 12 and later. Responsive photo grid with search and a direct import button. Tap a
  thumbnail to edit. The image is above the controls on portrait phones, with a side panel on roomy
  or short landscape displays. English and French are available in Settings.
- The photo editor groups the implemented controls into Light, Color and Effects. Scroll within
  the active group. Pinch to zoom, drag to pan when zoomed, and double-tap to fit. Press and hold the
  photo or tap the labelled comparison control to show the before-edit view, which keeps the same
  crop to align the two images. Undo and redo step through the selected photo's edits. Crop includes
  common fixed aspect ratios and 90° rotation.
- Import and save use the Android system document picker. Selected files are copied to private app
  storage. The original is never written, moved or replaced. Exports are separate sRGB JPEGs; edge
  size (1,280, 2,048 or 4,096 px) and quality are configurable. The app does not upload your images or
  request broad storage permissions. Clearing the app's data or uninstalling deletes the private
  library, not the separately saved JPEGs or the original files.
- Lens, AI and other advanced desktop tools are not exposed. RAW development depends on the
  upstream decoder. In testing on the host, the Rust decoder developed a Nikon Zf lossless NEF
  (CC0 raw.pixls.us sample 6885) but rejected the HE★ sample (6886). Merely importing a RAW or
  seeing an embedded JPEG is not proof that its sensor data can be developed.

The mobile workflow was informed by Adobe's documentation for the
[Lightroom mobile workspace](https://helpx.adobe.com/lightroom/mobile/get-started/workspace-overview.html)
and [touch gestures](https://helpx.adobe.com/lightroom/mobile/get-started/gesture-controls-in-lightroom-for-mobile.html).
This app is not affiliated with Adobe and does not use Adobe's assets.

## Build an ARM64 debug APK

Install Git, Rust 1.95, JDK 21, Android SDK 35, NDK r28c, and Android platform tools. Run from the
checkout root. Set `ANDROID_NDK_HOME` to the r28c directory if needed.

```sh
rustup target add aarch64-linux-android
apps/lightcraft-android/scripts/build-debug.sh
adb install -r apps/lightcraft-android/android/app/build/outputs/apk/debug/app-debug.apk
# For an x86_64 emulator, install the x86_64-linux-android Rust target and pass x86_64.
cargo test --locked -p lightcraft-android --lib
```

The debug APK is for testing, not production. An in-place upgrade needs the same package ID and
signing key as the installed build. Keep signing material out of the repository.

## End-to-end smoke test

1. Import a JPG through Android Files; open the thumbnail and adjust Exposure.
2. Hold the image and release, then try the comparison button. Undo, redo, and set a crop ratio.
3. Export the JPEG, open it from Android Files, and confirm the crop and edits. Verify that the
   original has not changed. Cancel either picker and check that the app stays usable.
4. Repeat from the grid on a narrow phone, in short landscape, and on a tablet. Check Android Back,
   larger text, and both languages. If you repeat with RAW, verify a sensor decode separately from
   embedded JPEG preview support.
