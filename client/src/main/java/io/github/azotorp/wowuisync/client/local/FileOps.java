package io.github.azotorp.wowuisync.client.local;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;

/** File tree helpers. */
public final class FileOps {
    /** Counts of a tree. */
    public record TreeSize(long files, long bytes) {}

    public interface CopyProgress {
        void copied(long files, long bytes);
    }

    private FileOps() {}

    public static TreeSize size(Path root) throws IOException {
        if (!Files.exists(root)) return new TreeSize(0, 0);
        long[] t = new long[2];
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (a.isRegularFile()) {
                    t[0]++;
                    t[1] += a.size();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return new TreeSize(t[0], t[1]);
    }

    /** Copies a whole tree, preserving timestamps. */
    public static void copyTree(Path src, Path dst, CopyProgress progress) throws IOException {
        if (!Files.exists(src)) return;
        long[] c = new long[2];
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                Files.copy(f, dst.resolve(src.relativize(f).toString()), StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING);
                c[0]++;
                c[1] += a.size();
                if (progress != null && c[0] % 50 == 0) progress.copied(c[0], c[1]);
                return FileVisitResult.CONTINUE;
            }
        });
        if (progress != null) progress.copied(c[0], c[1]);
    }

    /**
     * Makes {@code dst} match {@code src}: copies files that are new or differ in size or modification time,
     * removes files and folders that are not in {@code src}.
     */
    public static void mirror(Path src, Path dst, CopyProgress progress) throws IOException {
        long[] c = new long[2];
        if (Files.exists(src)) {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                    Path t = dst.resolve(src.relativize(d).toString());
                    if (Files.exists(t, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(t, LinkOption.NOFOLLOW_LINKS)) Files.delete(t);
                    Files.createDirectories(t);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                    Path t = dst.resolve(src.relativize(f).toString());
                    boolean same = false;
                    if (Files.isRegularFile(t, LinkOption.NOFOLLOW_LINKS)) {
                        BasicFileAttributes ta = Files.readAttributes(t, BasicFileAttributes.class);
                        same = ta.size() == a.size() && ta.lastModifiedTime().equals(a.lastModifiedTime());
                    } else if (Files.isDirectory(t, LinkOption.NOFOLLOW_LINKS)) {
                        deleteTree(t);
                    }
                    if (!same) {
                        Files.copy(f, t, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                        c[0]++;
                        c[1] += a.size();
                        if (progress != null && c[0] % 50 == 0) progress.copied(c[0], c[1]);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (Files.exists(dst)) {
            try (Stream<Path> s = Files.walk(dst)) {
                for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                    Path rel = dst.relativize(p);
                    if (rel.toString().isEmpty()) continue;
                    if (!Files.exists(src.resolve(rel.toString()), LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(p);
                }
            }
            if (!Files.exists(src)) Files.deleteIfExists(dst);
        }
        if (progress != null) progress.copied(c[0], c[1]);
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }

    /** Usable space on the volume holding {@code p}. */
    public static long usableSpace(Path p) throws IOException {
        Path q = p;
        while (q != null && !Files.exists(q)) q = q.getParent();
        return q == null ? Long.MAX_VALUE : Files.getFileStore(q).getUsableSpace();
    }

    /** Deletes a file and then any empty parent folders up to (not including) {@code stop}. */
    public static void deleteAndPrune(Path file, Path stop) throws IOException {
        Files.deleteIfExists(file);
        Path d = file.getParent();
        while (d != null && !d.equals(stop) && d.startsWith(stop)) {
            try (Stream<Path> s = Files.list(d)) {
                if (s.findAny().isPresent()) break;
            }
            Files.deleteIfExists(d);
            d = d.getParent();
        }
    }
}
