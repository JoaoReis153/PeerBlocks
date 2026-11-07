package dev.joaoreis.peerblocks.tools;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.core.NodeListener;
import dev.joaoreis.peerblocks.core.Utils;
import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import dev.joaoreis.peerblocks.gui.GUI;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Measures download speed against the number of seeders. Transfer and verification are timed apart.
 */
public class Benchmark {

    record Timing(long transferMs, long verifyMs) {
        long totalMs() {
            return transferMs + verifyMs;
        }
    }

    // ── Configuration ──────────────────────────────────────────────────────────

    private final int maxSeeds;
    private final int iterations;
    private final long fileSizeBytes;
    private final String csvOutputPath;

    /** How long to wait for TCP connections to settle after connecting nodes. */
    private static final long CONNECT_SETTLE_MS = 400;

    /**
     * How long to wait after broadcasting the search before triggering the download. Must be long
     * enough for all seeders to respond.
     */
    private static final long SEARCH_SETTLE_MS = 1_500;

    /** Maximum time to wait for a download to finish before declaring a timeout. */
    private static final long DOWNLOAD_TIMEOUT_MS = 120_000;

    private static final String BENCH_FILE_NAME = "benchmark_file";

    private static final int FIRST_ID = 100;
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(FIRST_ID);

    // ── Construction / entry-point ─────────────────────────────────────────────

    public Benchmark(int maxSeeds, int iterations, long fileSizeBytes, String csvOutputPath) {
        this.maxSeeds = maxSeeds;
        this.iterations = iterations;
        this.fileSizeBytes = fileSizeBytes;
        this.csvOutputPath = csvOutputPath;
    }

    // Reads sizes like "512KB", "100MB", "1GB", or a plain number of bytes
    static long parseSize(String text) {
        String size = text.trim().toUpperCase();
        long unit = 1;
        if (size.endsWith("KB")) unit = 1024;
        else if (size.endsWith("MB")) unit = 1024 * 1024;
        else if (size.endsWith("GB")) unit = 1024 * 1024 * 1024;
        if (unit > 1) size = size.substring(0, size.length() - 2);
        return Long.parseLong(size.trim()) * unit;
    }

    public static void main(String[] args) throws Exception {
        // Delete every node folder this benchmark used when it ends, even on Ctrl+C
        Runtime.getRuntime()
                .addShutdownHook(new Thread(Benchmark::clearUsedNodeFolders, "bench-cleanup"));

        int maxSeeds = 10;
        int iterations = 3;
        long fileSizeBytes = 1024L * 1024 * 100; // 100 MB default
        String csvPath = "benchmark/benchmark_results.csv";

        try {
            if (args.length >= 1) maxSeeds = Integer.parseInt(args[0]);
            if (args.length >= 2) iterations = Integer.parseInt(args[1]);
            if (args.length >= 3) fileSizeBytes = parseSize(args[2]);
            if (args.length >= 4) csvPath = args[3];
        } catch (NumberFormatException e) {
            System.err.println(
                    "Usage: Benchmark [maxSeeds] [iterations] [fileSize, e.g. 100MB] [csvPath]");
            System.exit(1);
        }

        if (fileSizeBytes > Integer.MAX_VALUE) {
            System.err.println("fileSize must be under 2GB");
            System.exit(1);
        }

        // Use loopback by default, so a VPN or Wi-Fi interface doesn't cap the speed
        System.setProperty(
                Utils.HOST_PROPERTY, System.getProperty(Utils.HOST_PROPERTY, "127.0.0.1"));

        System.out.printf(
                "Benchmark  maxSeeds=%d  iterations=%d  fileSize=%,d bytes  host=%s  csv=%s%n",
                maxSeeds,
                iterations,
                fileSizeBytes,
                System.getProperty(Utils.HOST_PROPERTY),
                csvPath);

        new Benchmark(maxSeeds, iterations, fileSizeBytes, csvPath).run();
        System.exit(0); // stop lingering non-daemon node threads
    }

