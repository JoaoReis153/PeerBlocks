package dev.joaoreis.peerblocks.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.joaoreis.peerblocks.support.FakeSeeder;
import dev.joaoreis.peerblocks.support.TestListener;
import dev.joaoreis.peerblocks.support.TestNodes;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class NodeCloseTest {

    @AfterEach
    void cleanUp() {
        TestNodes.cleanup();
    }

    // Threads that belong to the node: its pools and connections, plus the test's server thread
    private static List<String> liveThreadsOf(Node node) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(
                        name ->
                                name.startsWith("node-" + node.getPort() + "-")
                                        || name.equals("test-node-" + node.getId())
                                        || name.endsWith(node.getFolder().getName()))
                .toList();
    }

    @Test
    void freesThePortAndStopsItsThreads() throws Exception {
        Node node = TestNodes.startNode(new TestListener());
        Node other = TestNodes.startNode(new TestListener());
        TestNodes.connect(other, node);
        TestNodes.waitUntil(() -> node.getPeers().size() == 1, 5_000);
        assertFalse(liveThreadsOf(node).isEmpty());

        node.close();

        TestNodes.waitUntil(() -> liveThreadsOf(node).isEmpty(), 5_000);
        assertEquals(List.of(), liveThreadsOf(node));
        TestNodes.waitUntil(() -> other.getPeers().isEmpty(), 5_000);
        assertTrue(other.getPeers().isEmpty(), "the other node should see the connection close");
        try (ServerSocket reuse = new ServerSocket(node.getPort())) {
            assertTrue(reuse.isBound(), "the port should be free again");
        }
    }

    @Test
    void stopsADownloadAndDeletesItsPartFile() throws Exception {
        File source = TestNodes.sourceFile("big.bin");
        Files.write(source.toPath(), TestNodes.randomBytes(8_000_000, 20));
        TestListener listener = new TestListener();
        Node downloader = TestNodes.startNode(listener);
        // Answers one block, then goes quiet, so the download stays half done
        new FakeSeeder(downloader, source, "big.bin", 1, false).connect();
        TestNodes.searchAndDownload(downloader, listener, "big", 1);
        TestNodes.waitUntil(() -> TestNodes.partFiles(downloader).length > 0, 10_000);

        long start = System.currentTimeMillis();
        downloader.close();
        long tookMs = System.currentTimeMillis() - start;

        assertTrue(listener.awaitFinished(5_000), "the download should report that it stopped");
        assertFalse(listener.downloadSucceeded());
        assertEquals(0, TestNodes.partFiles(downloader).length, "the .part file must be deleted");
        assertTrue(tookMs < 5_000, "close() took " + tookMs + " ms");
    }

    @Test
    void canBeClosedTwiceAndIgnoresLaterRequests() throws Exception {
        Node node = TestNodes.startNode(new TestListener());
        Node other = TestNodes.startNode(new TestListener());

        node.close();

        assertTrue(node.isClosed());
        assertDoesNotThrow(node::close);
        assertDoesNotThrow(() -> TestNodes.connect(node, other));
        assertDoesNotThrow(() -> node.downloadFiles(List.of()));
        assertTrue(node.getPeers().isEmpty());
    }
}
