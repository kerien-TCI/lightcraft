# Photo Lab Android · Native Android preview

Unofficial native Android derivative of [storytold/lightcraft](https://github.com/storytold/lightcraft);
upstream base `ea033fbe1c6dacdc7e1e99ee5a3e9fa0ce35d78c` (0.21.1). The port retains the Rust
image engine and uses its own native Android Activity; the desktop UI is not compiled into the
APK. The first prototype reused tzhazuma's Apache-2.0/MIT-licensed Android contribution
[#2802](https://github.com/storytold/lightcraft/pull/2802). See NOTICE and the upstream licenses for
code, bundled assets, and fonts.

## Android workspace

Android 12 and later. Photos open without an adjustment panel. The native mode rail is below
the image in portrait and at its side in short landscape and on tablets. **Edit** opens Light,
Color, Curves, Effects and the other develop sections; **Presets**, **Crop**, **Masks**, **Heal**
and **Review** have their own shortcuts. Pick a parameter to show its slider and current value.
Reset next to the value affects only that parameter. Drag the down chevron or tap it to close
the panel. The photo grows while sliding in portrait without moving the slider's touch target.

The gallery uses a compact photo grid. In the editor, swipe at fit zoom or use the arrow buttons
to change photo while keeping the selected tool. Tap the photo counter to expand the filmstrip.
Import, export, sampling and confirmations use native Android screens. English and French strings
follow the app or device language.

| Desktop workflow | Android path |
| --- | --- |
| Develop adjustments | Auto, white-balance presets and a point sampler, light, RGB point and parametric curves, color mixer and black-and-white mixer, color grading, point colors, texture/clarity/dehaze, vignette, grain, detail and calibration. Numeric controls use the same engine ranges and defaults. |
| Local edits and framing | Touch crop and straighten, radial/linear/brush/luminance masks, offline Subject/Sky estimates, combined components, red/pet-eye correction and removal/healing, and the same render engine for previews and exports. |
| Optics and geometry | Manual optical correction, chromatic aberration removal, profile corrections when usable lens data is built into the photo, manual, automatic and guided perspective. Lens Blur uses the desktop engine’s subject/shape-based focus with brush refinements. |
| Detail and HDR | Classical luminance and color noise reduction; on-device RAW Denoise using the built-in CPU model for compatible sensor RAWs. HDR controls and compatible HDR export formats; Android displays the SDR preview. |
| Looks and versions | Built-in profiles and presets, asset import via Files (.xmp, .lrtemplate, .lcpreset, .lceffect, .cube, .dcp), copy/paste selected families of adjustments, history and named versions. |
| Catalogue | Multi-select ratings, flags, color labels, favorites, keywords, IPTC/GPS fields, albums and folders, browsing existing smart albums, and sorting/filtering. Compare, Survey, stacks and virtual copies are in Photo workflows. |
| File output | Quick JPEG or advanced JPEG, PNG, TIFF, PSD, WebP, AVIF, JPEG XL and linear DNG. Resize, quality, bit depth, color space and metadata options depend on format. Exports create a separate file using the system picker; original files are not overwritten. Providers that cannot prove a new empty document are refused. Batch output and Original+XMP use an explicitly selected folder. |
| Import, merge and layout | Import files or a folder/SD card through Files, photo merge to a new HDR/panorama DNG, and print-format single-image layouts or contact sheets saved as PDF. |

See [the Android parity matrix](PARITY.md) for tested workflows, format limits and the remaining
gaps compared with LightCraft desktop. Video editing and a Book workspace are not available in
upstream LightCraft either.
The app has no network or broad storage permission. Imports are app-owned copies. Uninstalling or
clearing its data removes the app's private library, not separately saved exports or originals.

RAW development depends on the upstream decoder. In testing on the host, the Rust decoder
developed a Nikon Zf lossless NEF (CC0 raw.pixls.us sample 6885) but rejected HE★ (6886).
Merely importing a RAW or seeing its embedded JPEG is not proof that the sensor data can be developed.

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
2. Open **Edit → Curves**; edit a point curve, use **Masks** to add a local mask, and reopen the
   photo to verify the changes. Exercise before/after and per-photo undo/redo (redo is in More).
3. Use **Export options** to save a PNG, then test quick JPEG and the Android save dialog's Cancel
   action. Decode the new file and verify the original's bytes.
4. In the grid, select images, assign a rating and check the rating filter. On compatible DNG or
   lossless RAW, open **Edit → Detail → More for this tool → RAW Denoise**, prepare the pixels, and apply the result.
5. Repeat on a narrow phone, in short landscape, and on a tablet with large text. Check Android
   Back and both languages. If you repeat with RAW, verify a sensor decode separately from
   embedded JPEG preview support.
