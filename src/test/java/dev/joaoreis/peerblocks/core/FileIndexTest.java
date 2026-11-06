package dev.joaoreis.peerblocks.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileIndexTest {

    @TempDir Path folder;

    private FileIndex index;

    @BeforeEach
    void setUp() {
        index = new FileIndex(folder.toFile(), "[test]");
        index.start();
    }

    private File write(String name, String content) throws IOException {
        return Files.writeString(folder.resolve(name), content, StandardCharsets.UTF_8).toFile();
    }

    private static byte[] hashOf(File file) {
        return Utils.calculateFileHash(file.getAbsolutePath());
    }

    @Test
    void hashesAFile() throws IOException {
        File file = write("a.txt", "hello");
        assertArrayEquals(hashOf(file), index.getHash(file));
    }

    @Test
    void findsAFileThatWasNeverHashed() throws IOException {
        File file = write("b.txt", "not cached yet");
        byte[] hash = hashOf(file);

        assertEquals(file.getCanonicalFile(), index.findFileByHash(hash).getCanonicalFile());
        assertTrue(index.contains(hash));
    }

    @Test
    void forgetsADeletedFile() throws IOException {
        File file = write("c.txt", "soon gone");
        byte[] hash = index.getHash(file);
        Files.delete(file.toPath());

        assertNull(index.findFileByHash(hash));
        assertFalse(index.contains(hash));
    }

    @Test
    void rehashesAFileAfterItChanges() throws Exception {
        File file = write("d.txt", "first version");
        byte[] oldHash = index.getHash(file);
        write("d.txt", "second version");
        byte[] newHash = hashOf(file);

        // The watcher is asynchronous (and polls every few seconds on macOS)
        long deadline = System.currentTimeMillis() + 20_000;
        while (Arrays.equals(index.getHash(file), oldHash)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertArrayEquals(newHash, index.getHash(file));
        assertEquals(file.getCanonicalFile(), index.findFileByHash(newHash).getCanonicalFile());
    }

    @Test
    void trustsAKnownHash() throws IOException {
        File file = write("e.txt", "verified");
        byte[] hash = hashOf(file);
        index.put(file, hash);

        assertArrayEquals(hash, index.getHash(file));
    }

    @Test
    void closeStopsTheWatcherButKeepsLookupsWorking() throws Exception {
        String watcherName = "file-index-watch-" + folder.toFile().getName();
        index.close();

        long deadline = System.currentTimeMillis() + 5_000;
        while (watcherAlive(watcherName) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertFalse(watcherAlive(watcherName), "the watcher thread should end");
        File file = write("f.txt", "still works");
        assertArrayEquals(hashOf(file), index.getHash(file));
    }

    private static boolean watcherAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && t.getName().equals(name));
    }
}
