package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A read-only SAF tree import. Only the private staging directory is ever written. */
final class MobileFolderImport {
    static final int REQUEST_TREE = 110;
    private static final String INBOX = "FolderInbox", SAVED = "remembered_tree", SAVED_NAME = "remembered_label";
    private static final String MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    interface Host {
        void task(Callable<JSONObject> worker, Consumer<JSONObject> done, Consumer<Throwable> failed);
        JSONObject request(String name, JSONObject payload) throws Exception;
        void imported();
    }

    private final Activity activity;
    private final Host host;
    private final SharedPreferences prefs;
    private Work active;

    MobileFolderImport(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        prefs = activity.getSharedPreferences("folder_import", Context.MODE_PRIVATE);
    }

    /** MainActivity calls this only for a final destruction or rotation. */
    void destroy() {
        if (active != null) {
            Work job = active;
            job.cancel();
            finish(job);
        }
    }

    void show() {
        if (active != null) return;
        Uri saved = remembered();
        List<CharSequence> options = new ArrayList<>();
        options.add(activity.getString(R.string.folder_choose));
        if (saved != null) {
            options.add(activity.getString(R.string.folder_rescan, prefs.getString(SAVED_NAME, activity.getString(R.string.folder_default_name))));
            options.add(activity.getString(R.string.folder_forget));
        }
        new AlertDialog.Builder(activity).setTitle(R.string.folder_import_title)
            .setItems(options.toArray(CharSequence[]::new), (dialog, index) -> {
                if (index == 0) choose();
                else if (index == 1) scan(saved, true, 0);
                else forget(saved);
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void choose() {
        try {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            activity.startActivityForResult(picker, REQUEST_TREE);
        } catch (RuntimeException noProvider) {
            problem(R.string.folder_scan_error);
        }
    }

    void onTreeResult(int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null && isTree(data.getData())) {
            scan(data.getData(), false, data.getFlags());
        }
    }

    private static boolean isTree(Uri uri) {
        return uri != null && "content".equals(uri.getScheme()) && DocumentsContract.isTreeUri(uri)
            && uri.getAuthority() != null;
    }

    private Uri remembered() {
        String encoded = prefs.getString(SAVED, "");
        if (encoded.isEmpty()) return null;
        Uri uri = Uri.parse(encoded);
        if (isTree(uri)) {
            try {
                for (UriPermission grant : activity.getContentResolver().getPersistedUriPermissions()) {
                    if (grant.isReadPermission() && grant.getUri().equals(uri)) return uri;
                }
            } catch (RuntimeException unavailable) {
                return null;
            }
        }
        prefs.edit().remove(SAVED).remove(SAVED_NAME).apply();
        return null;
    }

    private void forget(Uri saved) {
        String label = prefs.getString(SAVED_NAME, activity.getString(R.string.folder_default_name));
        new AlertDialog.Builder(activity).setTitle(R.string.folder_forget)
            .setMessage(activity.getString(R.string.folder_forget_info, label))
            .setPositiveButton(R.string.folder_forget, (d, which) -> {
                release(saved);
                prefs.edit().remove(SAVED).remove(SAVED_NAME).apply();
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void release(Uri tree) {
        try {
            activity.getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException noGrant) {
            // The provider/system may already have revoked it.
        }
    }

    private boolean remember(Scan scan) {
        Uri old = remembered();
        if (scan.alreadyRemembered() || (old != null && old.equals(scan.tree()))) return true;
        if ((scan.grantFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0
            || (scan.grantFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) return false;
        try {
            activity.getContentResolver().takePersistableUriPermission(scan.tree(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            prefs.edit().putString(SAVED, scan.tree().toString()).putString(SAVED_NAME, scan.name()).apply();
            if (old != null) release(old);
            return true;
        } catch (RuntimeException notPersistable) {
            return false;
        }
    }

    private record Scan(Uri tree, String name, int grantFlags, boolean alreadyRemembered, int batchSize, FolderImportPlan.Result found) {}

    private void scan(Uri tree, boolean saved, int flags) {
        if (active != null || !isTree(tree)) return;
        Work job = progress(R.string.folder_scanning);
        // Holder is scoped to this job and the host marshals completion back to Android's UI thread.
        Scan[] next = new Scan[1];
        host.task(() -> {
            host.request("open", json("root", activity.getFilesDir().getCanonicalPath()));
            JSONObject options = host.request("folder.options", new JSONObject());
            Set<String> extensions = new HashSet<>();
            JSONArray names = options.optJSONArray("extensions");
            if (names == null || names.length() == 0) throw new IOException("no supported formats");
            for (int i = 0; i < names.length(); ++i) extensions.add(names.getString(i));
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            FolderImportPlan.Node root = readRoot(tree, rootId, job.signal);
            if (!root.directory()) throw new IOException("not a document tree");
            FolderImportPlan.Result result = FolderImportPlan.scan(new FolderImportPlan.Tree() {
                @Override public FolderImportPlan.Children children(FolderImportPlan.Node parent, int remaining) throws Exception {
                    return readChildren(tree, parent.id(), remaining, job.signal);
                }
                @Override public void checkpoint() { job.signal.throwIfCanceled(); }
            }, root, extensions);
            int batchSize = Math.max(1, Math.min(150, options.optInt("maximum", 150)));
            next[0] = new Scan(tree, safeText(root.name(), 70), flags, saved, batchSize, result);
            return new JSONObject();
        }, result -> {
            finish(job);
            if (!job.signal.isCanceled()) review(next[0]);
        }, error -> {
            finish(job);
            if (!job.signal.isCanceled()) problem(R.string.folder_scan_error);
        });
    }

    private FolderImportPlan.Node readRoot(Uri tree, String id, CancellationSignal cancel) throws IOException {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        try (Cursor cursor = activity.getContentResolver().query(document, COLUMNS, null, null, null, cancel)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("cannot query chosen folder");
            FolderImportPlan.Node result = readNode(cursor);
            if (!id.equals(result.id())) throw new IOException("provider returned a different folder");
            return result;
        }
    }

    private FolderImportPlan.Children readChildren(Uri tree, String id, int remaining, CancellationSignal cancel) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor cursor = activity.getContentResolver().query(children, COLUMNS, null, null, null, cancel)) {
            if (cursor == null) throw new IOException("folder could not be opened");
            List<FolderImportPlan.Node> entries = new ArrayList<>();
            while (entries.size() < remaining && cursor.moveToNext()) {
                cancel.throwIfCanceled();
                entries.add(readNode(cursor));
            }
            return new FolderImportPlan.Children(entries, entries.size() == remaining && cursor.moveToNext());
        }
    }

    private FolderImportPlan.Node readNode(Cursor cursor) throws IOException {
        int doc = cursor.getColumnIndex(COLUMNS[0]), name = cursor.getColumnIndex(COLUMNS[1]), type = cursor.getColumnIndex(COLUMNS[2]);
        if (doc < 0 || name < 0 || type < 0) throw new IOException("provider did not return document information");
        int size = cursor.getColumnIndex(COLUMNS[3]), flags = cursor.getColumnIndex(COLUMNS[4]);
        int mod = cursor.getColumnIndex(COLUMNS[5]);
        long modified = mod < 0 || cursor.isNull(mod) ? -1 : cursor.getLong(mod);
        long bytes = size < 0 || cursor.isNull(size) ? -1 : cursor.getLong(size);
        boolean virtual = flags >= 0 && (cursor.getInt(flags) & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0;
        String rawName = cursor.getString(name), id = cursor.getString(doc);
        // Do not let a faulty provider allocate huge labels, duplicate keys or filesystem components.
        if (id == null || id.length() > 8192 || rawName == null || rawName.length() > 512) {
            return new FolderImportPlan.Node("", "", false, -1, true, -1);
        }
        return new FolderImportPlan.Node(id, rawName, MIME_DIR.equals(cursor.getString(type)), bytes, virtual, modified);
    }

    private void review(Scan scan) {
        int count = scan.found().photos().size();
        if (count == 0) {
            new AlertDialog.Builder(activity).setTitle(scan.name())
                .setMessage(R.string.folder_no_photos).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        LinearLayout page = column();
        TextView summary = new TextView(activity);
        summary.setText(activity.getString(R.string.folder_found, count, scan.found().unsupported(), scan.found().unreadable()));
        page.addView(summary);
        if (scan.found().limited()) addLabel(page, R.string.folder_limit);
        addLabel(page, R.string.folder_copy_only);
        if (count > scan.batchSize()) {
            addLabel(page, R.string.folder_batch_help, scan.batchSize());
        }
        EditText first = number(page, R.string.folder_first, "1");
        first.setVisibility(count > scan.batchSize() ? android.view.View.VISIBLE : android.view.View.GONE);
        addLabel(page, R.string.folder_naming);
        EditText template = new EditText(activity);
        template.setSingleLine(true);
        template.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        template.setHint(R.string.folder_template_hint);
        template.setContentDescription(activity.getString(R.string.folder_naming));
        page.addView(template);
        addLabel(page, R.string.folder_template_examples);
        EditText sequence = number(page, R.string.folder_sequence, "1");
        Spinner arrange = spinner(page, R.string.folder_arrange_day, R.string.folder_arrange_month, R.string.folder_arrange_flat);
        CheckBox keep = new CheckBox(activity);
        keep.setText(R.string.folder_remember);
        boolean canRemember = !scan.alreadyRemembered()
            && (scan.grantFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        if (canRemember) page.addView(keep);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(page);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(scan.name())
            .setView(scroll).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_confirm, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int start = count > scan.batchSize() ? FolderImportPlan.parsePositive(first.getText().toString(), count) : 1;
            int seq = FolderImportPlan.parsePositive(sequence.getText().toString(), 999_999_999);
            if (start < 0) { first.setError(activity.getString(R.string.folder_numeric_error)); return; }
            if (seq < 0) { sequence.setError(activity.getString(R.string.folder_numeric_error)); return; }
            String pattern = template.getText().toString().trim();
            if (pattern.contains("/") || pattern.contains("\\") || pattern.startsWith(".")) {
                template.setError(activity.getString(R.string.folder_template_error)); return;
            }
            String organization = new String[]{"date", "month", "flat"}[arrange.getSelectedItemPosition()];
            boolean saveAccess = canRemember && keep.isChecked();
            dialog.dismiss();
            importBatch(scan, start - 1, pattern, seq, organization, saveAccess);
        }));
        dialog.show();
    }

    private void importBatch(Scan scan, int offset, String template, int sequence, String organize, boolean keep) {
        Work job = progress(R.string.folder_copying);
        host.task(() -> {
            host.request("open", json("root", activity.getFilesDir().getCanonicalPath()));
            if (!template.isEmpty()) host.request("folder.validate", json("template", template));
            File root = new File(activity.getFilesDir(), INBOX);
            if (!(root.isDirectory() || root.mkdir())) throw new IOException("private import directory unavailable");
            sweep(root);
            String token = UUID.randomUUID().toString();
            File st = new File(root, token);
            if (!st.mkdir()) throw new IOException("private batch directory unavailable");
            try {
                JSONArray paths = new JSONArray();
                long remaining = FolderImportPlan.MAX_BATCH_BYTES;
                int noRead = 0, sidecarErrors = 0, capacity = 0;
                int last = Math.min(scan.found().photos().size(), offset + scan.batchSize());
                Map<String, String> groups = new HashMap<>();
                Map<String, Set<String>> groupNames = new HashMap<>();
                int next = 1;
                for (int i = offset; i < last; ++i) {
                    job.signal.throwIfCanceled();
                    FolderImportPlan.Entry photo = scan.found().photos().get(i);
                    FolderImportPlan.Node document = photo.document();
                    if (remaining <= 0 || document.bytes() > remaining || document.bytes() > FolderImportPlan.MAX_PHOTO_BYTES) {
                        capacity++;
                        continue;
                    }
                    String local = Documents.localName(photo.document().name());
                    String group = groups.get(photo.parentId());
                    if (group == null) {
                        group = "g" + next++;
                        groups.put(photo.parentId(), group);
                        groupNames.put(group, new HashSet<>());
                    }
                    if (!groupNames.get(group).add(local.toLowerCase(Locale.ROOT))) {
                        group = "g" + next++;
                    }
                    String dir = Documents.localName(photo.parentName());
                    File grouped = new File(new File(st, group), dir);
                    if (!(grouped.isDirectory() || grouped.mkdirs())) throw new IOException("private copy folder unavailable");
                    try {
                        long written = copy(scan.tree(), document, new File(grouped, local), Math.min(remaining, FolderImportPlan.MAX_PHOTO_BYTES), job.signal);
                        remaining -= written;
                        paths.put(group + "/" + dir + "/" + local);
                    } catch (OverLimit tooBig) {
                        capacity++;
                        continue;
                    } catch (IOException | RuntimeException reader) {
                        job.signal.throwIfCanceled();
                        noRead++;
                        continue;
                    }
                    for (FolderImportPlan.Node sidecar : FolderImportPlan.sidecars(scan.found(), photo)) {
                        if (remaining <= 0) { sidecarErrors++; continue; }
                        File output = new File(grouped, FolderImportPlan.sidecarName(document.name(), local, sidecar));
                        if (output.isFile()) continue;
                        try {
                            remaining -= copy(scan.tree(), sidecar, output, Math.min(remaining, FolderImportPlan.MAX_SIDECAR_BYTES), job.signal);
                        } catch (IOException | RuntimeException missing) {
                            job.signal.throwIfCanceled();
                            sidecarErrors++;
                        }
                    }
                }
                job.beginCommit();
                JSONObject result = paths.length() == 0 ? new JSONObject()
                    : host.request("folder.import", json("token", token, "photos", paths, "template", template, "sequence", sequence, "organize", organize));
                return result.put("unreadable", noRead).put("sidecarErrors", sidecarErrors).put("capacity", capacity)
                    .put("notRemembered", keep && !remember(scan))
                    .put("next", last < scan.found().photos().size() ? last + 1 : 0);
            } finally {
                deleteOwned(st);
            }
        }, result -> {
            finish(job);
            host.imported();
            String report = activity.getString(R.string.folder_report, result.optInt("imported"), result.optInt("duplicates"),
                result.optInt("failed"), result.optInt("unreadable"), result.optInt("capacity"), result.optInt("sidecarErrors"),
                result.optInt("warnings"));
            if (result.optBoolean("notRemembered")) report += "\n\n" + activity.getString(R.string.folder_not_remembered);
            int next = result.optInt("next");
            if (next > 0) report += "\n\n" + activity.getString(R.string.folder_resume, next);
            new AlertDialog.Builder(activity).setTitle(R.string.folder_import_title).setMessage(report)
                .setPositiveButton(android.R.string.ok, null).show();
        }, error -> {
            finish(job);
            if (!job.signal.isCanceled()) {
                boolean templateError = error instanceof LabEngine.Failure && "folder_template".equals(((LabEngine.Failure) error).code);
                problem(templateError ? R.string.folder_template_error : R.string.folder_import_error);
            }
        });
    }

    private static final class OverLimit extends IOException {}

    private long copy(Uri tree, FolderImportPlan.Node node, File output, long limit, CancellationSignal cancel) throws IOException {
        if (limit <= 0 || node.bytes() > limit) throw new OverLimit();
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, node.id());
        File tmp = new File(output.getParentFile(), ".partial-" + UUID.randomUUID());
        long bytes = 0;
        try {
            try (AssetFileDescriptor fd = activity.getContentResolver().openAssetFileDescriptor(uri, "r", cancel)) {
                if (fd == null) throw new IOException("no stream");
                try (InputStream in = fd.createInputStream(); OutputStream out = Files.newOutputStream(tmp.toPath())) {
                    byte[] buffer = new byte[65_536];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        cancel.throwIfCanceled();
                        if (n == 0) continue;
                        bytes += n;
                        if (bytes > limit) throw new OverLimit();
                        out.write(buffer, 0, n);
                    }
                }
            }
            cancel.throwIfCanceled();
            if (bytes == 0 || output.exists()) throw new IOException("unsafe or empty document");
            Files.move(tmp.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE);
            // The desktop's fallback when EXIF/XMP gives no date is the source's modified date.
            // Preserve that hint on this private copy, never on the provider's document.
            if (node.modified() > 0) {
                try {
                    Files.setLastModifiedTime(output.toPath(), FileTime.fromMillis(node.modified()));
                } catch (IOException | RuntimeException ignored) {
                    // Not all filesystems honor provider timestamps. The engine can still import.
                }
            }
            return bytes;
        } finally {
            tmp.delete();
        }
    }

    private static void deleteOwned(File file) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteOwned(child);
        }
        file.delete();
    }

    private static void sweep(File inbox) {
        File[] entries = inbox.listFiles();
        long cutoff = System.currentTimeMillis() - 86_400_000L;
        if (entries != null) for (File item : entries) {
            if (item.getName().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") && item.lastModified() < cutoff) deleteOwned(item);
        }
    }

    private final class Work {
        final CancellationSignal signal = new CancellationSignal();
        AlertDialog dialog;
        boolean committing;

        synchronized void cancel() { if (!committing) signal.cancel(); }

        synchronized void beginCommit() {
            signal.throwIfCanceled();
            committing = true;
            activity.runOnUiThread(() -> {
                if (dialog != null && dialog.isShowing()) {
                    dialog.setTitle(R.string.folder_saving);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                }
            });
        }
    }

    private Work progress(int text) {
        Work job = new Work();
        active = job;
        LinearLayout column = column();
        column.addView(new ProgressBar(activity), new LinearLayout.LayoutParams(dp(48), dp(48)));
        job.dialog = new AlertDialog.Builder(activity).setTitle(text).setView(column)
            .setNegativeButton(android.R.string.cancel, null).setCancelable(false).create();
        job.dialog.setOnShowListener(d ->
            job.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                job.cancel();
                job.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            }));
        job.dialog.show();
        return job;
    }

    private void finish(Work job) {
        if (active == job) active = null;
        if (job.dialog != null && job.dialog.isShowing()) job.dialog.dismiss();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(22), dp(8), dp(22), dp(12));
        return view;
    }

    private EditText number(LinearLayout page, int label, String initial) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setText(initial);
        field.setHint(label);
        field.setContentDescription(activity.getString(label));
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});
        page.addView(field);
        return field;
    }

    private Spinner spinner(LinearLayout page, int... labels) {
        Spinner s = new Spinner(activity);
        s.setContentDescription(activity.getString(R.string.folder_arrange));
        String[] values = new String[labels.length];
        for (int i = 0; i < values.length; ++i) values[i] = activity.getString(labels[i]);
        s.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, values));
        page.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        return s;
    }

    private void addLabel(LinearLayout page, int res, Object... args) {
        TextView label = new TextView(activity);
        label.setText(activity.getString(res, args));
        label.setPadding(0, dp(8), 0, dp(4));
        page.addView(label);
    }

    private String safeText(String value, int limit) {
        String plain = value.replaceAll("[\\p{Cntrl}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]", " ").trim();
        if (plain.isEmpty()) return activity.getString(R.string.folder_default_name);
        return plain.codePointCount(0, plain.length()) <= limit ? plain : plain.substring(0, plain.offsetByCodePoints(0, limit)) + "…";
    }

    private static JSONObject json(Object... values) throws JSONException {
        JSONObject object = new JSONObject();
        for (int i = 0; i + 1 < values.length; i += 2) object.put((String) values[i], values[i + 1]);
        return object;
    }

    private void problem(int text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    private int dp(int count) { return Math.round(count * activity.getResources().getDisplayMetrics().density); }
}
