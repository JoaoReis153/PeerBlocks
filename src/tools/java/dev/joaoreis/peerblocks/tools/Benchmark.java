package dev.joaoreis.peerblocks.tools;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.core.NodeListener;
import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import dev.joaoreis.peerblocks.gui.GUI;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Benchmark {

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

    private static final AtomicInteger ID_COUNTER = new AtomicInteger(100);

    // ── Construction / entry-point ─────────────────────────────────────────────

    public Benchmark(int maxSeeds, int iterations, long fileSizeBytes, String csvOutputPath) {
        this.maxSeeds = maxSeeds;
        this.iterations = iterations;
        this.fileSizeBytes = fileSizeBytes;
        this.csvOutputPath = csvOutputPath;
    }

    public static void main(String[] args) throws Exception {
        int maxSeeds = 10;
        int iterations = 3;
        long fileSizeBytes = 1024L * 1024 * 100; // 100 MB default
        String csvPath = "benchmark/benchmark_results.csv";

        try {
            if (args.length >= 1) maxSeeds = Integer.parseInt(args[0]);
            if (args.length >= 2) iterations = Integer.parseInt(args[1]);
            if (args.length >= 3) fileSizeBytes = Long.parseLong(args[2]);
            if (args.length >= 4) csvPath = args[3];
        } catch (NumberFormatException e) {
            System.err.println(
                    "Usage: Benchmark [maxSeeds] [iterations] [fileSizeBytes] [csvPath]");
            System.exit(1);
        }

        if (fileSizeBytes > Integer.MAX_VALUE) {
            System.err.println("fileSizeBytes must be <= " + Integer.MAX_VALUE);
            System.exit(1);
        }

        System.out.printf(
                "Benchmark  maxSeeds=%d  iterations=%d  fileSize=%,d bytes  csv=%s%n",
                maxSeeds, iterations, fileSizeBytes, csvPath);

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
                    "seeds", "iteration", "file_size_bytes", "duration_ms", "throughput_mb_per_sec"
                });

        // Per-seed aggregates for the summary table
        Map<Integer, List<Long>> durationsPerSeed = new LinkedHashMap<>();

        for (int seeds = 1; seeds <= maxSeeds; seeds++) {
            System.out.printf("%n=== %d seed(s) ===%n", seeds);
            List<Long> durations = new ArrayList<>();

            for (int iter = 1; iter <= iterations; iter++) {
                System.out.printf("  [%d/%d]  ", iter, iterations);
                String durationStr, throughputStr;

                try {
                    long duration = runSingleBenchmark(seeds, fileContent);
                    if (duration > 0) {
                        double mbps = (fileSizeBytes / 1_048_576.0) / (duration / 1_000.0);
                        durationStr = String.valueOf(duration);
                        throughputStr = String.format("%.4f", mbps);
                        System.out.printf("%d ms  (%.2f MB/s)%n", duration, mbps);
                        durations.add(duration);
                    } else {
                        durationStr = throughputStr = "TIMEOUT";
                        System.out.println("TIMEOUT");
                    }
                } catch (Exception e) {
                    durationStr = throughputStr = "ERROR";
                    System.out.println("ERROR: " + e.getMessage());
                }

                rows.add(
                        new String[] {
                            String.valueOf(seeds),
                            String.valueOf(iter),
                            String.valueOf(fileSizeBytes),
                            durationStr,
                            throughputStr
                        });

                Thread.sleep(300); // brief cooldown between runs
            }

            durationsPerSeed.put(seeds, durations);
        }

        writeCsv(rows, csvOutputPath);
        System.out.println("\nResults written to: " + new File(csvOutputPath).getAbsolutePath());

        printSummaryTable(durationsPerSeed);
    }

    // ── Single benchmark run ───────────────────────────────────────────────────

    /**
     * Spins up {@code seedCount} seeder nodes pre-loaded with the test file, plus one downloader
     * node with a {@link BenchmarkNodeListener}. Connects them, broadcasts a search, waits for
     * results, triggers the download, and returns the duration in milliseconds as measured inside
     * {@link dev.joaoreis.peerblocks.services.DownloadTasksManager}.
     *
     * @return download duration in ms, or -1 on timeout / failure
     */
    private long runSingleBenchmark(int seedCount, byte[] fileContent) throws Exception {

        // Allocate unique IDs for this run (avoids ServerSocket port conflicts
        // with still-running nodes from previous iterations)
        int downloaderId = ID_COUNTER.getAndIncrement();
        List<Integer> seederIds = new ArrayList<>();
        for (int i = 0; i < seedCount; i++) {
            seederIds.add(ID_COUNTER.getAndIncrement());
        }

        // 1 — Create seeder nodes and plant the test file in each
        List<GUI> seeders = new ArrayList<>();
        for (int id : seederIds) {
            GUI gui = new GUI(id, false);
            writeFile(Config.WORK_FOLDER + id + File.separator + BENCH_FILE_NAME, fileContent);
            gui.getNode().loadHashes(); // pick up the newly written file
            gui.open();
            seeders.add(gui);
        }
        Thread.sleep(200); // let seeder servers start and hash-loading settle

        // 2 — Create downloader node with a lightweight BenchmarkNodeListener
        BenchmarkNodeListener benchListener = new BenchmarkNodeListener();
        Node downloader = new Node(downloaderId, benchListener);
        benchListener.setNode(downloader);

        Thread serverThread = new Thread(downloader::startServing, "bench-server-" + downloaderId);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(200); // let downloader server start

        // 3 — Connect every seeder → downloader
        for (GUI seeder : seeders) {
            seeder.getNode()
                    .connectToNode(downloader.getAddress().getHostAddress(), downloader.getPort());
        }
        Thread.sleep(CONNECT_SETTLE_MS);

        // 4 — Broadcast search from downloader → seeders reply with FileSearchResult[]
        downloader.broadcastWordSearchMessageRequest(BENCH_FILE_NAME);
        Thread.sleep(SEARCH_SETTLE_MS);

        // 5 — Trigger download with the results we collected
        if (benchListener.hasNoResults()) {
            System.out.print("[no search results — skipped]  ");
            return -1;
        }
        benchListener.triggerDownload();

        // 6 — Wait; duration is measured precisely inside DownloadTasksManager
        long duration = benchListener.awaitCompletion(DOWNLOAD_TIMEOUT_MS);

        // 7 — Delete the downloaded file so the next run starts clean
        new File(Config.WORK_FOLDER + downloaderId + File.separator + BENCH_FILE_NAME).delete();

        return duration;
    }

    // ── BenchmarkNodeListener ──────────────────────────────────────────────────

    /**
     * Lightweight {@link NodeListener} used by the downloader node during the benchmark. It:
     *
     * <ul>
     *   <li>auto-accepts all incoming connection requests,
     *   <li>accumulates {@link FileSearchResult} objects from seeder replies,
     *   <li>groups them by hash and calls {@code node.downloadFiles()} on demand,
     *   <li>records the precise duration reported by DownloadTasksManager and releases the waiting
     *       thread via a {@link CountDownLatch}.
     * </ul>
     */
    static class BenchmarkNodeListener implements NodeListener {

        private Node node;
        private final List<FileSearchResult> collected =
                Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile long durationMs = -1;

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

        long awaitCompletion(long timeoutMs) throws InterruptedException {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            return durationMs;
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
        public void startDownloadProgress(byte[] hash, String fileName, int totalBlocks) {}

        @Override
        public void updateDownloadProgress(byte[] hash, int completedBlocks) {}

        @Override
        public void finishDownloadProgress(byte[] hash) {
            // Download was aborted or failed — release the waiting thread
            latch.countDown();
        }

        @Override
        public void completeDownloadProgress(
                byte[] hash, long durationInMs, Map<String, Integer> nodesNBlocks) {
            this.durationMs = durationInMs;
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

    private static void printSummaryTable(Map<Integer, List<Long>> durationsPerSeed) {
        System.out.println();
        System.out.println("┌────────┬──────────────┬──────────────┬──────────────┐");
        System.out.println("│ Seeds  │   Avg (ms)   │   Min (ms)   │   Max (ms)   │");
        System.out.println("├────────┼──────────────┼──────────────┼──────────────┤");
        for (Map.Entry<Integer, List<Long>> e : durationsPerSeed.entrySet()) {
            List<Long> d = e.getValue();
            if (d.isEmpty()) {
                System.out.printf(
                        "│ %-6d │ %12s │ %12s │ %12s │%n", e.getKey(), "n/a", "n/a", "n/a");
            } else {
                long min = d.stream().mapToLong(Long::longValue).min().getAsLong();
                long max = d.stream().mapToLong(Long::longValue).max().getAsLong();
                double avg = d.stream().mapToLong(Long::longValue).average().getAsDouble();
                System.out.printf("│ %-6d │ %12.1f │ %12d │ %12d │%n", e.getKey(), avg, min, max);
            }
        }
        System.out.println("└────────┴──────────────┴──────────────┴──────────────┘");
    }
}
