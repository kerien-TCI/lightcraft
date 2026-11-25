package ai.storyteller.lightcraft;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Import only provider documents; write only copies inside the app sandbox. */
final class Documents {
    static final int MAX_FILES = 50;
    static final long MAX_FILE_BYTES = 512L << 20;
    static final long MAX_BATCH_BYTES = 2L << 30;

    private Documents() {}

    static String localName(String name) {
        String leaf = name.replaceAll("[^\\p{L}\\p{N} ._()-]", "_").trim().replaceFirst("^\\.+", "").trim();
        if (leaf.isEmpty()) return "photo.bin";
        if (leaf.getBytes(StandardCharsets.UTF_8).length > 160) {
            String ext = leaf.substring(leaf.lastIndexOf('.') + 1);
            leaf = "photo-" + Integer.toUnsignedString(leaf.hashCode());
            if (ext.matches("[A-Za-z0-9]{1,12}")) leaf += "." + ext;
        }
        return leaf;
    }

    static File copy(ContentResolver resolver, Uri uri, File imports, long limit) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme()) || limit <= 0) throw new IOException();
        String name = "photo.bin";
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = localName(c.getString(0));
        } catch (RuntimeException ignored) {
            // A provider need not expose a display name.
            if (uri.getLastPathSegment() != null) name = localName(uri.getLastPathSegment());
        }
        // Each operation owns a fresh, private directory; preserve the readable original name.
        File folder = new File(imports, UUID.randomUUID().toString());
        if (!folder.mkdirs()) throw new IOException();
        File output = new File(folder, name);
        File temp = new File(folder, ".incomplete");
        boolean success = false;
        try {
            try (InputStream in = resolver.openInputStream(uri);
                 OutputStream out = Files.newOutputStream(temp.toPath())) {
                if (in == null) throw new IOException();
                transfer(in, out, limit);
            }
            Files.move(temp.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE);
            success = true;
            return output;
        } finally {
            if (!success) {
                temp.delete();
                folder.delete();
            }
        }
    }

    static long transfer(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long written = 0;
        for (int n; (n = in.read(buffer)) != -1;) {
            if (n == 0) continue;
            written += n;
            if (written > limit) throw new IOException();
            out.write(buffer, 0, n);
        }
        if (written == 0) throw new IOException();
        return written;
    }
}
