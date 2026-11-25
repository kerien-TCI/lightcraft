# Desktop workflows on Android

The reference is the LightCraft desktop app in this repository, not Adobe Lightroom's feature list.
Android uses the same catalogue, decoder, develop, rendering, merge, slideshow and print engines. It replaces
the desktop window system with distinct native workflows instead of exposing its command socket.
All imports and derivatives below are local. System file permissions are requested only from the
native Android picker.

## Mobile editor and review

A new photo opens with the adjustment panel folded. The same native six tool families stay visible
in a bottom rail in portrait or a side rail on wide screens. Select the active family again
to fold it. Light → Curves opens the point-curve canvas directly; the tonal range controls remain
reachable separately. While a slider is dragged, an ephemeral draft from the same renderer updates
the photo, and release commits one batch with normal undo. No draft writes to the catalogue.

Swipe left/right at fit zoom, or use the accessible controls / nearby photos in the filmstrip.
Pinch, pan and press-and-hold comparison do not navigate; the current family and subsection stay
open across photos. The review panel offers flags, ratings, and optional advancement after a
successful save (off by default). Reject is a marker, not deletion. Assisted culling runs the
desktop thumbnail measures on up to 40 selected photos, or compares an explicit reference against
up to 39 recent imports. Scores are estimates and the scan itself changes no flags or files.

## Supported paths and tests to reproduce

| Desktop path | Android path | Boundary / acceptance check |
| --- | --- | --- |
| Import and review | Library → Import → files or folder/SD card, then review, rename and copy | SAF providers decide which directory roots are available. At most 150 files per folder-import batch, 2 GiB. No implicit move/deletion or background folder watch. Compare original hashes; try a duplicate and a recognized XMP. |
| Copy a desktop LightCraft catalogue | Android Settings → Desktop LightCraft libraries | Close LightCraft on desktop, then copy the whole consolidated library including catalog.snap, catalog.log and Originals. Opening the Android copy is a separate action. The source and existing phone catalogue are never replaced. Up to 4 GiB / 12,000 entries; external originals, custom colour profiles and Lightroom catalogues are not supported. |
| Cull and compare | Library → Select → Organize → Photo workflows → Compare, Survey and stacks | Compare synchronizes zoom/pan. Survey hides candidates only for the current view. Test existing flags and ratings, make a virtual copy, and compare new edits without copying or rewriting the original. |
| Albums and manual grouping | Library → Albums or Photo workflows → Compare, Survey and stacks | Create and reorder a stack; collapse under a rating filter and choose its visible representative. New smart album combines typed criteria and previews how many photos match. Album cycle checks use the common catalogue engine. |
| Batch synchronization and automatic grouping | Select at least two photos → Organize → Photo workflows → Batch synchronization | Pick a source and the target groups before writing. Local masks, crop, repairs and location are off by default; GPS never copies. Auto-stack uses capture-time tolerance and explicit preview confirmation. Verify undo and preservation of original bytes. |
| Develop, masks, geometry | Editor → Light / Color / Detail / Crop / Local | Engine global controls, touch crop/RGB curves, geometric and combined local masks, red/pet eye, removal, guided perspective, and Lens Blur. Subject/Sky/Background are the engine's offline estimators, not SAM. Test a named preset, a local mask and undo/redo when an original is cropped. |
| RAW noise | Editor → Detail → RAW Denoise | Built-in CPU model for eligible sensor RAW. Test preparation, cancellation and applied cached result. Nikon HE★ is not decoded by upstream. |
| Output | Quick JPEG, Editor → Export options, or Photo workflows → Batch export / originals | Select a writable Android folder for batch and Original+XMP. Names are allocated together without overwriting. Original+XMP currently refuses sources that embed location; render a privacy-safe copy instead. |
| Merge | Select two or more photos → Organize → Photo workflows → Photo Merge | HDR, panorama and HDR panorama create a new DNG. Input cap (12 files, estimated memory bounded) and reduced 1,024 px source mode are explicit. Align/deghost and panorama projection use the upstream algorithms. Test cancelled merge leaves no result and source hashes do not change. |
| Slideshow | Photo workflows → Slideshow | Play fullscreen with swipe, timed/manual controls, intro/outro, aspect, fit and fade. The shared compositor renders document PDFs and JPEG sequences, or silent AV1 MP4/WebM (where supported, up to 24 photos, two minutes and 720p). No external destination receives bytes until confirmation. Test cancellation, destination conflicts and 360 px landscape. |
| Print workspace | Photo workflows → Print layout / PDF | Native controls render a single-image layout or contact sheet with real PDF preview and deliberate save. No printer job is sent. Custom packages, ICC file selection and driver-specific settings remain to port. |

## Remaining desktop modules

1. Additional organization presets and sort modes; expanding migration beyond consolidated
   native LightCraft libraries; watched-folder semantics with explicit Android provider grants and
   OS background constraints.
2. Advanced slideshow overlay controls and imported template assets; watermark assets and deeper print
   layout editing with Android print handoff; XMP write/export policy, Lightroom catalogue package
   opening, GPX and offline GeoNames setup.
3. People tools and face regions, optional models with user-initiated offline import and device
   memory checks; GPU compute qualification across driver/device families. No destructive sync or
   model/network access by default.
4. Desktop integrations such as its menu bar, CLI and local control channel are infrastructure, not
   phone UI. Investigate a dedicated opt-in Android permission path instead of exposing an automation
   socket by default.

Not desktop capabilities at this engine version: Nikon HE/HE★ sensor processing, video editing,
Book, Super Resolution, generative fill, and a native HDR display pipeline. Standard-format preview of
embedded RAW JPEGs is not RAW development.
