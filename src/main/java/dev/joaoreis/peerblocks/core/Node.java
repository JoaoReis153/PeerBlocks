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
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Node {

    private static final Logger log = LoggerFactory.getLogger(Node.class);

    private final int nodeId;
    private final int port;
    private final InetAddress address;
    private final File folder;
    private final Set<SubNode> peers;
    private final Map<String, DownloadTasksManager> downloadManagers;
    private final NodeListener listener;
    private final BlockRequestQueue blockRequests = new BlockRequestQueue();
    private ExecutorService senders;
    private final FileIndex files;

    private final ExecutorService downloadTaskManagersThreadPool =
            Executors.newFixedThreadPool(Config.MAX_CONCURRENT_DOWNLOADS);

    public Node(int nodeId, NodeListener listener) {
        this.nodeId = nodeId;
        this.port = Config.BASE_PORT + nodeId;
        this.listener = listener;
        this.peers = ConcurrentHashMap.newKeySet();
        this.downloadManagers = new ConcurrentHashMap<>();
        validateId();
        initializeSenders(Config.SENDER_THREADS);
        this.address = initializeAddress();
        this.folder = createWorkingDirectory();
        this.files = new FileIndex(folder, getAddressAndPortFormated());
        files.start();
    }

    /*
     * Returns the peer, given a destination address and port, if it exists
     */
    public SubNode getPeerToSend(String address, int port) {
        for (SubNode peer : peers) {
            if (peer.getDestinationAddress().equals(address) && peer.getDestinationPort() == port) {
                return peer;
            }
        }
        return null;
    }

    /*
     * Initializes the senders for the node
     * The senders are responsible for answering the requests
     * from other nodes with a reply that contains the requested part of the file
     */
    private void initializeSenders(int n) {
        if (n <= 0) return;

        this.senders = Executors.newFixedThreadPool(n);
        for (int i = 0; i < n; i++) {
            this.senders.execute(new SenderAssistant(this));
        }
    }

    /*
     *  Use the already created method to check the ID
     *  And throw an exception if it's not valid
     */
    private void validateId() {
        if (!Utils.isValidID(this.nodeId)) {
            throw new IllegalArgumentException(
                    "Invalid node ID. Input a valid number ("
                            + Config.MIN_NODE_ID
                            + "-"
                            + Config.MAX_NODE_ID
                            + ")");
        }
    }

    /*
     * Creates the working directory for the node
     * The working directory is the directory where the node will store the files
     */
    private File createWorkingDirectory() {
        File folder = new File(Config.WORK_FOLDER + nodeId);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new RuntimeException(
                    getAddressAndPortFormated() + "Failed to create directory: dl" + nodeId);
        }
        return folder;
    }

    /*
     * Initializes the address of the node
     * The address is the IP address of the node
     */
    private InetAddress initializeAddress() {
        try {
            return dev.joaoreis.peerblocks.core.Utils.getLocalIPAddress();
        } catch (Exception e) {
            throw new RuntimeException(
                    getAddressAndPortFormated() + " Unable to get the device's address", e);
        }
    }

    /*
     * Starts the server of the node
     * The server is responsible for accepting connections from other nodes
     * and creating a new handler for each connection
     */
    public void startServing() {
        log.info(getAddressAndPortFormated() + "Awaiting connection...");
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                SubNode clientHandler = new SubNode(this, clientSocket, false);
                peers.add(clientHandler);
                clientHandler.start();
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    getAddressAndPortFormated() + "Failed to start server: " + e.getMessage());
        }
    }

    /*
     * Connects to a node
     * The connection is made by sending a new connection request to the node
     * and waiting for a response
     */
    public void connectToNode(String targetAddress, int targetPort) {
        try {
            InetAddress targetInetAddress = resolveAddress(targetAddress);

            // Check if the connection is valid
            // The valid connection method alreadys throws exceptions accordingly to the type of
            // error
            if (!isValidConnection(targetInetAddress, targetPort)) {
                return;
            }

            establishConnection(targetInetAddress, targetPort);
        } catch (Exception e) {
            log.error("Error connecting to node", e);
        }
    }

    /*
     * Resolves the address of a node
     * The address is resolved by using the InetAddress.getByName method
     * and checking if the resolved address is null
     *
     * This method will be called when trying to connect to a node
     */
    private InetAddress resolveAddress(String address) {
        try {
            InetAddress resolved = InetAddress.getByName(address);
            if (resolved == null) {
                throw new IllegalArgumentException("Invalid address");
            }
            return resolved;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to resolve address: " + address);
        }
    }

    /*
     * Checks if a connection with a node is valid
     * There are three types of errors that can be thrown:
     * 1. Invalid port range
     * 2. Connection already exists
     * 3. Unable to resolve the address
     *
     * Otherwise, the connection is valid
     */
    private boolean isValidConnection(InetAddress targetAddress, int targetPort) {
        if (!Utils.isValidPort(targetPort)) {
            log.warn(getAddressAndPortFormated() + "Failed to connect: Invalid port range");
            return false;
        }

        if (targetAddress.equals(this.address) && targetPort == this.port) {
            log.warn(getAddressAndPortFormated() + "Failed to connect: Cannot connect to itself");
            return false;
        }

        if (isAlreadyConnected(targetAddress, targetPort)) {
            log.warn(getAddressAndPortFormated() + "Failed to connect: Connection already exists");
            return false;
        }

        return true;
    }

    /*
     * Checks if a connection with a node already exists
     * The connection is checked by comparing the address and port of the connection
     */
    private boolean isAlreadyConnected(InetAddress targetAddress, int targetPort) {
        for (SubNode peer : peers) {
            if ((peer.getSocket().getInetAddress().equals(targetAddress)
                            && peer.getSocket().getPort() == targetPort)
                    || peer.getOriginalBeforeOSchangePort() == targetPort) {
                return true;
            }
        }
        return false;
    }

    /*
     * Establishes a connection with a node
     * The connection is made by creating a new handler for the connection
     * and adding it to the list of peers
     */
    private void establishConnection(InetAddress targetAddress, int targetPort) {
        try {
            Socket clientSocket = new Socket(targetAddress, targetPort);
            SubNode handler = new SubNode(this, clientSocket, true);
            peers.add(handler);
            handler.start();
            notifyPeersChanged();

            Thread.sleep(Config.CONNECTION_SETUP_DELAY_MS);
            handler.sendNewConnectionRequest(address, port);
        } catch (IOException e) {
            log.error(
                    getAddressAndPortFormated()
                            + "Failed to establish connection: "
                            + e.getMessage());
        } catch (InterruptedException e) {
            log.error(
                    getAddressAndPortFormated()
                            + "Failed to establish connection: "
                            + e.getMessage());
        }
    }

    /*
     * Broadcasts a word search message request to all peers
     * The message is sent to all peers in the list of peers
     *
     * That each wil eventually send a FileSearchResult list with the files they have locally that contain the keyword
     */
    public void broadcastWordSearchMessageRequest(String keyword) {
        for (SubNode peer : peers) peer.sendWordSearchMessageRequest(keyword);
    }

    public void downloadFiles(List<List<FileSearchResult>> filesToDownload) {
        for (List<FileSearchResult> file : filesToDownload) {
            // Check if it's already downloading the file
            String hashKey = hashKey(file.get(0).getHash());
            if (downloadManagers.containsKey(hashKey)) continue;

            // Check if already has the file in the directory
            if (hasFileWithHash(file.get(0).getHash())) continue;
            FileSearchResult example = file.get(0);
            log.info(getAddressAndPortFormated() + "Request file: " + example);
            DownloadTasksManager downloadManager = new DownloadTasksManager(this, file);
            downloadManagers.put(hashKey, downloadManager);
            downloadTaskManagersThreadPool.execute(downloadManager);
        }
    }

    // Remove a request from the list of requests to process
    public void removeDownloadProcess(byte[] hash) {
        downloadManagers.remove(hashKey(hash));
    }

    // Returns the download process of a file
    public Map<String, Integer> getDownloadProcess(byte[] hash) {
        return downloadManagers.get(hashKey(hash)).getDownloadProcess();
    }

    /*
     *  Add an answer to a download request
     * It uses the address and port of who sent the answer,
     * to know how many answers has each peer sent
     */
    public void writeBlockToFile(
            byte[] hash, InetAddress address, int port, FileBlockAnswerMessage answer) {
        DownloadTasksManager downloadManager = downloadManagers.get(hashKey(hash));
        if (downloadManager == null) return;
        downloadManager.addDownloadAnswer(answer);
        downloadManager.addNumberOfDownloadsForPeer(address.getHostAddress() + ":" + port, 1);
    }

    public void notifyPeersChanged() {
        if (listener != null) listener.peersChanged();
    }

    // Remove a peer from the list of peers
    public void removePeer(SubNode peer) {
        peers.remove(peer);
        notifyPeersChanged();
        // Let active downloads hand this peer's blocks to the others right away
        for (DownloadTasksManager manager : downloadManagers.values()) {
            manager.peerDisconnected(peer);
        }
        int port =
                Utils.isValidPort(peer.getSocket().getPort())
                        ? peer.getSocket().getPort()
                        : peer.getOriginalBeforeOSchangePort();
        log.info(
                getAddressAndPortFormated()
                        + "Removed connection with: "
                        + peer.getSocket().getInetAddress().getHostAddress()
                        + "::"
                        + port);
    }

    public boolean hasFileWithHash(byte[] hash) {
        return files.contains(hash);
    }

    public FileIndex files() {
        return files;
    }

    public BlockRequestQueue blockRequests() {
        return blockRequests;
    }

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

    public Set<SubNode> getPeers() {
        return peers;
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
