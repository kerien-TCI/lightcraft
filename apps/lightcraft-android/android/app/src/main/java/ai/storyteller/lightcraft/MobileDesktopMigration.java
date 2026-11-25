package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only folder copy of a closed, consolidated desktop LightCraft library. */
final class MobileDesktopMigration {
    static final int REQUEST_TREE = 121;
    private static final String DESTINATION = "DesktopLibraries";
    private static final Set<String> CATALOG_FILES = Set.of("catalog.snap", "catalog.log", "presets.json", "prefs.json");
    private static final int MAX_ENTRIES = 12_000, MAX_DEPTH = 16;
    private static final long TOTAL_BYTES = 4L << 30, ORIGINAL_BYTES = 512L << 20, CATALOG_BYTES = 32L << 20, PREFS_BYTES = 4L << 20;
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    interface Host {
        void task(Callable<JSONObject> worker, Consumer<JSONObject> done, Consumer<Throwable> failed);
        JSONObject request(String op, JSONObject data) throws Exception;
        void switched(String active);
    }

    private final Activity activity;
    private final Host host;
    private CopyJob current;
    private boolean switching;

    MobileDesktopMigration(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    void destroy() {
        if (current != null) {
            current.cancel();
            finish(current);
        }
    }

    void show() {
        if (current != null || switching) return;
        host.task(() -> host.request("migration.libraries", new JSONObject()), this::menu, this::problem);
    }

    private void menu(JSONObject result) {
        JSONArray libs = result.optJSONArray("libraries");
        String active = result.optString("active");
        boolean fallback = result.optBoolean("fallback");
        List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        ids.add("");
        names.add(activity.getString(R.string.migration_choose));
        ids.add("default");
        names.add((active.equals("default") ? "✓  " : "") + activity.getString(R.string.migration_default));
        for (int i = 0; libs != null && i < libs.length(); i++) {
            JSONObject lib = libs.optJSONObject(i);
            if (lib == null || lib.optString("id").isEmpty()) continue;
            ids.add(lib.optString("id"));
            names.add((active.equals(lib.optString("id")) ? "✓  " : "") + clean(lib.optString("name"), 96));
        }
        new AlertDialog.Builder(activity).setTitle(R.string.migration_title)
            .setItems(names.toArray(new String[0]), (d, n) -> {
                String id = ids.get(n);
                if (id.isEmpty()) explain();
                else if (!id.equals(active) || (fallback && id.equals("default"))) switchTo(id);
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void explain() {
        EditText title = new EditText(activity);
        title.setSingleLine(true);
        title.setHint(R.string.migration_name);
        title.setContentDescription(activity.getString(R.string.migration_name));
        title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(96)});
        LinearLayout box = padded();
        box.addView(title);
        new AlertDialog.Builder(activity).setTitle(R.string.migration_choose).setMessage(R.string.migration_help)
            .setView(box).setPositiveButton(R.string.migration_pick, (d, w) -> {
                String name = title.getText().toString().trim();
                activity.getSharedPreferences("native_migration", Activity.MODE_PRIVATE).edit().putString("pending_name", name).apply();
                try {
                    activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQUEST_TREE);
                } catch (RuntimeException e) { problemMsg(R.string.migration_io); }
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    void onTreeResult(int resultCode, Intent data) {
        var prefs = activity.getSharedPreferences("native_migration", Activity.MODE_PRIVATE);
        String title = prefs.getString("pending_name", "");
        prefs.edit().remove("pending_name").apply();
        if (resultCode != Activity.RESULT_OK || data == null || current != null) return;
        Uri tree = data.getData();
        if (tree == null || !"content".equals(tree.getScheme()) || tree.getAuthority() == null || !DocumentsContract.isTreeUri(tree)) {
            problemMsg(R.string.migration_incomplete);
            return;
        }
        start(tree, title);
    }

    private void switchTo(String id) {
        if (switching) return;
        switching=true;
        AlertDialog dialog=new AlertDialog.Builder(activity).setMessage(R.string.migration_validate).setCancelable(false).show();
        host.task(() -> host.request("migration.switch", args("id", id)), response -> {
            switching=false;dialog.dismiss();host.switched(response.optString("active",id));
        }, error -> {switching=false;dialog.dismiss();problem(error);});
    }

    private void start(Uri tree, String title) {
        CopyJob job = progress();
        host.task(() -> {
            host.request("open", args("root", activity.getFilesDir().getCanonicalPath()));
            File parent = new File(activity.getFilesDir(), DESTINATION);
            if (!(parent.isDirectory() || parent.mkdir()) || Files.isSymbolicLink(parent.toPath())) throw failure("migration_io");
            sweep(parent);
            String id = UUID.randomUUID().toString();
            File stage = new File(parent, id + ".pending");
            if (!stage.mkdir()) throw failure("migration_io");
            try {
                String docId = DocumentsContract.getTreeDocumentId(tree);
                Item root = readDocument(tree, docId, job.signal);
                if (!root.directory()) throw failure("migration_incomplete");
                String name = clean(title.trim().isEmpty() ? root.name() : title, 96);
                if (name.isEmpty()) name = activity.getString(R.string.migration_untitled);
                new Staging(tree, stage, job.signal).all(root);
                job.commit();
                return host.request("migration.copy", args("id",id,"name",name));
            } finally {
                delete(stage);
            }
        }, result -> {
            finish(job);
            String report = activity.getString(R.string.migration_report, result.optString("name"), result.optInt("photos"),
                result.optInt("albums"), result.optInt("originals"));
            int sizeOnly = result.optInt("sizeOnly");
            if (sizeOnly > 0) report += "\n\n" + activity.getString(R.string.migration_unhashed, sizeOnly);
            String id = result.optString("id");
            new AlertDialog.Builder(activity).setTitle(R.string.migration_complete).setMessage(report)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.migration_open_copy, (d,w) -> switchTo(id)).show();
        }, error -> {
            finish(job);
            if (!job.signal.isCanceled()) problem(error);
        });
    }

    private record Item(String id, String name, boolean directory, boolean virtual, long bytes, long modified) {}

    private Item item(Cursor row) throws IOException {
        int document = row.getColumnIndex(COLUMNS[0]), name = row.getColumnIndex(COLUMNS[1]), type = row.getColumnIndex(COLUMNS[2]);
        int size = row.getColumnIndex(COLUMNS[3]), flags = row.getColumnIndex(COLUMNS[4]), last = row.getColumnIndex(COLUMNS[5]);
        if (document < 0 || name < 0 || type < 0) throw failure("migration_incomplete");
        String id = row.getString(document), label = row.getString(name);
        if (id == null || id.isEmpty() || id.length() > 8192 || label == null || label.length() > 512) throw failure("migration_scope");
        return new Item(id,label,DocumentsContract.Document.MIME_TYPE_DIR.equals(row.getString(type)),
            flags >= 0 && (row.getInt(flags) & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0,
            size < 0 || row.isNull(size) ? -1 : row.getLong(size), last < 0 || row.isNull(last) ? -1 : row.getLong(last));
    }

    private Item readDocument(Uri tree, String expected, CancellationSignal cancel) throws IOException {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, expected);
        try (Cursor row = activity.getContentResolver().query(uri, COLUMNS, null, null, null, cancel)) {
            if (row == null || !row.moveToFirst()) throw failure("migration_incomplete");
            Item found = item(row);
            if (!expected.equals(found.id())) throw failure("migration_scope");
            return found;
        }
    }

    private final class Staging {
        private final Uri tree;
        private final File stage;
        private final CancellationSignal cancel;
        private final Set<String> seen = new HashSet<>();
        private int entries;
        private long bytes;

        Staging(Uri tree, File stage, CancellationSignal cancel) {
            this.tree = tree;
            this.stage = stage;
            this.cancel = cancel;
        }

        void all(Item root) throws IOException {
            seen.add(root.id());
            Map<String,Item> top = new HashMap<>();
            for (Item child : children(root)) {
                if (!(CATALOG_FILES.contains(child.name()) || child.name().equals("Originals"))) continue;
                if (top.putIfAbsent(child.name(), child) != null || !seen.add(child.id())) throw failure("migration_scope");
            }
            Item snap = top.get("catalog.snap"), log = top.get("catalog.log"), originals = top.get("Originals");
            if (snap == null || log == null || originals == null || !originals.directory() || snap.directory() || log.directory()) {
                throw failure("migration_incomplete");
            }
            for (String name : CATALOG_FILES) {
                Item file = top.get(name);
                if (file == null) continue;
                long limit = name.endsWith(".json") ? PREFS_BYTES : CATALOG_BYTES;
                copy(file,new File(stage,name),limit,name.equals("catalog.log"));
            }
            directory(originals,new File(stage,"Originals"),1);
            // If the user selected a live shared project or the copy was still syncing, don't adopt
            // a catalog whose journal or snapshot changed while collecting the originals.
            if (!sameVersion(snap,readDocument(tree,snap.id(),cancel)) || !sameVersion(log,readDocument(tree,log.id(),cancel))) {
                throw failure("migration_changed");
            }
        }

        private boolean sameVersion(Item a, Item b) {
            return a.name().equals(b.name()) && a.bytes() == b.bytes() && a.modified() == b.modified();
        }

        private List<Item> children(Item parent) throws IOException {
            Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent.id());
            try (Cursor rows = activity.getContentResolver().query(uri,COLUMNS,null,null,null,cancel)) {
                if (rows == null) throw failure("migration_incomplete");
                List<Item> list = new ArrayList<>();
                while (rows.moveToNext()) {
                    cancel.throwIfCanceled();
                    if (++entries > MAX_ENTRIES) throw failure("migration_limit");
                    list.add(item(rows));
                }
                return list;
            }
        }

        private void directory(Item parent, File out, int depth) throws IOException {
            if (depth > MAX_DEPTH || !out.mkdir()) throw failure("migration_limit");
            Set<String> names = new HashSet<>();
            for (Item it: children(parent)) {
                if (it.name().equals(".DS_Store") || it.name().equals("Thumbs.db") || it.name().startsWith("._")) continue;
                if (!safe(it.name()) || !names.add(it.name()) || !seen.add(it.id()) || it.virtual()) throw failure("migration_scope");
                File local = new File(out,it.name());
                if (it.directory()) directory(it,local,depth+1);
                else copy(it,local,ORIGINAL_BYTES,false);
            }
        }

        private void copy(Item source, File output, long max, boolean canBeEmpty) throws IOException {
            cancel.throwIfCanceled();
            if (source.virtual() || source.directory() || output.exists()) throw failure("migration_scope");
            if (source.bytes() > max || source.bytes() > TOTAL_BYTES - bytes) throw failure("migration_limit");
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree,source.id());
            File temp = new File(output.getParentFile(), ".partial-" + UUID.randomUUID());
            long written = 0;
            try {
                try (AssetFileDescriptor file = activity.getContentResolver().openAssetFileDescriptor(uri,"r",cancel)) {
                    if (file == null) throw failure("migration_missing");
                    try (InputStream input = file.createInputStream(); OutputStream sink = Files.newOutputStream(temp.toPath())) {
                        byte[] chunks = new byte[65_536];
                        for (int n; (n = input.read(chunks)) != -1; ) {
                            cancel.throwIfCanceled();
                            if (n == 0) continue;
                            written += n;
                            if (written > max || written > TOTAL_BYTES - bytes) throw failure("migration_limit");
                            sink.write(chunks,0,n);
                        }
                    }
                }
                if ((!canBeEmpty && written == 0) || (source.bytes() >= 0 && written != source.bytes())) throw failure("migration_changed");
                Files.move(temp.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE);
                bytes += written;
            } finally {
                temp.delete();
            }
        }
    }

    private static boolean safe(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.getBytes(StandardCharsets.UTF_8).length <= 240
            && !name.contains("/") && !name.contains("\\") && !name.contains(":") && !name.codePoints().anyMatch(Character::isISOControl);
    }

    private static String clean(String name,int limit) {
        String plain = name.replaceAll("[\\p{Cntrl}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]", " ").trim();
        return plain.codePointCount(0,plain.length()) <= limit ? plain : plain.substring(0,plain.offsetByCodePoints(0,limit));
    }

    private static void sweep(File directory) {
        File[] entries = directory.listFiles();
        long cutoff = System.currentTimeMillis() - 86_400_000L;
        if (entries != null) for (File file: entries) {
            if (file.getName().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.pending") && file.lastModified() < cutoff) delete(file);
        }
    }

    private static void delete(File file) {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] files = file.listFiles();
            if (files != null) for (File child: files) delete(child);
        }
        file.delete();
    }

