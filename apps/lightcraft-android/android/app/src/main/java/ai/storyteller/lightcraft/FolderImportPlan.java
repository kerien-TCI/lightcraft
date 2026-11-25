package ai.storyteller.lightcraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic and bounded tree scan, independent of Android and safe to test with a fake provider. */
final class FolderImportPlan {
    static final int MAX_ENTRIES = 12_000, MAX_FOLDERS = 1200, MAX_DEPTH = 12, MAX_PHOTOS = 5000;
    static final long MAX_PHOTO_BYTES = 512L << 20, MAX_BATCH_BYTES = 2L << 30, MAX_SIDECAR_BYTES = 8L << 20;
    static final Comparator<Node> ORDER = Comparator.comparing(Node::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
        .thenComparing(Node::name, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(Node::id, Comparator.nullsLast(Comparator.naturalOrder()));

    record Node(String id, String name, boolean directory, long bytes, boolean virtual, long modified) {}
    record Children(List<Node> entries, boolean limited) {}
    record Entry(Node document, String parentId, String parentName) {}
    record Pending(Node folder, int depth) {}
    record Result(List<Entry> photos, Map<String, Map<String, Node>> sidecars, int unsupported, int unreadable, boolean limited) {}

    interface Tree {
        Children children(Node folder, int remaining) throws Exception;
        void checkpoint();
    }

    static Result scan(Tree tree, Node root, Set<String> extensions) throws Exception {
        List<Entry> photos = new ArrayList<>();
        Map<String, Map<String, Node>> sidecars = new HashMap<>();
        Set<String> dirs = new HashSet<>(), seenFiles = new HashSet<>();
        Deque<Pending> pending = new ArrayDeque<>();
        dirs.add(root.id());
        pending.add(new Pending(root, 0));
        boolean limited = false;
        int unreadable = 0, unsupported = 0, entries = 0;
        while (!pending.isEmpty()) {
            tree.checkpoint();
            if (entries >= MAX_ENTRIES) { limited = true; break; }
            Pending p = pending.removeFirst();
            Children listing;
            try {
                listing = tree.children(p.folder(), MAX_ENTRIES - entries);
            } catch (Exception problem) {
                tree.checkpoint();
                if (p.depth() == 0) throw problem;
                unreadable++;
                continue;
            }
            if (listing == null) {
                if (p.depth() == 0) throw new IllegalStateException("cannot read selected folder");
                unreadable++;
                continue;
            }
            if (listing.limited()) limited = true;
            List<Node> sorted = new ArrayList<>(listing.entries());
            sorted.sort(ORDER);
            for (Node item : sorted) {
                tree.checkpoint();
                if (entries >= MAX_ENTRIES) { limited = true; break; }
                entries++;
                if (item.id() == null || item.id().isEmpty() || item.name() == null || item.name().codePoints().allMatch(Character::isWhitespace)) {
                    unsupported++;
                } else if (item.directory()) {
                    if (dirs.contains(item.id())) continue;
                    if (p.depth() >= MAX_DEPTH || dirs.size() >= MAX_FOLDERS) { limited = true; continue; }
                    dirs.add(item.id());
                    pending.addLast(new Pending(item, p.depth() + 1));
                } else if (!seenFiles.add(item.id())) {
                    // One document can be represented by several aliases inside a provider.
                    continue;
                } else {
                    String ext = extension(item.name());
                    if (item.virtual()) {
                        unsupported++;
                    } else if ("xmp".equals(ext)) {
                        sidecars.computeIfAbsent(p.folder().id(), ignored -> new HashMap<>())
                            .putIfAbsent(item.name().toLowerCase(Locale.ROOT), item);
                    } else if (extensions.contains(ext)) {
                        if (photos.size() < MAX_PHOTOS) photos.add(new Entry(item, p.folder().id(), p.folder().name()));
                        else limited = true;
                    } else {
                        unsupported++;
                    }
                }
            }
        }
        return new Result(List.copyOf(photos), Map.copyOf(sidecars), unsupported, unreadable, limited);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || name.length() == dot + 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Includes both desktop XMP conventions when both exist. */
    static List<Node> sidecars(Result scan, Entry photo) {
        Map<String, Node> siblings = scan.sidecars().get(photo.parentId());
        if (siblings == null) return List.of();
        String name = photo.document().name().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return List.of();
        Node exact = siblings.get(name + ".xmp"), bare = siblings.get(name.substring(0, dot) + ".xmp");
        if (exact == null) return bare == null ? List.of() : List.of(bare);
        return bare == null || exact.id().equals(bare.id()) ? List.of(exact) : List.of(exact, bare);
    }

    static String sidecarName(String photoSourceName, String localName, Node sidecar) {
        if (sidecar.name().equalsIgnoreCase(photoSourceName + ".xmp")) return localName + ".xmp";
        int dot = localName.lastIndexOf('.');
        return (dot <= 0 ? localName : localName.substring(0, dot)) + ".xmp";
    }

    static int parsePositive(String n, int maximum) {
        if (n == null) return -1;
        try {
            int value = Integer.parseInt(n.trim());
            return value > 0 && value <= maximum ? value : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private FolderImportPlan() {}
}
