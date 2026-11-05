package dev.joaoreis.peerblocks.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.support.FakeSeeder;
import dev.joaoreis.peerblocks.support.TestListener;
import dev.joaoreis.peerblocks.support.TestNodes;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Downloads must stay inside the node's folder and never touch the node's own files. */
@Timeout(60)
class DownloadFileSafetyTest {

    private static final long DOWNLOAD_TIMEOUT_MS = 30_000;

    @AfterEach
    void cleanUp() {
        TestNodes.cleanup();
    }

    @Test
    void neverOverwritesAFileWithTheSameName() throws Exception {
        byte[] theirs = TestNodes.randomBytes(1_000_000, 11);
        byte[] mine = TestNodes.randomBytes(1_000, 12);
        Node seeder = TestNodes.startSeeder("song.bin", theirs);
        TestListener listener = new TestListener();
        Node downloader = TestNodes.startNode(listener);
        Files.write(TestNodes.fileOf(downloader, "song.bin").toPath(), mine);

        TestNodes.connect(seeder, downloader);
        TestNodes.searchAndDownload(downloader, listener, "song", 1);

        assertTrue(listener.awaitFinished(DOWNLOAD_TIMEOUT_MS), "download did not finish");
        assertTrue(listener.downloadSucceeded());
        assertArrayEquals(
                mine, Files.readAllBytes(TestNodes.fileOf(downloader, "song.bin").toPath()));
        assertArrayEquals(
                theirs, Files.readAllBytes(TestNodes.fileOf(downloader, "song (1).bin").toPath()));
    }

    @Test
    void keepsAnExistingFileWhenTheDownloadFails() throws Exception {
        byte[] mine = TestNodes.randomBytes(1_000, 13);
        File source = TestNodes.sourceFile("notes.txt");
        Files.write(source.toPath(), TestNodes.randomBytes(4_000_000, 14));
        TestListener listener = new TestListener();
        Node downloader = TestNodes.startNode(listener);
        File myNotes = TestNodes.fileOf(downloader, "notes.txt");
        Files.write(myNotes.toPath(), mine);
        // Answers one block, then hangs up, so the download fails
        new FakeSeeder(downloader, source, 1).connect();

        TestNodes.searchAndDownload(downloader, listener, "notes", 1);

        assertTrue(listener.awaitFinished(DOWNLOAD_TIMEOUT_MS), "download hung after the drop");
        assertFalse(listener.downloadSucceeded());
        TestNodes.waitUntil(() -> TestNodes.partFiles(downloader).length == 0, 5_000);
        assertArrayEquals(mine, Files.readAllBytes(myNotes.toPath()));
        assertEquals(0, TestNodes.partFiles(downloader).length);
    }
}
