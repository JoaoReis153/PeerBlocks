package dev.joaoreis.peerblocks.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caches file hashes, rehashing a file when its size or modified time changes. */
public class FileIndex {

    private static final Logger log = LoggerFactory.getLogger(FileIndex.class);

    private final File folder;
    private final String node;
    private final Map<Path, Entry> hashes = new ConcurrentHashMap<>();

    public FileIndex(File folder, String node) {
        this.folder = folder;
        this.node = node;
    }

    /** Hashes the files already in the folder, in the background. */
    public void start() {
        Thread loader =
                new Thread(
                        LogContext.wrap(node, this::refresh),
                        "file-index-load-" + folder.getName());
        loader.setDaemon(true);
        loader.start();
    }

    /** Hashes every file whose hash isn't current, forgets deleted files, and waits for it. */
    public void refresh() {
        hashes.keySet().removeIf(path -> !Files.isRegularFile(path));
        for (File file : sharedFiles()) getHash(file);
    }

    /** Returns the file's hash, computing it if needed, or null if it can't be read. */
    public byte[] getHash(File file) {
        Path key = keyOf(file);
        BasicFileAttributes now = attributesOf(key);
        if (now == null) {
            hashes.remove(key);
            return null;
        }
        Entry cached = hashes.get(key);
        if (cached != null && cached.matches(now)) return cached.hash();
        try {
            byte[] hash = Utils.calculateFileHash(file.getAbsolutePath());
            hashes.put(key, Entry.of(hash, now));
            return hash;
        } catch (RuntimeException e) {
            log.error("Failed to calculate hash for file: " + file);
            return null;
        }
    }

    /** Records a hash that is already known to be right, such as a verified download. */
    public void put(File file, byte[] hash) {
        Path key = keyOf(file);
        BasicFileAttributes now = attributesOf(key);
        if (now != null) hashes.put(key, Entry.of(hash, now));
    }

    /** Returns the file in the folder with this hash, or null if there is none. */
    public File findFileByHash(byte[] hash) {
        for (Map.Entry<Path, Entry> cached : hashes.entrySet()) {
            if (!MessageDigest.isEqual(cached.getValue().hash(), hash)) continue;
            if (cached.getValue().matches(attributesOf(cached.getKey()))) {
                return cached.getKey().toFile();
            }
            hashes.remove(cached.getKey(), cached.getValue()); // deleted or changed
        }
        // Otherwise hash whatever isn't current yet (new or changed files)
        for (File file : sharedFiles()) {
            byte[] fileHash = getHash(file);
            if (fileHash != null && MessageDigest.isEqual(fileHash, hash)) return file;
        }
        return null;
    }

    public boolean contains(byte[] hash) {
        return findFileByHash(hash) != null;
    }

    public File[] sharedFiles() {
        File[] files = folder.listFiles(FileIndex::isShared);
        return files == null ? new File[0] : files;
    }

    public static boolean isShared(File file) {
        return file.isFile() && !file.getName().endsWith(Config.PARTIAL_DOWNLOAD_SUFFIX);
    }

    private Path keyOf(File file) {
        return file.toPath().toAbsolutePath().normalize();
    }

    // Null if the file is gone or isn't a regular file
    private static BasicFileAttributes attributesOf(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.isRegularFile() ? attributes : null;
        } catch (IOException e) {
            return null;
        }
    }

    // A file's hash, and its size and modified time when it was hashed
    private record Entry(byte[] hash, long size, long modifiedNanos) {

        static Entry of(byte[] hash, BasicFileAttributes attributes) {
            return new Entry(hash, attributes.size(), modifiedNanos(attributes));
        }

        // True if the file still has the size and modified time it had when hashed
        boolean matches(BasicFileAttributes now) {
            return now != null && now.size() == size && modifiedNanos(now) == modifiedNanos;
        }

        private static long modifiedNanos(BasicFileAttributes attributes) {
            return attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS);
        }
    }
}
