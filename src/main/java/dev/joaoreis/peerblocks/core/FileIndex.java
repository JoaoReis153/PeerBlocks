package dev.joaoreis.peerblocks.core;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caches the hash of each file in a node's folder, and forgets it when the file changes. */
public class FileIndex {

    private static final Logger log = LoggerFactory.getLogger(FileIndex.class);

    private final File folder;
    private final Path folderPath;
    private final String logPrefix;
    private final Map<Path, byte[]> hashes = new ConcurrentHashMap<>();
    private volatile WatchService watcher;

    public FileIndex(File folder, String logPrefix) {
        this.folder = folder;
        this.folderPath = folder.toPath().toAbsolutePath().normalize();
        this.logPrefix = logPrefix;
    }

    /** Starts watching the folder and hashes the files already in it, in the background. */
    public void start() {
        startWatcher();
        Thread loader = new Thread(this::refresh, "file-index-load-" + folder.getName());
        loader.setDaemon(true);
        loader.start();
    }

    /** Hashes every file in the folder that isn't cached yet, and waits for it. */
    public void refresh() {
        for (File file : sharedFiles()) getHash(file);
    }

    /** Returns the file's hash, computing it if needed, or null if it can't be read. */
    public byte[] getHash(File file) {
        Path key = keyOf(file);
        byte[] cached = hashes.get(key);
        if (cached != null) return cached;
        try {
            byte[] hash = Utils.calculateFileHash(file.getAbsolutePath());
            hashes.put(key, hash);
            return hash;
        } catch (RuntimeException e) {
            log.error(logPrefix + " Failed to calculate hash for file: " + file);
            return null;
        }
    }

    /** Records a hash that is already known to be right, such as a verified download. */
    public void put(File file, byte[] hash) {
        hashes.put(keyOf(file), hash);
    }

    /** Returns the file in the folder with this hash, or null if there is none. */
    public File findFileByHash(byte[] hash) {
        for (Map.Entry<Path, byte[]> entry : hashes.entrySet()) {
            if (MessageDigest.isEqual(entry.getValue(), hash)) {
                File file = entry.getKey().toFile();
                if (file.isFile()) return file;
                hashes.remove(entry.getKey(), entry.getValue()); // deleted from disk
            }
        }
        // Files the cache doesn't know about yet (new, or changed since they were hashed)
        for (File file : sharedFiles()) {
            if (hashes.containsKey(keyOf(file))) continue;
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

    /** Stops watching the folder. Lookups keep working. */
    public void close() {
        WatchService current = watcher;
        if (current == null) return;
        try {
            current.close();
        } catch (IOException e) {
            // already closed
        }
    }

    private void startWatcher() {
        WatchService watcher;
        try {
            watcher = FileSystems.getDefault().newWatchService();
            folderPath.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException e) {
            log.warn(logPrefix + " Can't watch " + folderPath + ": " + e.getMessage());
            return;
        }
        this.watcher = watcher;
        Thread thread = new Thread(() -> watch(watcher), "file-index-watch-" + folder.getName());
        thread.setDaemon(true);
        thread.start();
    }

    private void watch(WatchService watcher) {
        try (watcher) {
            while (true) {
                WatchKey key = watcher.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == OVERFLOW) {
                        hashes.clear(); // missed some events, so trust nothing
                    } else {
                        hashes.remove(folderPath.resolve((Path) event.context()));
                    }
                }
                if (!key.reset()) return; // the folder was deleted
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException | IOException e) {
            // stopped
        }
    }
}
