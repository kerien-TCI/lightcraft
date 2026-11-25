package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Export selected photos sequentially to a system-selected folder; originals are never overwritten. */
final class MobileBatchExport {
    static final int MAX_ITEMS = 50;
    private static final int MAX_FOLDER_ENTRIES = 100_000;
    private static final Pattern PAIR_FILE = Pattern.compile("Batch-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(source|xmp)");
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME};

    record Item(long id, String name) {}
    record Progress(int done, int total, int succeeded, int failed) {}
    record Failure(Item item, String code) {}
    record Report(int succeeded, List<Failure> failures) {
        int total() { return succeeded + failures.size(); }
    }

    /** No destination is remembered: the user chooses a folder for each batch. */
    record Request(List<Item> items, MobileExportTools.Options options) {
        Request {
            items = List.copyOf(items);
            if (items.isEmpty() || items.size() > MAX_ITEMS) throw new IllegalArgumentException("batch size");
            Set<Long> ids = new HashSet<>();
            for (Item item : items) if (item.id < 0 || item.name == null || !ids.add(item.id)) throw new IllegalArgumentException("photo selection");
        }

        boolean originals() { return options == null; }

        Intent folderIntent() {
            return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }

        String serialize() {
            try {
                JSONArray photos = new JSONArray();
                for (Item i : items) photos.put(new JSONObject().put("id", i.id).put("name", i.name));
                JSONObject root = new JSONObject().put("items", photos);
                if (options != null) {
                    JSONObject data = options.args(0, "");
                    data.remove("id"); data.remove("file");
                    root.put("render", data);
                }
                return root.toString();
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }

        static Request restore(String value) {
            if (value == null || value.length() > 65_536) return null;
            try {
                JSONObject root = new JSONObject(value);
                JSONArray photos = root.getJSONArray("items");
                if (photos.length() > MAX_ITEMS) return null;
                List<Item> items = new ArrayList<>();
                for (int i = 0; i < photos.length(); i++) {
                    JSONObject p = photos.getJSONObject(i);
                    items.add(new Item(p.getLong("id"), p.getString("name")));
                }
                JSONObject render = root.optJSONObject("render");
                if (render == null) return root.has("render") ? null : new Request(items, null);
                MobileExportTools.Format format = null;
                for (MobileExportTools.Format f : MobileExportTools.Format.values()) {
                    if (f.key.equals(render.getString("format"))) format = f;
                }
                if (format == null) return null;
                return new Request(items, new MobileExportTools.Options(
                    format, render.getString("sizeMode"), render.getInt("edge"), render.getInt("fitHeight"),
                    render.getInt("quality"), render.getInt("bitDepth"), render.getString("colorSpace"),
                    render.getString("metadata"), render.getInt("ppi"), render.getInt("limitKb"),
                    render.getString("sharpen"), render.getString("watermark"), render.getBoolean("hdr"),
                    render.getBoolean("lossless"), render.getInt("budgetMiB")));
            } catch (JSONException | IllegalArgumentException error) { return null; }
        }
    }

    private record FilePlan(String name, String mime, File staging) {}
    private record Staged(String stem, String extension, String mime, File primary, File xmp) {}
    private record NewDocument(Uri uri, String id, String foldedName) {}

    private MobileBatchExport() {}

    static boolean isQueueFile(String name) {
        return name != null && (MobileExportTools.ticketIsValid(name) || PAIR_FILE.matcher(name).matches());
    }

    /** UI thread. Launch folder selection from the submit callback. */
    static void show(Activity host, List<Item> items, int defaultEdge, int defaultQuality, Consumer<Request> submit) {
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            new AlertDialog.Builder(host).setTitle(R.string.batch_export)
                .setMessage(host.getString(R.string.batch_count_limit, MAX_ITEMS)).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(host).setTitle(host.getString(R.string.batch_export_count, items.size()))
            .setItems(new CharSequence[]{host.getString(R.string.batch_mode_render), host.getString(R.string.batch_mode_original)}, (dialog, which) -> {
                if (which == 0) {
                    MobileExportTools.show(host, defaultEdge, defaultQuality, opts -> submit.accept(new Request(items, opts)));
                } else {
                    new AlertDialog.Builder(host).setTitle(R.string.batch_mode_original).setMessage(R.string.batch_original_privacy)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.batch_choose_folder, (confirmation, button) -> submit.accept(new Request(items, null))).show();
                }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** One background call on the Activity's existing Rust worker (the session is thread-local). */
    static Report run(Context context, LabEngine engine, Request request, Uri folder, Consumer<Progress> update) {
        List<Failure> failures = new ArrayList<>();
        int succeeded = 0, done = 0;
        final FolderDocs target;
        try { target = new FolderDocs(context, folder); }
        catch (IOException | RuntimeException problem) {
            for (Item item : request.items) failures.add(new Failure(item, "batch_destination"));
            update.accept(new Progress(request.items.size(), request.items.size(), 0, failures.size()));
            return new Report(0, List.copyOf(failures));
        }
        for (Item item : request.items) {
            List<File> staged = new ArrayList<>(2);
            String error = null;
            try {
                File queue = new File(context.getFilesDir(), "ExportQueue");
                Staged result = stage(engine, item, request, queue, staged, originalMemoryMiB(context));
                String stem = uniqueStem(result.stem, result.extension, result.xmp != null, target.byName.keySet());
                List<FilePlan> files = new ArrayList<>(2);
                if (result.xmp != null) files.add(new FilePlan(stem + ".xmp", mimeForExtension("xmp"), result.xmp));
                files.add(new FilePlan(stem + "." + result.extension, result.mime, result.primary));
                publish(target, files);
            } catch (LabEngine.Failure problem) {
                error = problem.code;
            } catch (Exception problem) {
                error = "batch_destination";
            } finally {
                for (File f : staged) f.delete();
            }
            if (error == null) succeeded++;
            else failures.add(new Failure(item, error));
            update.accept(new Progress(++done, request.items.size(), succeeded, failures.size()));
        }
        return new Report(succeeded, List.copyOf(failures));
    }

    private static int originalMemoryMiB(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return 192;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        long mib = Math.min(info.totalMem / 4, info.availMem / 2) >> 20;
        return mib == 0 ? 192 : (int) Math.max(64, Math.min(512, mib));
    }

    private static Staged stage(LabEngine engine, Item item, Request request, File queue, List<File> staged, int budgetMiB) throws Exception {
        if (!request.originals()) {
            String ticket = request.options.ticket();
            File file = new File(queue, ticket);
            staged.add(file);
            engine.request("export_configured", request.options.args(item.id, ticket));
            return new Staged(baseName(item.name) + "-edit", request.options.format().extension, mimeForExtension(request.options.format().extension), file, null);
        }
        String ticket = "Batch-" + UUID.randomUUID();
        File primary = new File(queue, ticket + ".source");
        File xmp = new File(queue, ticket + ".xmp");
        staged.add(primary);
        staged.add(xmp);
        JSONObject reply = NativeBridge.read(engine.request("export_original_pair", new JSONObject().put("id", item.id).put("ticket", ticket).put("budgetMiB", budgetMiB)));
        String extension = reply.getString("extension").toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]{1,12}") || extension.equals("xmp")) throw new LabEngine.Failure("batch_original");
        return new Staged(baseName(reply.getString("name")), extension, mimeForExtension(extension), primary, xmp);
    }

    // Match Android's own extension-to-MIME mapping. Providers can append an extension if
    // a supplied MIME does not match, which would break the XMP sibling name.
    private static String mimeForExtension(String extension) {
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    private static void publish(FolderDocs target, List<FilePlan> files) throws LabEngine.Failure {
        List<NewDocument> ours = new ArrayList<>(files.size());
        try {
            // Reserve both names before writing either so provider renaming cannot unpair the files.
            for (FilePlan fp : files) ours.add(target.create(fp.name, fp.mime));
            for (int i = 0; i < files.size(); i++) writeNew(target.resolver, ours.get(i).uri, files.get(i).staging);
        } catch (IOException | RuntimeException problem) {
            boolean allRemoved = true;
            for (NewDocument doc : ours) allRemoved &= target.remove(doc);
            if (problem instanceof PartialDestinationException) allRemoved = false;
            throw new LabEngine.Failure(allRemoved ? "batch_destination" : "batch_partial");
        }
    }

    private static String baseName(String incoming) {
        String sanitized = Documents.localName(incoming).replaceFirst("\\.[^.]+$", "").trim();
        return sanitized.isEmpty() ? "Photo" : sanitized;
    }

    // SAF providers differ on case sensitivity. Consider names case-insensitively for the pair.
    private static String folded(String name) { return name.toLowerCase(Locale.ROOT); }

    static String uniqueStem(String stem, String extension, boolean pair, Set<String> usedNames) throws IOException {
        for (int count = 1; count <= 100_000; count++) {
            String candidate = count == 1 ? stem : stem + "-" + count;
            if (!usedNames.contains(folded(candidate + "." + extension)) && (!pair || !usedNames.contains(folded(candidate + ".xmp")))) {
                return candidate;
            }
        }
        throw new IOException("too many existing names");
    }

    private static void writeNew(ContentResolver resolver, Uri destination, File staged) throws IOException {
        if (!isQueueFile(staged.getName()) || !Files.isRegularFile(staged.toPath(), LinkOption.NOFOLLOW_LINKS)
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

    private static final class PartialDestinationException extends IOException {}

    private static final class FolderDocs {
        final ContentResolver resolver;
        final Uri tree, parent;
        final Set<String> ids = new HashSet<>();
        final Map<String, Integer> byName = new HashMap<>();

        FolderDocs(Context context, Uri tree) throws IOException {
            if (tree == null || !ContentResolver.SCHEME_CONTENT.equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree)) {
                throw new IOException("not an Android document tree");
            }
            this.tree = tree;
            resolver = context.getContentResolver();
            String id = DocumentsContract.getTreeDocumentId(tree);
            parent = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            try (Cursor c = resolver.query(children, DOCUMENT_COLUMNS, null, null, null)) {
                if (c == null || c.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)
                    || c.getExtras().containsKey(DocumentsContract.EXTRA_ERROR)) throw new IOException("cannot verify existing files");
                while (c.moveToNext()) {
                    if (ids.size() >= MAX_FOLDER_ENTRIES || c.isNull(0) || c.isNull(1)) throw new IOException("cannot verify existing files");
                    ids.add(c.getString(0));
                    byName.merge(folded(c.getString(1)), 1, Integer::sum);
                }
            }
        }

        NewDocument create(String name, String mime) throws IOException {
            Uri supplied = DocumentsContract.createDocument(resolver, parent, mime, name);
            if (supplied == null) throw new IOException();
            if (!ContentResolver.SCHEME_CONTENT.equals(supplied.getScheme()) || !tree.getAuthority().equals(supplied.getAuthority())) {
                throw new PartialDestinationException();
            }
            final String id;
            try { id = DocumentsContract.getDocumentId(supplied); }
            catch (RuntimeException malformed) { throw new PartialDestinationException(); }
            if (id.equals(DocumentsContract.getTreeDocumentId(tree)) || !ids.add(id)) throw new IOException("not a fresh document");
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            NewDocument created = new NewDocument(doc, id, folded(name));
            byName.merge(created.foldedName, 1, Integer::sum);
            try (Cursor c = resolver.query(doc, DOCUMENT_COLUMNS, null, null, null)) {
                if (c == null || !c.moveToFirst() || !id.equals(c.getString(0)) || !name.equals(c.getString(1))) {
                    throw new IOException("provider renamed the fresh file");
                }
            } catch (IOException | RuntimeException problem) {
                if (!remove(created)) throw new PartialDestinationException();
                throw new IOException(problem);
            }
            return created;
        }

        // Never delete anything from the pre-existing snapshot; cleanup is limited to fresh IDs.
        boolean remove(NewDocument doc) {
            try {
                if (!DocumentsContract.deleteDocument(resolver, doc.uri)) return false;
                ids.remove(doc.id);
                byName.computeIfPresent(doc.foldedName, (name, count) -> count <= 1 ? null : count - 1);
                return true;
            } catch (IOException | RuntimeException problem) { return false; }
        }
    }

    static String progressText(Context context, Progress progress) {
        return context.getString(R.string.batch_progress, progress.done, progress.total);
    }

    static void showResult(Activity host, Report report) {
        StringBuilder reportText = new StringBuilder(host.getString(R.string.batch_summary, report.succeeded, report.total()));
        for (Failure issue : report.failures) {
            reportText.append("\n\n").append(Documents.localName(issue.item.name)).append("\n");
            String detail = MobileExportTools.error(host, issue.code);
            if (detail == null) detail = host.getString(switch (issue.code) {
                case "batch_location" -> R.string.batch_err_location;
                case "batch_partial" -> R.string.batch_err_partial;
                case "batch_destination" -> R.string.batch_err_destination;
                case "batch_original" -> R.string.batch_err_original;
                case "nef_he" -> R.string.batch_err_nef;
                default -> R.string.batch_err_file;
            });
            reportText.append(detail);
        }
        new AlertDialog.Builder(host).setTitle(R.string.batch_export).setMessage(reportText)
            .setPositiveButton(android.R.string.ok, null).show();
    }
}