    // ── Main loop ──────────────────────────────────────────────────────────────

    public void run() throws Exception {
        byte[] fileContent = generateTestFile(fileSizeBytes);

        // CSV rows
        List<String[]> rows = new ArrayList<>();
        rows.add(
                new String[] {
                    "seeds",
                    "iteration",
                    "file_size_bytes",
                    "transfer_ms",
                    "verify_ms",
                    "total_ms",
                    "transfer_mb_per_sec",
                    "total_mb_per_sec"
                });

        // Per-seed results for the summary table
        Map<Integer, List<Timing>> timingsPerSeed = new LinkedHashMap<>();

        // Warm-up run, not recorded
        System.out.print("Warm-up run (not recorded)  ");
        Timing warmUp = runSingleBenchmark(1, fileContent);
        System.out.println(warmUp == null ? "failed" : "done");

        for (int seeds = 1; seeds <= maxSeeds; seeds++) {
            System.out.printf("%n=== %d seed(s) ===%n", seeds);
            List<Timing> timings = new ArrayList<>();

            for (int iter = 1; iter <= iterations; iter++) {
                System.out.printf("  [%d/%d]  ", iter, iterations);
                String[] timingColumns;

                try {
                    Timing timing = runSingleBenchmark(seeds, fileContent);
                    if (timing != null) {
                        timingColumns = timingColumns(timing);
                        System.out.printf(
                                "transfer %d ms (%.2f MB/s)  +  verify %d ms  =  %d ms%n",
                                timing.transferMs(),
                                megabytesPerSecond(timing.transferMs()),
                                timing.verifyMs(),
                                timing.totalMs());
                        timings.add(timing);
                    } else {
                        timingColumns = filled("TIMEOUT");
                        System.out.println("TIMEOUT");
                    }
                } catch (Exception e) {
                    timingColumns = filled("ERROR");
                    System.out.println("ERROR: " + e.getMessage());
                }

                List<String> row = new ArrayList<>();
                row.add(String.valueOf(seeds));
                row.add(String.valueOf(iter));
                row.add(String.valueOf(fileSizeBytes));
                row.addAll(Arrays.asList(timingColumns));
                rows.add(row.toArray(new String[0]));

                Thread.sleep(300); // brief cooldown between runs
            }

            timingsPerSeed.put(seeds, timings);
        }

        writeCsv(rows, csvOutputPath);
        System.out.println("\nResults written to: " + new File(csvOutputPath).getAbsolutePath());

        printSummaryTable(timingsPerSeed);
    }

    private double megabytesPerSecond(long durationMs) {
        return (fileSizeBytes / 1_048_576.0) / (Math.max(1, durationMs) / 1_000.0);
    }

    private String[] timingColumns(Timing t) {
        return new String[] {
            String.valueOf(t.transferMs()),
            String.valueOf(t.verifyMs()),
            String.valueOf(t.totalMs()),
            String.format(Locale.ROOT, "%.4f", megabytesPerSecond(t.transferMs())),
            String.format(Locale.ROOT, "%.4f", megabytesPerSecond(t.totalMs()))
        };
    }

    private static String[] filled(String value) {
        String[] columns = new String[5];
        Arrays.fill(columns, value);
        return columns;
    }

    // ── Single benchmark run ───────────────────────────────────────────────────

