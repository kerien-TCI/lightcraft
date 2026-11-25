package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** Native single-image render settings and safe writing to newly created Android documents. */
final class MobileExportTools {
    private static final Pattern TICKET = Pattern.compile(
        "Export-[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\\.(jpg|png|tif|psd|webp|avif|jxl|dng)");

    enum Format {
        JPEG("jpeg", "jpg", "image/jpeg", "JPEG"),
        PNG("png", "png", "image/png", "PNG"),
        TIFF("tiff", "tif", "image/tiff", "TIFF"),
        PSD("psd", "psd", "image/vnd.adobe.photoshop", "PSD"),
        WEBP("webp", "webp", "image/webp", "WebP"),
        AVIF("avif", "avif", "image/avif", "AVIF"),
        JXL("jxl", "jxl", "image/jxl", "JPEG XL"),
        DNG("renderedDng", "dng", "image/x-adobe-dng", "DNG");

        final String key, extension, mime, label;
        Format(String key, String extension, String mime, String label) {
            this.key = key; this.extension = extension; this.mime = mime; this.label = label;
        }
        boolean hasQuality() { return this == JPEG || this == AVIF || this == JXL; }
        boolean supportsHdr() { return this == JPEG || this == PNG || this == TIFF || this == AVIF || this == JXL; }
        int[] depths() {
            return switch (this) {
                case TIFF -> new int[]{16, 8, 32};
                case PNG, PSD, JXL -> new int[]{8, 16};
                case AVIF -> new int[]{8, 10};
                case JPEG, WEBP -> new int[]{8};
                case DNG -> new int[]{0};
            };
        }
    }

