package dev.joaoreis.peerblocks.support;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.core.NodeListener;
import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Helpers for starting real nodes on this machine and cleaning up after a test. */
public final class TestNodes {

    private TestNodes() {}

    private static final File WORK_ROOT = new File(Config.WORK_FOLDER).getParentFile();

    // Each node listens on 8080 + id, so every node in the test run gets its own id
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1200);
    private static final List<Integer> USED_IDS = new ArrayList<>();

    public static synchronized int reserveId() {
        int id = NEXT_ID.getAndIncrement();
        if (id > Config.MAX_NODE_ID) throw new IllegalStateException("Out of node ids");
        USED_IDS.add(id);
        return id;
    }

    /** Starts a node that is already accepting connections. */
    public static Node startNode(NodeListener listener) throws InterruptedException {
        Node node = new Node(reserveId(), listener);
        Thread server = new Thread(node::startServing, "test-node-" + node.getId());
        server.setDaemon(true);
        server.start();
        Thread.sleep(200); // let the server socket open
        return node;
    }

    /** Starts a node that already holds the given file and has hashed it. */
    public static Node startSeeder(String fileName, byte[] content)
            throws IOException, InterruptedException {
        Node seeder = startNode(new TestListener());
        File file = new File(seeder.getFolder(), fileName);
        Files.write(file.toPath(), content);
        seeder.getHash(file.getAbsolutePath()); // hashes now and caches the result
        return seeder;
    }

    /** Opens a connection from one node to another, the way the GUI's Connect button does. */
    public static void connect(Node from, Node to) {
        from.connectToNode(to.getAddress().getHostAddress(), to.getPort());
    }

    /**
     * Searches from the downloader and starts downloading once {@code expectedSeeders} peers have
     * answered.
     */
    public static void searchAndDownload(
            Node downloader, TestListener listener, String fileName, int expectedSeeders)
            throws InterruptedException {
        waitUntil(() -> downloader.getPeers().size() >= expectedSeeders, 10_000);
        downloader.broadcastWordSearchMessageRequest(fileName);
        waitUntil(() -> listener.getResults().size() >= expectedSeeders, 10_000);

        Map<String, List<FileSearchResult>> byHash = new LinkedHashMap<>();
        for (FileSearchResult result : listener.getResults()) {
            byHash.computeIfAbsent(Arrays.toString(result.getHash()), k -> new ArrayList<>())
                    .add(result);
        }
        downloader.downloadFiles(new ArrayList<>(byHash.values()));
    }

    public static void waitUntil(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    /** A folder outside every node's folder, for files a fake seeder serves. */
    public static File sourceFile(String fileName) {
        File folder = new File(WORK_ROOT, "sources-" + reserveId());
        folder.mkdirs();
        return new File(folder, fileName);
    }

    public static File fileOf(Node node, String fileName) {
        return new File(node.getFolder(), fileName);
    }

    public static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    /** Deletes the working folders of every node started so far. */
    public static synchronized void cleanup() {
        for (int id : USED_IDS) deleteRecursively(new File(Config.WORK_FOLDER + id));
        USED_IDS.clear();
        File[] sources = WORK_ROOT.listFiles((d, n) -> n.startsWith("sources-"));
        if (sources != null) for (File source : sources) deleteRecursively(source);
        WORK_ROOT.delete(); // only removed if empty
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
