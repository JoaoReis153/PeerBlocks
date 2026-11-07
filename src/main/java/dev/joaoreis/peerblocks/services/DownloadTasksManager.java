package dev.joaoreis.peerblocks.services;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.core.Utils;
import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import dev.joaoreis.peerblocks.messaging.FileBlockAnswerMessage;
import dev.joaoreis.peerblocks.messaging.FileBlockRequestMessage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DownloadTasksManager extends Thread {

    private static final Logger log = LoggerFactory.getLogger(DownloadTasksManager.class);

    private Node node;
    private FileSearchResult fileInfo;
    private List<FileSearchResult> requests;
    private ExecutorService threadPool;
    private List<FileBlockRequestMessage> requestList;
    private Set<FileBlockRequestMessage> completedRequests;
    private final Set<FileBlockRequestMessage> blocksBeingWritten = new HashSet<>();
    private final File partFile;
    private FileChannel partChannel;
    private File savedFile;
    private Map<String, Integer> numberOfDownloadsForPeer;
    private ArrayList<SubNode> peersWithFile;
    private int totalBlocks;
    private int completedBlocks;
    private int activeAssistants;
    private final Set<SubNode> disconnectedPeers =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private String failureReason;
    private boolean downloadSuccessful = false;

    public DownloadTasksManager(Node node, List<FileSearchResult> requests) {
        this.node = node;
        this.requests = requests;
        this.fileInfo = requests.get(0);

        log.info(
                "Download task manager created for file "
                        + HexFormat.of().formatHex(fileInfo.getHash()));
        this.completedRequests = new HashSet<FileBlockRequestMessage>();
        this.numberOfDownloadsForPeer = new HashMap<>();
        this.requestList =
                FileBlockRequestMessage.createBlockList(fileInfo.getHash(), fileInfo.getFileSize());
        this.totalBlocks = requestList.size();
        this.completedBlocks = 0;
        this.peersWithFile = getNodesWithFile();
        this.partFile =
                new File(
                        node.getFolder(),
                        "."
                                + HexFormat.of().formatHex(fileInfo.getHash())
                                + Config.PARTIAL_DOWNLOAD_SUFFIX);

        // In case there are no nodes with the file, return
        if (peersWithFile.isEmpty()) {
            log.info("Couldn't find peers with the file");
            return;
        }
        this.threadPool =
                Executors.newFixedThreadPool(
                        peersWithFile.size() * Config.REQUESTS_IN_FLIGHT_PER_PEER,
                        node.namedThreads("fetch"));
        log.info(requestList.size() + " blocks to process");
    }

    @Override
    public void run() {
        try {
            node.getListener()
                    .startDownloadProgress(fileInfo.getHash(), fileInfo.getFileName(), totalBlocks);

            String fileName = safeFileName(fileInfo.getFileName());
            if (fileName == null) {
                throw new IOException("Invalid file name: " + fileInfo.getFileName());
            }

            long start = System.currentTimeMillis();
            processDownload();
            verifyDownloadedFile();
            savedFile = saveUnderFreeName(fileName);
            downloadSuccessful = true;
            long duration = System.currentTimeMillis() - start;
            long safeDurationMs = Math.max(1L, duration);
            long bytesPerSecond = (fileInfo.getFileSize() * 1000L) / safeDurationMs;

            node.getListener()
                    .completeDownloadProgress(
                            fileInfo.getHash(), duration, new HashMap<>(numberOfDownloadsForPeer));
            log.info(
                    "Download finished for file "
                            + fileInfo.getFileName()
                            + " ["
                            + fileInfo.getFileSize()
                            + "] at a rate of "
                            + bytesPerSecond
                            + " bytes/s");
            node.getListener().reloadListModel();
            node.files().put(savedFile, fileInfo.getHash());
        } catch (Exception e) {
            node.getListener().finishDownloadProgress(fileInfo.getHash());
            log.warn("Error in DownloadTasksManager: " + e.getMessage());
        } finally {
            if (threadPool != null) threadPool.shutdownNow();
            closePartFile();
            node.removeDownloadProcess(fileInfo.getHash());
            if (!downloadSuccessful) {
                log.error(
                        "Download failed or interrupted. Deleting partial file: "
                                + partFile.getAbsolutePath());
            }
            if (partFile.exists()) partFile.delete();
        }
    }

    /*
     * Starts a few assistants per peer, each with one request in flight,
     * and waits until every block is written,
     * or until the download can't finish (no peers left, disk error).
     */
    private void processDownload() throws IOException, InterruptedException {
        synchronized (this) {
            if (peersWithFile.isEmpty()) {
                throw new IOException("No peers have the file");
            }
            activeAssistants = peersWithFile.size() * Config.REQUESTS_IN_FLIGHT_PER_PEER;
            partChannel =
                    FileChannel.open(
                            partFile.toPath(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING);
        }

        for (SubNode peer : peersWithFile) {
            for (int i = 0; i < Config.REQUESTS_IN_FLIGHT_PER_PEER; i++) {
                threadPool.execute(new DownloadAssistant(this, peer));
            }
        }

        synchronized (this) {
            while (!allBlocksWritten() && failureReason == null) wait();
            if (failureReason != null) throw new IOException(failureReason);
        }
        closePartFile();
    }

    private void closePartFile() {
        FileChannel channel;
        synchronized (this) {
            channel = partChannel;
            partChannel = null;
        }
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException e) {
            log.warn("Couldn't close " + partFile + ": " + e.getMessage());
        }
    }

    private void verifyDownloadedFile() throws IOException {
        byte[] actualHash = Utils.calculateFileHash(partFile.getAbsolutePath());
        if (!MessageDigest.isEqual(actualHash, fileInfo.getHash())) {
            throw new IOException(
                    "Hash mismatch for " + fileInfo.getFileName() + ", download is corrupted");
        }
    }

    public void addNumberOfDownloadsForPeer(String peer, int number) {
        if (numberOfDownloadsForPeer.containsKey(peer)) {
            numberOfDownloadsForPeer.put(peer, numberOfDownloadsForPeer.get(peer) + number);
        } else {
            numberOfDownloadsForPeer.put(peer, number);
        }
    }

    private boolean allBlocksWritten() {
        return completedBlocks == totalBlocks;
    }

    private boolean isOver() {
        return allBlocksWritten() || failureReason != null;
    }

    /*
     * Gives the next block to download from this peer.
     * Waits while other assistants still have blocks in flight, since those
     * may come back if their peer drops. Returns null when there's nothing
     * left to do for this peer.
     */
    public synchronized FileBlockRequestMessage getDownloadRequest(SubNode peer)
            throws InterruptedException {
        while (true) {
            if (isOver() || isPeerDisconnected(peer)) return null;
            while (!requestList.isEmpty()) {
                FileBlockRequestMessage request = requestList.remove(0);
                if (!completedRequests.contains(request)) return request;
            }
            wait();
        }
    }

    // Puts a block back in the queue so another assistant can download it
    public synchronized void addDownloadRequest(FileBlockRequestMessage request) {
        if (completedRequests.contains(request)) return;
        requestList.add(request);
        notifyAll();
    }

    public synchronized void peerDisconnected(SubNode peer) {
        for (SubNode p : peersWithFile) {
            if (p == peer) {
                disconnectedPeers.add(peer);
                notifyAll();
                return;
            }
        }
    }

    public synchronized boolean isPeerDisconnected(SubNode peer) {
        return disconnectedPeers.contains(peer) || peer.getSocket().isClosed();
    }

    // Called by each assistant when it stops, for any reason
    public synchronized void assistantStopped() {
        activeAssistants--;
        if (activeAssistants == 0 && !isOver()) {
            failureReason = "No peers left with the file";
        }
        notifyAll();
    }

    public Map<String, Integer> getDownloadProcess() {
        return numberOfDownloadsForPeer;
    }

    public void addDownloadAnswer(FileBlockAnswerMessage answer) {
        FileBlockRequestMessage request = answer.getRequest();
        FileChannel channel;
        synchronized (this) {
            if (isOver() || partChannel == null || completedRequests.contains(request)) return;
            if (!blocksBeingWritten.add(request)) return;
            channel = partChannel;
        }

        try {
            ByteBuffer data = ByteBuffer.wrap(answer.getData());
            long position = answer.getOffset();
            while (data.hasRemaining()) position += channel.write(data, position);
        } catch (IOException e) {
            synchronized (this) {
                blocksBeingWritten.remove(request);
                if (!isOver()) {
                    log.error("Error writing block to disk: " + e.getMessage());
                    failureReason = "Error writing block to disk: " + e.getMessage();
                }
                notifyAll();
            }
            return;
        }

        synchronized (this) {
            blocksBeingWritten.remove(request);
            completedRequests.add(request);
            completedBlocks++;
            node.getListener().updateDownloadProgress(fileInfo.getHash(), completedBlocks);
            notifyAll();
        }
    }

    /*
     * Sleeps until the block is written, the peer disconnects,
     * the download ends, or the timeout runs out.
     * Returns true only if the block was written.
     */
    public synchronized boolean waitForBlock(
            FileBlockRequestMessage request, SubNode peer, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!completedRequests.contains(request)) {
            if (failureReason != null || isPeerDisconnected(peer)) return false;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return false;
            wait(remaining);
        }
        return true;
    }

    private ArrayList<SubNode> getNodesWithFile() {
        ArrayList<SubNode> nodesWithFile = new ArrayList<>();
        for (FileSearchResult request : requests) {
            for (SubNode peer : node.getPeers()) {
                if (peer.hasConnectionWith(request.getAddress(), request.getPort())) {
                    nodesWithFile.add(peer);
                }
            }
        }
        return nodesWithFile;
    }

    public Node getNode() {
        return node;
    }

    /*
     * Moves the finished file to its name, or to "name (1).ext", "name (2).ext", ...
     * when that name is taken, so an existing file is never overwritten.
     */
    private File saveUnderFreeName(String fileName) throws IOException {
        Path folder = node.getFolder().toPath().toAbsolutePath().normalize();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";

        for (int copy = 0; copy < 1000; copy++) {
            String name = copy == 0 ? fileName : stem + " (" + copy + ")" + extension;
            Path target = folder.resolve(name).normalize();
            if (!folder.equals(target.getParent())) {
                throw new IOException("Refusing to save outside the folder: " + name);
            }
            try {
                return Files.move(partFile.toPath(), target).toFile();
            } catch (FileAlreadyExistsException e) {
                // taken, try the next name
            }
        }
        throw new IOException("No free name for " + fileName);
    }

    /*
     * Keeps only the last part of a name a peer sent, so "../../.zshrc" becomes ".zshrc".
     * Returns null when nothing usable is left.
     */
    static String safeFileName(String name) {
        if (name == null) return null;
        String last = name.replace('\\', '/');
        last = last.substring(last.lastIndexOf('/') + 1);
        last = last.replaceAll("\\p{Cntrl}", "").strip();
        if (last.isEmpty() || last.equals(".") || last.equals("..")) return null;
        if (last.endsWith(Config.PARTIAL_DOWNLOAD_SUFFIX)) return null;
        return last;
    }
}