    /**
     * Spins up {@code seedCount} seeder nodes pre-loaded with the test file, plus one downloader
     * node with a {@link BenchmarkNodeListener}. Connects them, broadcasts a search, waits for
     * results and triggers the download.
     *
     * @return the transfer and verification times, or null on timeout / failure
     */
    private Timing runSingleBenchmark(int seedCount, byte[] fileContent) throws Exception {

        // Allocate unique IDs for this run (avoids ServerSocket port conflicts
        // with still-running nodes from previous iterations)
        int downloaderId = ID_COUNTER.getAndIncrement();
        List<Integer> seederIds = new ArrayList<>();
        for (int i = 0; i < seedCount; i++) {
            seederIds.add(ID_COUNTER.getAndIncrement());
        }

        // 1 — Create seeder nodes and plant the test file in each
        // Files left by earlier runs would be hashed and offered instead of this run's file
        clearNodeFolder(downloaderId);
        for (int id : seederIds) clearNodeFolder(id);

        List<GUI> seeders = new ArrayList<>();
        for (int id : seederIds) {
            GUI gui = new GUI(id, false);
            writeFile(Config.WORK_FOLDER + id + File.separator + BENCH_FILE_NAME, fileContent);
            gui.getNode().files().refresh(); // hash the new file before timing starts
            gui.open();
            seeders.add(gui);
        }
        Thread.sleep(200); // let seeder servers start and hash-loading settle

        Node downloader = null;
        try {
            // 2 — Create downloader node with a lightweight BenchmarkNodeListener
            BenchmarkNodeListener benchListener = new BenchmarkNodeListener();
            downloader = new Node(downloaderId, benchListener);
            benchListener.setNode(downloader);

            Thread serverThread =
                    new Thread(downloader::startServing, "bench-server-" + downloaderId);
            serverThread.setDaemon(true);
            serverThread.start();
            Thread.sleep(200); // let downloader server start

            // 3 — Connect every seeder → downloader
            for (GUI seeder : seeders) {
                seeder.getNode()
                        .connectToNode(
                                downloader.getAddress().getHostAddress(), downloader.getPort());
            }
            Thread.sleep(CONNECT_SETTLE_MS);

            // 4 — Broadcast search from downloader → seeders reply with FileSearchResult[]
            downloader.broadcastWordSearchMessageRequest(BENCH_FILE_NAME);
            Thread.sleep(SEARCH_SETTLE_MS);

            // 5 — Trigger download with the results we collected
            if (benchListener.hasNoResults()) {
                System.out.print("[no search results — skipped]  ");
                return null;
            }
            benchListener.triggerDownload();

            // 6 — Wait for the download to finish
            return benchListener.awaitCompletion(DOWNLOAD_TIMEOUT_MS);
        } finally {
            // Stop this run's nodes and delete their folders, so the next run starts clean
            if (downloader != null) downloader.close();
            for (GUI seeder : seeders) seeder.getNode().close();
            clearNodeFolder(downloaderId);
            for (int id : seederIds) clearNodeFolder(id);
        }
    }

    // ── BenchmarkNodeListener ──────────────────────────────────────────────────

    /**
     * Lightweight {@link NodeListener} used by the downloader node during the benchmark. It:
     *
     * <ul>
     *   <li>auto-accepts all incoming connection requests,
     *   <li>accumulates {@link FileSearchResult} objects from seeder replies,
     *   <li>groups them by hash and calls {@code node.downloadFiles()} on demand,
     *   <li>records the transfer and verification times and releases the waiting thread via a
     *       {@link CountDownLatch}.
     * </ul>
     */
    static class BenchmarkNodeListener implements NodeListener {

        private Node node;
        private final List<FileSearchResult> collected =
                Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile long totalMs = -1;
        private volatile long startNanos;
        private volatile long transferEndNanos;
        private volatile int totalBlocks;

        void setNode(Node node) {
            this.node = node;
        }

        boolean hasNoResults() {
            return collected.isEmpty();
        }

        void triggerDownload() {
            if (node == null || collected.isEmpty()) return;

            Map<String, List<FileSearchResult>> byHash = new LinkedHashMap<>();
            synchronized (collected) {
                for (FileSearchResult r : collected) {
                    byHash.computeIfAbsent(Arrays.toString(r.getHash()), k -> new ArrayList<>())
                            .add(r);
                }
            }
            node.downloadFiles(new ArrayList<>(byHash.values()));
        }

