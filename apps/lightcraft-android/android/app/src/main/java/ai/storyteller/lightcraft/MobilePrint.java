package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The PDF is composed by the desktop engine. Android displays its real pages and saves by SAF. */
final class MobilePrint {
    interface Host {
        JSONObject request(String op, JSONObject args) throws Exception;
        <T> void task(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure);
        void feedback(String message, boolean error);
    }

    static final int REQUEST_SAVE = 112;
    private static final String STATE_KEY = "mobile_print_pending";
    private static final int MAX_PHOTOS = 80;
    private static final long MAX_PDF_BYTES = 96L * 1024 * 1024;
    private static final Pattern TICKET = Pattern.compile("Print-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");
    private static final String[] PAPER_KEYS = {"a4", "a3", "a5", "letter", "4x6", "5x7"};
    private static final int[] DPI = {96, 150, 240, 300};
    private static final int[] MARGINS = {0, 5, 10, 15, 20};
    private static final int[] GAPS = {0, 2, 5, 10};
    private final Activity activity;
    private final Host host;
    private String pending;
    private boolean busy;

    MobilePrint(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    void saveState(Bundle state) {
        if (valid(pending)) state.putString(STATE_KEY, pending);
    }

    void restoreState(Bundle state) {
        if (state == null) return;
        String value = state.getString(STATE_KEY);
        if (valid(value)) pending = value;
    }

    void show(long[] selected) {
        if (busy || pending != null) return;
        if (selected.length == 0 || selected.length > MAX_PHOTOS) {
            feedback(R.string.mobile_print_select_limit, true);
            return;
        }
        long[] ids = selected.clone();
        LinearLayout body = column();
        body.setPadding(dp(18), dp(8), dp(18), dp(12));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(body);
        TextView note = new TextView(activity);
        note.setText(activity.getResources().getQuantityString(R.plurals.mobile_print_photo_count, ids.length, ids.length));
        note.setPadding(0, 0, 0, dp(4));
        body.addView(note);

        Spinner style = field(body, R.string.mobile_print_layout, new String[]{text(R.string.mobile_print_single), text(R.string.mobile_print_contact)}, ids.length == 1 ? 0 : 1);
        Spinner paper = field(body, R.string.mobile_print_paper, new String[]{"A4", "A3", "A5", "Letter", "4 × 6 in", "5 × 7 in"}, 0);
        Spinner orientation = field(body, R.string.mobile_print_orientation, new String[]{text(R.string.mobile_print_portrait), text(R.string.mobile_print_landscape)}, 0);
        Spinner resolution = field(body, R.string.mobile_print_resolution, formatted(DPI, R.string.mobile_print_ppi), 1);
        Spinner margin = field(body, R.string.mobile_print_margin, formatted(MARGINS, R.string.mobile_print_mm), 2);
        LinearLayout grid = column();
        body.addView(grid);
        Spinner columns = field(grid, R.string.mobile_print_columns, new String[]{"1","2","3","4","5","6"}, 2);
        Spinner rows = field(grid, R.string.mobile_print_rows, new String[]{"1","2","3","4","5","6","7","8"}, 3);
        Spinner gap = field(grid, R.string.mobile_print_gap, formatted(GAPS, R.string.mobile_print_mm), 2);
        CheckBox square = check(grid, R.string.mobile_print_square);
        grid.setVisibility(ids.length == 1 ? View.GONE : View.VISIBLE);
        style.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> ignored) {}
            @Override public void onItemSelected(AdapterView<?> ignored, View row, int pos, long id) {
                grid.setVisibility(pos == 0 ? View.GONE : View.VISIBLE);
            }
        });
        CheckBox fill = check(body, R.string.mobile_print_fill);
        CheckBox rotate = check(body, R.string.mobile_print_rotate);
        CheckBox numbers = check(body, R.string.mobile_print_numbers);
        CheckBox marks = check(body, R.string.mobile_print_marks);

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mobile_print_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mobile_print_preview, (d, which) -> {
                try {
                    String token = "Print-" + UUID.randomUUID() + ".pdf";
                    JSONArray list = new JSONArray();
                    for (long id : ids) list.put(id);
                    JSONObject params = new JSONObject().put("ids", list).put("file", token)
                        .put("layout", style.getSelectedItemPosition() == 1 ? "contact" : "single")
                        .put("paper", PAPER_KEYS[paper.getSelectedItemPosition()])
                        .put("orientation", orientation.getSelectedItemPosition() == 1 ? "landscape" : "portrait")
                        .put("ppi", DPI[resolution.getSelectedItemPosition()])
                        .put("marginMm", MARGINS[margin.getSelectedItemPosition()])
                        .put("columns", columns.getSelectedItemPosition()+1)
                        .put("rows", rows.getSelectedItemPosition()+1)
                        .put("gapMm", GAPS[gap.getSelectedItemPosition()])
                        .put("square", square.isChecked()).put("fill", fill.isChecked())
                        .put("rotate", rotate.isChecked()).put("pageNumbers", numbers.isChecked()).put("cropMarks", marks.isChecked());
                    prepare(token, params);
                } catch (JSONException e) {
                    feedback(R.string.mobile_print_failed, true);
                }
            }).show();
    }

    private void prepare(String token, JSONObject params) {
        busy = true;
        feedback(R.string.mobile_print_preparing, false);
        host.task(() -> {
            File pdf = staged(token);
            prune();
            try {
                JSONObject result = host.request("print_render", params);
                Preview page = readPage(pdf, 0);
                if (result.optInt("pages") != page.pages()) throw new IOException();
                return page;
            } catch (Exception failure) {
                Files.deleteIfExists(pdf.toPath());
                throw failure;
            }
        }, page -> {
            busy = false;
            if (activity.isFinishing() || activity.isDestroyed()) {
                staged(token).delete();
                return;
            }
            preview(token, page);
        }, failure -> {
            busy = false;
            showError(failure);
        });
    }

    private void preview(String token, Preview first) {
        LinearLayout content = column();
        content.setPadding(dp(12), dp(8), dp(12), dp(4));
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(text(R.string.mobile_print_page_image));
        image.setImageBitmap(first.bitmap());
        int h = Math.min(dp(500), (int)(0.49 * activity.getResources().getDisplayMetrics().heightPixels));
        content.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        content.addView(controls);
        Button prev = new Button(activity);
        prev.setText(R.string.mobile_print_previous);
        Button next = new Button(activity);
        next.setText(R.string.mobile_print_next);
        TextView label = new TextView(activity);
        label.setGravity(Gravity.CENTER);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(prev);
        controls.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(next);
        final int[] shown = {0};
        final boolean[] saving = {false};
        Runnable update = () -> {
            label.setText(activity.getString(R.string.mobile_print_page_index, shown[0]+1, first.pages()));
            prev.setEnabled(shown[0] > 0);
            next.setEnabled(shown[0]+1 < first.pages());
        };
        update.run();
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.mobile_print_preview)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mobile_print_save, null)
            .create();
        Consumer<Integer> go = index -> {
            if (index < 0 || index >= first.pages()) return;
            prev.setEnabled(false);
            next.setEnabled(false);
            host.task(() -> readPage(staged(token), index), page -> {
                if (!dialog.isShowing()) return;
                shown[0] = index;
                image.setImageBitmap(page.bitmap());
                update.run();
            }, error -> {
                if (dialog.isShowing()) {
                    update.run();
                    feedback(R.string.mobile_print_failed, true);
                }
            });
        };
        prev.setOnClickListener(v -> go.accept(shown[0]-1));
        next.setOnClickListener(v -> go.accept(shown[0]+1));
        dialog.setOnDismissListener(d -> {
            if (!saving[0]) staged(token).delete();
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, text(R.string.mobile_print_filename))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            pending = token;
            try {
                activity.startActivityForResult(create, REQUEST_SAVE);
                saving[0] = true;
                dialog.dismiss();
            } catch (RuntimeException failure) {
                pending = null;
                feedback(R.string.mobile_print_failed, true);
            }
        });
    }

    boolean onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_SAVE) return false;
        String token = pending;
        pending = null;
        if (!valid(token)) return true;
        File file = staged(token);
        Uri uri = data == null ? null : data.getData();
        if (result != Activity.RESULT_OK || uri == null) {
            file.delete();
            feedback(R.string.mobile_print_cancelled, false);
            return true;
        }
        busy = true;
        host.task(() -> {
            try { writeNew(uri, file); }
            finally { file.delete(); }
            return true;
        }, ignored -> {
            busy = false;
            feedback(R.string.mobile_print_saved, false);
        }, failure -> {
            busy = false;
            feedback(R.string.mobile_print_failed, true);
        });
        return true;
    }

    private void writeNew(Uri uri, File source) throws IOException {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) ||
            !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.size(source.toPath()) > MAX_PDF_BYTES) throw new IOException();
        try (InputStream input = Files.newInputStream(source.toPath())) {
            ParcelFileDescriptor target = activity.getContentResolver().openFileDescriptor(uri, "rw");
            if (target == null) throw new IOException();
            try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(target)) {
                // Never truncate or append to a provider-selected existing document.
                if (target.getStatSize() != 0) throw new IOException();
                Documents.transfer(input, out, MAX_PDF_BYTES);
            }
        }
    }

    private record Preview(Bitmap bitmap, int pages) {}

    private static Preview readPage(File source, int pageIndex) throws IOException {
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.size(source.toPath()) > MAX_PDF_BYTES) {
            throw new IOException();
        }
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer;
        try { renderer = new PdfRenderer(fd); }
        catch (IOException | RuntimeException failure) { fd.close(); throw failure; }
        try (renderer; PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            int w = page.getWidth(), h = page.getHeight();
            if (w <= 0 || h <= 0) throw new IOException();
            float scale = 1024.0f / Math.max(w, h);
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(w*scale)), Math.max(1, Math.round(h*scale)), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return new Preview(bitmap, renderer.getPageCount());
        }
    }

    private void prune() {
        File[] stale = new File(activity.getFilesDir(), "PrintQueue").listFiles();
        if (stale == null) return;
        long before = System.currentTimeMillis() - 24L*60*60*1000;
        for (File file : stale) {
            if (valid(file.getName()) && file.lastModified() < before) file.delete();
        }
    }

    private File staged(String token) { return new File(new File(activity.getFilesDir(), "PrintQueue"), token); }
    private static boolean valid(String token) { return token != null && TICKET.matcher(token).matches(); }

    private void showError(Throwable error) {
        String code = error instanceof LabEngine.Failure failure ? failure.code : "";
        int message = switch(code) {
            case "print_limit" -> R.string.mobile_print_memory_limit;
            case "print_page" -> R.string.mobile_print_bad_layout;
            default -> R.string.mobile_print_failed;
        };
        feedback(message, true);
    }

    private void feedback(int res, boolean err) { host.feedback(text(res), err); }
    private String text(int res) { return activity.getString(res); }
    private int dp(int n) { return Math.round(n*activity.getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }
    private Spinner field(LinearLayout parent, int name, String[] values, int index) {
        TextView label = new TextView(activity);
        Spinner input = new Spinner(activity, Spinner.MODE_DIALOG);
        input.setId(View.generateViewId());
        label.setText(name);
        label.setLabelFor(input.getId());
        label.setPadding(0, dp(6), 0, 0);
        parent.addView(label);
        input.setContentDescription(text(name));
        input.setPrompt(text(name));
        input.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        input.setAdapter(adapter);
        input.setSelection(index);
        parent.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return input;
    }
    private CheckBox check(LinearLayout parent, int name) {
        CheckBox view = new CheckBox(activity);
        view.setText(name);
        view.setMinimumHeight(dp(48));
        parent.addView(view);
        return view;
    }
    private String[] formatted(int[] values, int res) {
        String[] out = new String[values.length];
        for (int i=0;i<values.length;i++) out[i] = activity.getString(res, values[i]);
        return out;
    }
}