    private static final class CopyFailure extends IOException {
        final String code;
        CopyFailure(String code) { this.code = code; }
    }
    private static CopyFailure failure(String code) { return new CopyFailure(code); }

    private final class CopyJob {
        final CancellationSignal signal = new CancellationSignal();
        AlertDialog dialog;
        boolean saving;
        synchronized void cancel() { if (!saving) signal.cancel(); }
        synchronized void commit() {
            signal.throwIfCanceled();
            saving = true;
            activity.runOnUiThread(() -> {
                if (dialog != null && dialog.isShowing()) {
                    dialog.setTitle(R.string.migration_validate);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                }
            });
        }
    }

    private CopyJob progress() {
        CopyJob job = new CopyJob();
        current = job;
        LinearLayout box = padded();
        box.addView(new ProgressBar(activity));
        job.dialog = new AlertDialog.Builder(activity).setTitle(R.string.migration_copying).setView(box)
            .setNegativeButton(android.R.string.cancel,null).setCancelable(false).create();
        job.dialog.setOnShowListener(d -> job.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            job.cancel();
            job.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }));
        job.dialog.show();
        return job;
    }

    private void finish(CopyJob job) {
        if (job == current) current = null;
        if (job.dialog != null && job.dialog.isShowing()) job.dialog.dismiss();
    }

    private LinearLayout padded() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(22 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(p,p,p,p);
        return box;
    }

    private void problem(Throwable failure) {
        String code = failure instanceof LabEngine.Failure ? ((LabEngine.Failure) failure).code
            : failure instanceof CopyFailure ? ((CopyFailure)failure).code : "";
        problemMsg(switch(code) {
            case "migration_damaged" -> R.string.migration_damaged;
            case "migration_incomplete" -> R.string.migration_incomplete;
            case "migration_scope" -> R.string.migration_scope;
            case "migration_missing" -> R.string.migration_missing;
            case "migration_limit" -> R.string.migration_limit;
            case "migration_mismatch" -> R.string.migration_mismatch;
            case "migration_profiles" -> R.string.migration_profiles;
            case "migration_open" -> R.string.migration_open_error;
            case "migration_changed" -> R.string.migration_changed;
            default -> R.string.migration_io;
        });
    }

    private void problemMsg(int message) {
        new AlertDialog.Builder(activity).setTitle(R.string.migration_title).setMessage(message)
            .setPositiveButton(android.R.string.ok,null).show();
    }

    private static JSONObject args(Object... kv) throws JSONException {
        JSONObject value = new JSONObject();
        for(int i=0;i+1<kv.length;i+=2) value.put((String)kv[i],kv[i+1]);
        return value;
    }
}