        /** Returns null if the download failed or timed out. */
        Timing awaitCompletion(long timeoutMs) throws InterruptedException {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (totalMs <= 0 || transferEndNanos == 0) return null;

            long transferMs = (transferEndNanos - startNanos) / 1_000_000;
            // What is left after the last block is the verification
            long verifyMs = Math.max(0, totalMs - transferMs);
            return new Timing(transferMs, verifyMs);
        }

        // NodeListener ──────────────────────────────────────────────────────────

        @Override
        public boolean confirmIncomingConnection(String address, int port) {
            return true; // always accept in benchmark mode
        }

        @Override
        public void loadListModel(FileSearchResult[] results) {
            Collections.addAll(collected, results);
        }

        @Override
        public void reloadListModel() {}

        @Override
        public void startDownloadProgress(byte[] hash, String fileName, int totalBlocks) {
            this.totalBlocks = totalBlocks;
            this.startNanos = System.nanoTime();
        }

        @Override
        public void updateDownloadProgress(byte[] hash, int completedBlocks) {
            if (completedBlocks == totalBlocks && transferEndNanos == 0) {
                transferEndNanos = System.nanoTime();
            }
        }

        @Override
        public void finishDownloadProgress(byte[] hash) {
            // Download was aborted or failed — release the waiting thread
            latch.countDown();
        }

        @Override
        public void completeDownloadProgress(
                byte[] hash, long durationInMs, Map<String, Integer> nodesNBlocks) {
            this.totalMs = durationInMs;
            latch.countDown();
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    /** Generates a deterministic pseudo-random byte array of the requested size. */
    private static byte[] generateTestFile(long size) {
        byte[] content = new byte[(int) size];
        new Random(0xBEEF).nextBytes(content);
        return content;
    }

    private static void clearUsedNodeFolders() {
        for (int id = FIRST_ID; id < ID_COUNTER.get(); id++) {
            try {
                clearNodeFolder(id);
            } catch (IOException e) {
                System.err.println("Could not delete " + Config.WORK_FOLDER + id);
            }
        }
    }

    // Deletes a node's folder and everything in it
    private static void clearNodeFolder(int id) throws IOException {
        Path folder = Path.of(Config.WORK_FOLDER + id);
        if (!Files.exists(folder)) return;
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void writeFile(String path, byte[] content) throws IOException {
        File f = new File(path);
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content);
        }
    }

    private static void writeCsv(List<String[]> rows, String path) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                StringJoiner sj = new StringJoiner(",");
                for (String field : row) sj.add(field);
                pw.println(sj);
            }
        }
    }

    private void printSummaryTable(Map<Integer, List<Timing>> timingsPerSeed) {
        System.out.println();
        System.out.println("┌───────┬───────────────────────────┬───────────────┬─────────────┐");
        System.out.println("│ Seeds │  Transfer avg (min–max)   │ Transfer MB/s │  Verify avg │");
        System.out.println("├───────┼───────────────────────────┼───────────────┼─────────────┤");
        for (Map.Entry<Integer, List<Timing>> e : timingsPerSeed.entrySet()) {
            List<Timing> t = e.getValue();
            if (t.isEmpty()) {
                System.out.printf(
                        "│ %-5d │ %25s │ %13s │ %11s │%n", e.getKey(), "n/a", "n/a", "n/a");
                continue;
            }
            LongSummaryStatistics transfer =
                    t.stream().mapToLong(Timing::transferMs).summaryStatistics();
            double verifyAvg = t.stream().mapToLong(Timing::verifyMs).average().getAsDouble();
            System.out.printf(
                    "│ %-5d │ %25s │ %13.1f │ %8.0f ms │%n",
                    e.getKey(),
                    String.format(
                            "%.0f ms (%d–%d)",
                            transfer.getAverage(), transfer.getMin(), transfer.getMax()),
                    megabytesPerSecond(Math.round(transfer.getAverage())),
                    verifyAvg);
        }
        System.out.println("└───────┴───────────────────────────┴───────────────┴─────────────┘");
    }
}