    record Options(Format format, String sizeMode, int edge, int fitHeight, int quality, int bitDepth,
                   String colorSpace, String metadata, int ppi, int limitKb, String sharpen,
                   String watermark, boolean hdr, boolean lossless, int budgetMiB) {
        static Options quick(int edge, int quality) {
            return new Options(Format.JPEG, "long", edge, 2048, quality, 8, "srgb", "none",
                240, 0, "none", "", false, false, 768);
        }

        String ticket() { return "Export-" + UUID.randomUUID() + "." + format.extension; }

        JSONObject args(long photoId, String ticket) {
            JSONObject p = new JSONObject();
            try {
                p.put("id", photoId).put("file", ticket).put("format", format.key).put("sizeMode", sizeMode)
                    .put("edge", edge).put("fitHeight", fitHeight).put("quality", quality).put("bitDepth", bitDepth)
                    .put("colorSpace", colorSpace).put("metadata", metadata).put("ppi", ppi).put("limitKb", limitKb)
                    .put("sharpen", sharpen).put("watermark", watermark).put("hdr", hdr).put("lossless", lossless)
                    .put("budgetMiB", budgetMiB);
            } catch (JSONException e) { throw new IllegalStateException(e); }
            return p;
        }

        Intent createDocument(String photoName) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(format.mime).addCategory(Intent.CATEGORY_OPENABLE);
            String stem = Documents.localName(photoName).replaceFirst("\\.[^.]+$", "").trim();
            intent.putExtra(Intent.EXTRA_TITLE, (stem.isEmpty() ? "Photo" : stem) + "-edit." + format.extension);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return intent;
        }
    }

    private MobileExportTools() {}

    static boolean ticketIsValid(String ticket) { return ticket != null && TICKET.matcher(ticket).matches(); }

    /**
     * ACTION_CREATE_DOCUMENT itself doesn't overwrite anything. Also refuse a result referring
     * to a nonempty file (including a provider returning an existing source), or a provider that
     * can't demonstrate a fresh empty document. Opening in "rw" is non-truncating; do not
     * fall back to "w", which a provider may interpret as truncation.
     */
    static void writeNew(ContentResolver resolver, Uri destination, File staged) throws IOException {
        if (destination == null || !ContentResolver.SCHEME_CONTENT.equals(destination.getScheme())
            || !ticketIsValid(staged.getName()) || !Files.isRegularFile(staged.toPath(), LinkOption.NOFOLLOW_LINKS)
            || Files.size(staged.toPath()) > Documents.MAX_FILE_BYTES) throw new IOException();
        try (InputStream input = Files.newInputStream(staged.toPath())) {
            ParcelFileDescriptor target = resolver.openFileDescriptor(destination, "rw");
            if (target == null) throw new IOException();
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(target)) {
                if (target.getStatSize() != 0) throw new IOException();
                Documents.transfer(input, output, Documents.MAX_FILE_BYTES);
            }
        }
    }

    static String copySaved(Context context) { return words(context, "Export saved.", "Export enregistré."); }
    static String title(Context context) { return words(context, "Export options", "Options d’export"); }

    static String error(Context context, String code) {
        return switch (code) {
            case "export_size" -> words(context,
                "This export is too large for this device. Choose smaller dimensions or a lower bit depth.",
                "Cet export est trop volumineux pour cet appareil. Réduisez les dimensions ou la profondeur de couleur.");
            case "export_limit" -> words(context, "The JPEG cannot fit under that file-size limit. Increase the limit or resize the photo.",
                "Le JPEG ne tient pas dans cette limite. Augmentez la limite ou réduisez les dimensions.");
            case "export_options" -> words(context, "These export settings are not compatible.", "Ces réglages d’export ne sont pas compatibles.");
            case "export_destination" -> words(context,
                "Choose a new empty file in a folder that supports writing, such as Downloads. No existing file was overwritten.",
                "Choisissez un nouveau fichier vide dans un dossier compatible, par exemple Téléchargements. Aucun fichier existant n’a été écrasé.");
            default -> null;
        };
    }

    static void show(Activity host, int defaultEdge, int defaultQuality, Consumer<Options> submit) {
        Form form = new Form(host, defaultEdge, defaultQuality);
        AlertDialog dialog = new AlertDialog.Builder(host).setTitle(title(host))
            .setView(form.scroll).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(form.t("Continue to Files", "Choisir le fichier"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Options options = form.read();
            if (options != null) {
                dialog.dismiss();
                submit.accept(options);
            }
        }));
        dialog.show();
    }

    private static String words(Context context, String en, String fr) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return "fr".equals(locale.getLanguage()) ? fr : en;
    }

    private static int dp(Context context, int amount) {
        return Math.round(amount * context.getResources().getDisplayMetrics().density);
    }

    private static int memoryBudget(Activity host) {
        ActivityManager manager = host.getSystemService(ActivityManager.class);
        if (manager == null) return 768;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        long mib = Math.min(info.totalMem / 3, info.availMem / 2) >> 20;
        if (mib == 0) return 768;
        return (int) Math.max(128, Math.min(2048, mib));
    }

    private static final class Form {
        private static final String[] SIZE_KEYS = {"long", "full", "short", "width", "height", "fit", "percent", "megapixels"};
        private static final String[] COLOR_KEYS = {"srgb", "displayP3", "adobeRgb", "proPhoto", "rec2020"};
        private static final String[] META_KEYS = {"none", "copyright", "all", "allExceptCamera"};
        private static final String[] SHARPEN_KEYS = {"none", "screen", "matte", "glossy"};

        final Activity host;
        final ScrollView scroll;
        final LinearLayout column;
        final Spinner format, size, depth, color, metadata, sharpen;
        final TextView edgeLabel, heightLabel, depthLabel, colorLabel, limitLabel, qualityLabel, formatHint;
        final EditText edge, height, limit, ppi, watermark;
        final CheckBox hdr, lossless;
        final SeekBar quality;
        final int defaultEdge, defaultQuality;
        int[] activeDepths = {8};
        int currentSize;

        Form(Activity host, int defaultEdge, int defaultQuality) {
            this.host = host;
            this.defaultEdge = defaultEdge;
            this.defaultQuality = defaultQuality;
            scroll = new ScrollView(host);
            scroll.setFillViewport(false);
            column = new LinearLayout(host);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(dp(host, 22), 0, dp(host, 22), dp(host, 12));
            scroll.addView(column, new ScrollView.LayoutParams(-1, -2));

            label(t("A new copy will be saved. The original stays untouched.", "Une nouvelle copie sera créée. L’original reste intact."));
            TextView formatLabel = label(t("Format", "Format"));
            format = dropdown(formatLabel, new String[]{"JPEG", "PNG", "TIFF", t("Flat PSD", "PSD aplati"), t("WebP (lossless)", "WebP (sans perte)"),
                "AVIF", "JPEG XL", t("DNG (rendered)", "DNG (rendu)")});
            formatHint = label("");
            TextView sizeLabel = label(t("Image size", "Dimensions"));
            size = dropdown(sizeLabel, new String[]{t("Maximum long edge", "Côté long maximal"), t("Full resolution", "Résolution d’origine"),
                t("Maximum short edge", "Côté court maximal"), t("Maximum width", "Largeur maximale"), t("Maximum height", "Hauteur maximale"),
                t("Fit inside width × height", "Tenir dans largeur × hauteur"), t("Percentage of original", "Pourcentage de l’original"), t("Megapixels", "Mégapixels")});
            edgeLabel = label(t("Long edge (px)", "Côté long (px)"));
            edge = number(edgeLabel, Integer.toString(defaultEdge));
            heightLabel = label(t("Height (px)", "Hauteur (px)"));
            height = number(heightLabel, Integer.toString(defaultEdge));
            qualityLabel = label("");
            quality = new SeekBar(host);
            qualityLabel.setLabelFor(View.generateViewId());
            quality.setId(qualityLabel.getLabelFor());
            quality.setMax(99);
            quality.setProgress(defaultQuality - 1);
            quality.setMinimumHeight(dp(host, 48));
            column.addView(quality, new LinearLayout.LayoutParams(-1, -2));
            quality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar b) {}
                @Override public void onStopTrackingTouch(SeekBar b) {}
                @Override public void onProgressChanged(SeekBar b, int progress, boolean user) { qualityText(progress + 1); }
            });
            qualityText(defaultQuality);
            lossless = check(t("Lossless JPEG XL", "JPEG XL sans perte"));
            depthLabel = label(t("Bit depth", "Profondeur de couleur"));
            depth = dropdown(depthLabel, new String[]{"8"});
            colorLabel = label(t("Color space", "Espace colorimétrique"));
            color = dropdown(colorLabel, new String[]{"sRGB", "Display P3", t("Adobe RGB compatible", "Compatible Adobe RGB"), "ProPhoto RGB", "Rec. 2020"});
            hdr = check(t("HDR, if this photo was edited in HDR", "HDR, si la photo est retouchée en HDR"));

            TextView metadataLabel = label(t("Metadata (location is always removed)", "Métadonnées (localisation toujours retirée)"));
            metadata = dropdown(metadataLabel, new String[]{t("None", "Aucune"), t("Creator and copyright only", "Auteur et droits uniquement"),
                t("All except location", "Toutes sauf la localisation"), t("Without camera or location", "Sans appareil ni localisation")});
            TextView resolutionLabel = label(t("Print resolution (ppi)", "Résolution d’impression (ppp)"));
            ppi = number(resolutionLabel, "240");
            TextView sharpenLabel = label(t("Output sharpening", "Netteté à l’export"));
            sharpen = dropdown(sharpenLabel, new String[]{t("None", "Aucune"), t("Screen", "Écran"), t("Matte print", "Papier mat"), t("Glossy print", "Papier brillant")});
            limitLabel = label(t("Maximum JPEG file size (KB, optional)", "Taille maximale du JPEG (Ko, facultative)"));
            limit = number(limitLabel, "");
            TextView watermarkLabel = label(t("Text watermark (optional)", "Filigrane texte (facultatif)"));
            watermark = new EditText(host);
            associate(watermarkLabel, watermark);
            watermark.setSingleLine(true);
            watermark.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
            column.addView(watermark, new LinearLayout.LayoutParams(-1, -2));

            whenChanged(format, this::showFormat);
            whenChanged(size, this::showSize);
            whenChanged(depth, this::showHdr);
            lossless.setOnCheckedChangeListener((button, checked) -> showQuality());
            showFormat();
            showSize();
        }

        private TextView label(String message) {
            TextView v = new TextView(host);
            v.setText(message);
            v.setPadding(0, dp(host, 8), 0, 0);
            column.addView(v, new LinearLayout.LayoutParams(-1, -2));
            return v;
        }

        private void associate(TextView label, View field) {
            field.setId(View.generateViewId());
            label.setLabelFor(field.getId());
        }

        private Spinner dropdown(TextView label, String[] values) {
            Spinner v = new Spinner(host, Spinner.MODE_DIALOG);
            associate(label, v);
            v.setPrompt(label.getText());
            fill(v, values);
            v.setMinimumHeight(dp(host, 48));
            column.addView(v, new LinearLayout.LayoutParams(-1, -2));
            return v;
        }

        private void fill(Spinner view, String[] values) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(host, android.R.layout.simple_spinner_item, values);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            view.setAdapter(adapter);
        }

        private EditText number(TextView label, String text) {
            EditText view = new EditText(host);
            associate(label, view);
            view.setInputType(InputType.TYPE_CLASS_NUMBER);
            view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
            view.setSingleLine(true);
            view.setText(text);
            column.addView(view, new LinearLayout.LayoutParams(-1, -2));
            return view;
        }

        private CheckBox check(String label) {
            CheckBox v = new CheckBox(host);
            v.setText(label);
            v.setMinimumHeight(dp(host, 48));
            column.addView(v, new LinearLayout.LayoutParams(-1, -2));
            return v;
        }

        private void showFormat() {
            Format selected = selectedFormat();
            activeDepths = selected.depths();
            String[] names = new String[activeDepths.length];
            for (int i = 0; i < names.length; i++) names[i] = Integer.toString(activeDepths[i]);
            fill(depth, names);
            depth.setSelection(0);
            visibility(activeDepths.length > 1, depth, depthLabel);
            visibility(selected != Format.AVIF && selected != Format.DNG, color, colorLabel);
            visibility(selected == Format.JPEG, limitLabel, limit);
            visibility(selected == Format.JXL, lossless);
            String explanation = switch (selected) {
                case DNG -> t("Linear DNG with edits baked in. This is not a copy of the camera RAW.",
                    "DNG linéaire avec les retouches intégrées. Ce n’est pas une copie du RAW de l’appareil.");
                case PSD -> t("Rendered photo on one layer.", "Photo rendue sur un seul plan.");
                default -> "";
            };
            formatHint.setText(explanation);
            visibility(!explanation.isEmpty(), formatHint);
            showHdr();
            showQuality();
        }

        private void showHdr() {
            Format selected = selectedFormat();
            boolean supported = selected.supportsHdr() && (selected != Format.TIFF || selectedDepth() == 32);
            if (!supported) hdr.setChecked(false);
            visibility(supported, hdr);
        }

        private void showQuality() {
            Format selected = selectedFormat();
            visibility(selected.hasQuality() && !(selected == Format.JXL && lossless.isChecked()), quality, qualityLabel);
        }

        private void qualityText(int value) {
            String title = t("Quality", "Qualité");
            qualityLabel.setText(title + " " + value + " %");
            quality.setContentDescription(title);
        }

        private void showSize() {
            int mode = size.getSelectedItemPosition();
            if (mode < 0) return;
            if (mode != currentSize && (mode >= 6 || currentSize >= 6)) {
                edge.setText(mode == 6 ? "100" : mode == 7 ? "12" : Integer.toString(defaultEdge));
            }
            currentSize = mode;
            edgeLabel.setText(switch (mode) {
                case 2 -> t("Short edge (px)", "Côté court (px)");
                case 3, 5 -> t("Width (px)", "Largeur (px)");
                case 4 -> t("Height (px)", "Hauteur (px)");
                case 6 -> t("Percentage (%)", "Pourcentage (%)");
                case 7 -> t("Megapixels (MP)", "Mégapixels (MP)");
                default -> t("Long edge (px)", "Côté long (px)");
            });
            visibility(mode != 1, edge, edgeLabel);
            visibility(mode == 5, height, heightLabel);
        }

        private Options read() {
            int mode = size.getSelectedItemPosition();
            int value = mode == 1 ? defaultEdge : ranged(edge, mode >= 6 ? 1 : 128, mode == 6 ? 100 : mode == 7 ? 64 : 12000, -1);
            int second = mode == 5 ? ranged(height, 128, 12000, -1) : 2048;
            int resolution = ranged(ppi, 1, 1200, -1);
            Format selected = selectedFormat();
            int limitValue = selected == Format.JPEG ? ranged(limit, 1, 50000, 0) : 0;
            if (value < 0 || second < 0 || resolution < 0 || limitValue < 0) return null;
            String colorKey = (selected == Format.AVIF || selected == Format.DNG) ? "srgb" : COLOR_KEYS[color.getSelectedItemPosition()];
            return new Options(selected, SIZE_KEYS[mode], value, second, quality.getProgress() + 1, selectedDepth(),
                colorKey, META_KEYS[metadata.getSelectedItemPosition()], resolution, limitValue, SHARPEN_KEYS[sharpen.getSelectedItemPosition()],
                watermark.getText().toString().trim(), hdr.isChecked(), selected == Format.JXL && lossless.isChecked(), memoryBudget(host));
        }

        private int ranged(EditText field, int min, int max, int blank) {
            String value = field.getText().toString().trim();
            if (value.isEmpty() && blank >= 0) return blank;
            try {
                int n = Integer.parseInt(value);
                if (n >= min && n <= max) return n;
            } catch (NumberFormatException ignored) {}
            field.setError(t("Enter a value from ", "Saisir une valeur entre ") + min + t(" to ", " et ") + max);
            field.requestFocus();
            return -1;
        }

        private Format selectedFormat() { return Format.values()[Math.max(0, format.getSelectedItemPosition())]; }
        private int selectedDepth() { return activeDepths[Math.max(0, Math.min(activeDepths.length - 1, depth.getSelectedItemPosition()))]; }
        private String t(String en, String fr) { return words(host, en, fr); }

        private static void visibility(boolean visible, View... views) {
            for (View v : views) v.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        private static void whenChanged(Spinner v, Runnable callback) {
            v.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { callback.run(); }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }
}
