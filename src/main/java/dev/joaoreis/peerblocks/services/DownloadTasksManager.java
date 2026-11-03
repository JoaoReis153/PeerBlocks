package dev.joaoreis.peerblocks.services;

import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.core.Utils;
import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import dev.joaoreis.peerblocks.messaging.FileBlockAnswerMessage;
import dev.joaoreis.peerblocks.messaging.FileBlockRequestMessage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadTasksManager extends Thread {

    private Node node;
    private FileSearchResult fileInfo;
    private List<FileSearchResult> requests;
    private ExecutorService threadPool;
    private List<FileBlockRequestMessage> requestList;
    private Set<FileBlockRequestMessage> completedRequests;
    private File destinationFile;
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

        System.out.println(
                node.getAddressAndPortFormated()
                        + "[taskmanager]"
                        + "Download task manager created for file "
                        + fileInfo.getHash());
        this.completedRequests = new HashSet<FileBlockRequestMessage>();
        this.numberOfDownloadsForPeer = new HashMap<>();
        this.requestList =
                FileBlockRequestMessage.createBlockList(fileInfo.getHash(), fileInfo.getFileSize());
        this.totalBlocks = requestList.size();
        this.completedBlocks = 0;
        this.peersWithFile = getNodesWithFile();
        this.destinationFile = new File(buildFilePath(fileInfo.getFileName()));

        // In case there are no nodes with the file, return
        if (peersWithFile.isEmpty()) {
            System.out.println(node.getAddressAndPort() + "Couldn't find peers with the file");
            return;
        }
        this.threadPool = Executors.newFixedThreadPool(peersWithFile.size());
        System.out.println(
                node.getAddressAndPortFormated()
                        + "[taskmanager]"
                        + " "
                        + requestList.size()
                        + " blocks to process");
    }

    @Override
    public void run() {
        try {
            node.getListener()
                    .startDownloadProgress(fileInfo.getHash(), fileInfo.getFileName(), totalBlocks);

            long start = System.currentTimeMillis();
            processDownload();
            verifyDownloadedFile();
            downloadSuccessful = true;
            long duration = System.currentTimeMillis() - start;
            long safeDurationMs = Math.max(1L, duration);
            long bytesPerSecond = (fileInfo.getFileSize() * 1000L) / safeDurationMs;

            node.getListener()
                    .completeDownloadProgress(
                            fileInfo.getHash(), duration, new HashMap<>(numberOfDownloadsForPeer));
            System.out.println(
                    node.getAddressAndPortFormated()
                            + "[taskmanager]"
                            + "Download finished for file "
                            + fileInfo.getFileName()
                            + " ["
                            + fileInfo.getFileSize()
                            + "] at a rate of "
                            + bytesPerSecond
                            + " bytes/s");
            node.getListener().reloadListModel();
            node.loadHashes();
        } catch (Exception e) {
            node.getListener().finishDownloadProgress(fileInfo.getHash());
            System.out.println(
                    node.getAddressAndPortFormated()
                            + "Error in DownloadTasksManager: "
                            + e.getMessage());
            // e.printStackTrace();
        } finally {
            if (threadPool != null) threadPool.shutdownNow();
            node.removeDownloadProcess(fileInfo.getHash());
            if (!downloadSuccessful) {
                System.err.println(
                        node.getAddressAndPortFormated()
                                + " [taskmanager] Download failed or interrupted. Deleting partial file: "
                                + destinationFile.getAbsolutePath());
                if (destinationFile.exists()) {
                    destinationFile.delete();
                }
            }
        }
    }

    /*
     * Starts one assistant per peer and waits until every block is written,
     * or until the download can't finish (no peers left, disk error).
     */
    private void processDownload() throws IOException, InterruptedException {
        synchronized (this) {
            if (peersWithFile.isEmpty()) {
                throw new IOException("No peers have the file");
            }
            activeAssistants = peersWithFile.size();
        }

        for (SubNode peer : peersWithFile) {
            threadPool.execute(new DownloadAssistant(this, peer));
        }

        synchronized (this) {
            while (!allBlocksWritten() && failureReason == null) wait();
            if (failureReason != null) throw new IOException(failureReason);
        }
    }

    private void verifyDownloadedFile() throws IOException {
        byte[] actualHash = Utils.calculateFileHash(destinationFile.getAbsolutePath());
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

    public synchronized void addDownloadAnswer(FileBlockAnswerMessage answer) {
        if (completedRequests.contains(answer.getRequest())) {
            return;
        }

        try (RandomAccessFile raf = new RandomAccessFile(destinationFile, "rw")) {
            raf.seek(answer.getOffset());
            raf.write(answer.getData());

            completedRequests.add(answer.getRequest());
            completedBlocks++;
            node.getListener().updateDownloadProgress(fileInfo.getHash(), completedBlocks);
            notifyAll();
        } catch (IOException e) {
            System.err.println(
                    node.getAddressAndPortFormated()
                            + " Error writing block to disk: "
                            + e.getMessage());
            failureReason = "Error writing block to disk: " + e.getMessage();
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

    private String buildFilePath(String fileName) {
        return (getNode().getFolder().getAbsolutePath() + File.separator + fileName);
    }
}
