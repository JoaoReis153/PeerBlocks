package Services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Core.Node;
import Support.FakeSeeder;
import Support.TestListener;
import Support.TestNodes;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Runs real nodes on this machine and downloads files between them. */
@Timeout(60)
class DownloadIntegrationTest {

    private static final String FILE_NAME = "sample-song.bin";
    private static final long DOWNLOAD_TIMEOUT_MS = 30_000;

    @AfterEach
    void cleanUp() {
        TestNodes.cleanup();
    }

    @Test
    void downloadsAFileFromOneSeeder() throws Exception {
        byte[] content = TestNodes.randomBytes(3_000_000, 1);
        Node seeder = TestNodes.startSeeder(FILE_NAME, content);
        TestListener listener = new TestListener();
        Node downloader = TestNodes.startNode(listener);

        TestNodes.connect(seeder, downloader);
        TestNodes.searchAndDownload(downloader, listener, FILE_NAME, 1);

        assertTrue(listener.awaitFinished(DOWNLOAD_TIMEOUT_MS), "download did not finish");
        assertTrue(listener.downloadSucceeded());
        assertArrayEquals(
                content, Files.readAllBytes(TestNodes.fileOf(downloader, FILE_NAME).toPath()));
    }

    @Test
    void downloadsAFileFromSeveralSeeders() throws Exception {
        byte[] content = TestNodes.randomBytes(8_000_000, 2);
        TestListener listener = new TestListener();
        Node downloader = TestNodes.startNode(listener);
        List<Node> seeders = new ArrayList<>();
        for (int i = 0; i < 3; i++) seeders.add(TestNodes.startSeeder(FILE_NAME, content));

        for (Node seeder : seeders) TestNodes.connect(seeder, downloader);
        TestNodes.searchAndDownload(downloader, listener, FILE_NAME, seeders.size());

        assertTrue(listener.awaitFinished(DOWNLOAD_TIMEOUT_MS), "download did not finish");
        assertTrue(listener.downloadSucceeded());
        assertArrayEquals(
                content, Files.readAllBytes(TestNodes.fileOf(downloader, FILE_NAME).toPath()));
    }

    @Test
    void discardsADownloadWhoseContentDoesNotMatchItsHash() throws Exception {
        byte[] content = TestNodes.randomBytes(2_000_000, 3);
        Node seeder = TestNodes.startSeeder(FILE_NAME, content);
        // The seeder already advertises the old hash, so it now serves a corrupted file
        byte[] corrupted = content.clone();
        for (int i = 0; i < corrupted.length; i++) corrupted[i] ^= 0x5A;
        Files.write(TestNodes.fileOf(seeder, FILE_NAME).toPath(), corrupted);
        TestListener listener = new TestListener();
        Node downloader = TestNodes.startNode(listener);

        TestNodes.connect(seeder, downloader);
        TestNodes.searchAndDownload(downloader, listener, FILE_NAME, 1);

        assertTrue(listener.awaitFinished(DOWNLOAD_TIMEOUT_MS), "download did not finish");
        assertFalse(listener.downloadSucceeded(), "a corrupted file must not count as downloaded");
        File downloaded = TestNodes.fileOf(downloader, FILE_NAME);
        TestNodes.waitUntil(() -> !downloaded.exists(), 5_000);
        assertFalse(downloaded.exists(), "the corrupted file must be deleted");
    }

    @Test
    void finishesWhenASeederDropsInTheMiddleOfTheDownload() throws Exception {
        byte[] content = TestNodes.randomBytes(16_000_000, 4);
        Node seeder = TestNodes.startSeeder(FILE_NAME, content);
        File copy = TestNodes.sourceFile(FILE_NAME);
        Files.write(copy.toPath(), content);
        TestListener listener = new TestListener();
        Node downloader = TestNodes.startNode(listener);
        // This seeder answers 2 blocks, then hangs up while holding a third
        FakeSeeder flaky = new FakeSeeder(downloader, copy, 2);

        TestNodes.connect(seeder, downloader);
        flaky.connect();
        TestNodes.searchAndDownload(downloader, listener, FILE_NAME, 2);

        assertTrue(listener.awaitFinished(DOWNLOAD_TIMEOUT_MS), "download hung after the drop");
        assertTrue(listener.downloadSucceeded(), "the other seeder should finish the file");
        assertTrue(flaky.blockRequestsSeen() >= 1, "the flaky seeder was never asked for a block");
        assertArrayEquals(
                content, Files.readAllBytes(TestNodes.fileOf(downloader, FILE_NAME).toPath()));
    }

    @Test
    void failsInsteadOfHangingWhenTheOnlySeederDrops() throws Exception {
        byte[] content = TestNodes.randomBytes(8_000_000, 5);
        File source = TestNodes.sourceFile(FILE_NAME);
        Files.write(source.toPath(), content);
        TestListener listener = new TestListener();
        Node downloader = TestNodes.startNode(listener);
        FakeSeeder flaky = new FakeSeeder(downloader, source, 1);

        try {
            flaky.connect();
            TestNodes.searchAndDownload(downloader, listener, FILE_NAME, 1);

            assertTrue(listener.awaitFinished(DOWNLOAD_TIMEOUT_MS), "download hung after the drop");
            assertFalse(listener.downloadSucceeded());
            File downloaded = TestNodes.fileOf(downloader, FILE_NAME);
            TestNodes.waitUntil(() -> !downloaded.exists(), 5_000);
            assertFalse(downloaded.exists(), "the partial file must be deleted");
        } finally {
            source.delete();
        }
    }
}
