package dev.joaoreis.peerblocks.core;

import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import dev.joaoreis.peerblocks.messaging.FileBlockAnswerMessage;
import dev.joaoreis.peerblocks.services.DownloadTasksManager;
import dev.joaoreis.peerblocks.services.SenderAssistant;
import dev.joaoreis.peerblocks.services.SubNode;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Node implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Node.class);

    private final int nodeId;
    private final int port;
    private final InetAddress address;
    private final File folder;
    private final NodeListener listener;

    private final PeerManager peers;
    private final FileIndex files;
    private final BlockRequestQueue blockRequests = new BlockRequestQueue();
    private final Map<String, DownloadTasksManager> downloadManagers = new ConcurrentHashMap<>();
    private final Map<String, ThreadFactory> threadFactories = new ConcurrentHashMap<>();

    private final ExecutorService senders;
    private final ExecutorService downloadTaskManagersThreadPool;

    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ServerSocket serverSocket;

    public Node(int nodeId, NodeListener listener) {
        if (!Utils.isValidID(nodeId)) {
            throw new IllegalArgumentException(
                    "Invalid node ID. Input a valid number ("
                            + Config.MIN_NODE_ID
                            + "-"
                            + Config.MAX_NODE_ID
                            + ")");
        }
        this.nodeId = nodeId;
        this.port = Config.BASE_PORT + nodeId;
        this.listener = listener;
        this.address = initializeAddress();
        this.folder = createWorkingDirectory();
        this.peers = new PeerManager(this);
        this.files = new FileIndex(folder, getAddressAndPortFormated());
        files.start();

        this.senders = Executors.newFixedThreadPool(Config.SENDER_THREADS, namedThreads("sender"));
        this.downloadTaskManagersThreadPool =
                Executors.newFixedThreadPool(
                        Config.MAX_CONCURRENT_DOWNLOADS, namedThreads("download"));
        for (int i = 0; i < Config.SENDER_THREADS; i++) {
            senders.execute(new SenderAssistant(this));
        }
    }

    private File createWorkingDirectory() {
        File dir = new File(Config.WORK_FOLDER + nodeId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException(
                    getAddressAndPortFormated() + "Failed to create directory: " + dir);
        }
        return dir;
    }

    private InetAddress initializeAddress() {
        try {
            return Utils.getLocalIPAddress();
        } catch (Exception e) {
            throw new RuntimeException("Unable to get the device's address", e);
        }
    }

    /**
     * Thread names like "node-8081-sender-1", so a node's threads are easy to find. Every call for
     * the same role shares one counter, so names never repeat, even across downloads.
     */
    public ThreadFactory namedThreads(String role) {
        return threadFactories.computeIfAbsent(role, this::newThreadFactory);
    }

    private ThreadFactory newThreadFactory(String role) {
        return Thread.ofPlatform().name("node-" + port + "-" + role + "-", 1).factory();
    }

    /**
     * Accepts connections from other nodes until the node is closed or the server fails. Blocks the
     * calling thread.
     */
    public void startServing() {
        if (closed.get()) return;
        log.info(getAddressAndPortFormated() + "Awaiting connection...");
        try (ServerSocket socket = new ServerSocket(port)) {
            serverSocket = socket;
            while (!closed.get()) {
                peers.accept(socket.accept());
            }
        } catch (IOException e) {
            if (closed.get()) return; // close() shut the server socket
            throw new RuntimeException(
                    getAddressAndPortFormated() + "Failed to start server: " + e.getMessage());
        } finally {
            serverSocket = null;
        }
    }

    /**
     * Stops the node: the server, every connection, running downloads (their .part files are
     * deleted), the sender threads and the file watcher. Waits a few seconds for them to end.
     * Calling it again does nothing.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        log.info(getAddressAndPortFormated() + "Shutting down");

        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // already closed
            }
        }
        downloadTaskManagersThreadPool.shutdownNow();
        senders.shutdownNow();
        List<SubNode> open = new ArrayList<>(peers.all());
        for (SubNode peer : open) peer.close();
        files.close();

        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            downloadTaskManagersThreadPool.awaitTermination(
                    remaining(deadline), TimeUnit.NANOSECONDS);
            senders.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS);
            for (SubNode peer : open) {
                if (peer != Thread.currentThread()) {
                    TimeUnit.NANOSECONDS.timedJoin(peer, remaining(deadline));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    public boolean isClosed() {
        return closed.get();
    }

    // ---- Parts ----

    public PeerManager peers() {
        return peers;
    }

    public FileIndex files() {
        return files;
    }

    public BlockRequestQueue blockRequests() {
        return blockRequests;
    }

    // ---- Shortcuts ----

    public void connectToNode(String targetAddress, int targetPort) {
        if (closed.get()) return;
        peers.connect(targetAddress, targetPort);
    }

    /** Each peer answers with the files it has whose name contains the keyword. */
    public void broadcastWordSearchMessageRequest(String keyword) {
        peers.broadcastSearch(keyword);
    }

    public Set<SubNode> getPeers() {
        return peers.all();
    }

    public boolean hasFileWithHash(byte[] hash) {
        return files.contains(hash);
    }

    // ---- Downloads ----

    public void downloadFiles(List<List<FileSearchResult>> filesToDownload) {
        if (closed.get()) return;
        for (List<FileSearchResult> file : filesToDownload) {
            byte[] hash = file.get(0).getHash();
            String hashKey = hashKey(hash);
            if (downloadManagers.containsKey(hashKey)) continue; // already downloading
            if (hasFileWithHash(hash)) continue; // already have it

            log.info(getAddressAndPortFormated() + "Request file: " + file.get(0));
            DownloadTasksManager downloadManager = new DownloadTasksManager(this, file);
            downloadManagers.put(hashKey, downloadManager);
            downloadTaskManagersThreadPool.execute(downloadManager);
        }
    }

    public void removeDownloadProcess(byte[] hash) {
        downloadManagers.remove(hashKey(hash));
    }

    public Map<String, Integer> getDownloadProcess(byte[] hash) {
        return downloadManagers.get(hashKey(hash)).getDownloadProcess();
    }

    /*
     * Hands a received block to its download.
     * The sender's address and port count how many blocks each peer sent.
     */
    public void writeBlockToFile(
            byte[] hash, InetAddress address, int port, FileBlockAnswerMessage answer) {
        DownloadTasksManager downloadManager = downloadManagers.get(hashKey(hash));
        if (downloadManager == null) return;
        downloadManager.addDownloadAnswer(answer);
        downloadManager.addNumberOfDownloadsForPeer(address.getHostAddress() + ":" + port, 1);
    }

    /** Called when a connection closes. */
    public void removePeer(SubNode peer) {
        peers.remove(peer);
        // Let active downloads hand this peer's blocks to the others right away
        for (DownloadTasksManager manager : downloadManagers.values()) {
            manager.peerDisconnected(peer);
        }
    }

    // ---- Info ----

    public File getFolder() {
        return folder;
    }

    public NodeListener getListener() {
        return listener;
    }

    public int getId() {
        return nodeId;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public String getAddressAndPort() {
        return address.getHostAddress() + ":" + port;
    }

    public String getAddressAndPortFormated() {
        return "[" + address.getHostAddress() + ":" + port + "]";
    }

    @Override
    public String toString() {
        return "Node " + address + " " + port;
    }

    private String hashKey(byte[] hash) {
        return Arrays.toString(hash);
    }
}
