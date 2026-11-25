# Desktop workflows on Android

The reference is the LightCraft desktop app in this repository, not Adobe Lightroom's feature list.
Android uses the same catalogue, decoder, develop, rendering, merge and print engines. It replaces
the desktop window system with distinct native workflows instead of exposing its command socket.
All imports and derivatives below are local. System file permissions are requested only from the
native Android picker.

## Supported paths and tests to reproduce

| Desktop path | Android path | Boundary / acceptance check |
| --- | --- | --- |
| Import and review | Library → Import → files or folder/SD card, then review, rename and copy | SAF providers decide which directory roots are available. At most 150 files per folder-import batch, 2 GiB. No implicit move/deletion or background folder watch. Compare original hashes; try a duplicate and a recognized XMP. |
| Cull and compare | Library → Select → Organize → Photo workflows → Compare, Survey and stacks | Compare synchronizes zoom/pan. Survey hides candidates only for the current view. Test existing flags and ratings, make a virtual copy, and compare new edits without copying or rewriting the original. |
| Manual grouping | Photo workflows → Compare, Survey and stacks | Create and reorder a stack; collapse under a rating filter and choose its visible representative. Smart-rule authoring and auto-stack remain to port. |
| Develop, masks, geometry | Editor → Tools | Engine global controls, touch crop/RGB curves, geometric and combined local masks, red/pet eye, removal, guided perspective, and Lens Blur. Subject/Sky/Background are the engine's offline estimators, not SAM. Test a named preset, a local mask and undo/redo when an original is cropped. |
| RAW noise | Editor → Tools → Detail → RAW Denoise | Built-in CPU model for eligible sensor RAW. Test preparation, cancellation and applied cached result. Nikon HE★ is not decoded by upstream. |
| Output | Quick JPEG, Editor → Export options, or Photo workflows → Batch export / originals | Select a writable Android folder for batch and Original+XMP. Names are allocated together without overwriting. Original+XMP currently refuses sources that embed location; render a privacy-safe copy instead. |
| Merge | Select two or more photos → Organize → Photo workflows → Photo Merge | HDR, panorama and HDR panorama create a new DNG. Input cap (12 files, estimated memory bounded) and reduced 1,024 px source mode are explicit. Align/deghost and panorama projection use the upstream algorithms. Test cancelled merge leaves no result and source hashes do not change. |
| Print workspace | Photo workflows → Print layout / PDF | Native controls render a single-image layout or contact sheet with real PDF preview and deliberate save. No printer job is sent. Custom packages, ICC file selection and driver-specific settings remain to port. |

## Remaining desktop modules

1. Complete organization, batch sync/metadata, smart-album editor, group auto-stack, template and
   watermark assets; external library migration; watched-folder semantics implemented with explicit
   Android provider grants and OS background constraints.
2. Native slideshow workspace and exports; deeper layout editing and Android print handoff; XMP
   write/export policy and Lightroom catalogue package opening; GPX and offline GeoNames setup.
3. People tools and face regions, optional models with user-initiated offline import and device
   memory checks; GPU compute qualification across driver/device families. No destructive sync or
   model/network access by default.
4. Desktop integrations such as its menu bar, CLI and local control channel are infrastructure, not
   phone UI. Investigate a dedicated opt-in Android permission path instead of exposing an automation
   socket by default.

Not desktop capabilities at this engine version: Nikon HE/HE★ sensor processing, video editing,
Book, Super Resolution, generative fill, and a native HDR display pipeline. Standard-format preview of
embedded RAW JPEGs is not RAW development.
