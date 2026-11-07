package dev.joaoreis.peerblocks.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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

        // The size changed, so the next lookup hashes again
        assertArrayEquals(newHash, index.getHash(file));
        assertNull(index.findFileByHash(oldHash));
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
    void reusesTheHashOfAnUnchangedFile() throws IOException {
        File file = write("g.txt", "stays the same");

        byte[] first = index.getHash(file);
        assertSame(first, index.getHash(file), "an unchanged file should not be hashed again");
    }

    @Test
    void rehashesAFileWhoseSizeChanged() throws IOException {
        File file = write("h.txt", "short");
        byte[] oldHash = index.getHash(file);

        write("h.txt", "a much longer version");

        assertArrayEquals(hashOf(file), index.getHash(file));
        assertNull(index.findFileByHash(oldHash), "the old hash must not find the file");
    }

    @Test
    void rehashesAFileChangedToTheSameSize() throws IOException {
        File file = write("i.txt", "aaaa");
        byte[] oldHash = index.getHash(file);
        FileTime hashedAt = Files.getLastModifiedTime(file.toPath());

        // Same size, different bytes: only the modified time tells them apart
        write("i.txt", "bbbb");
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(hashedAt.toMillis() + 2_000));
        byte[] newHash = hashOf(file);

        assertNull(index.findFileByHash(oldHash), "the old hash must not find the file");
        assertEquals(file.getCanonicalFile(), index.findFileByHash(newHash).getCanonicalFile());
    }

    @Test
    void forgetsAKnownHashWhenTheFileChanges() throws IOException {
        File file = write("j.txt", "verified download");
        byte[] verified = hashOf(file);
        index.put(file, verified);

        write("j.txt", "edited after the download");

        assertArrayEquals(hashOf(file), index.getHash(file));
        assertNull(index.findFileByHash(verified));
    }
}
