package dev.joaoreis.peerblocks.services;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.core.NodeListener;
import dev.joaoreis.peerblocks.core.Utils;
import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import dev.joaoreis.peerblocks.filesearch.WordSearchMessage;
import dev.joaoreis.peerblocks.messaging.FileBlockAnswerMessage;
import dev.joaoreis.peerblocks.messaging.FileBlockRequestMessage;
import dev.joaoreis.peerblocks.messaging.NewConnectionRequest;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public class SubNode extends Thread {

    private final Node node;
    private final FileSearcher fileSearcher;

    private final Socket socket;
    private final boolean outgoingConnection;

    private DataOutputStream out;
    private DataInputStream in;
    private int originalBeforeOSchangePort;
    private boolean running = true;
    private CountDownLatch blockAnswerLatch;

    public SubNode(Node node, Socket socket, boolean outgoingConnection) {
        this.node = node;
        this.fileSearcher = new FileSearcher(node);
        this.socket = socket;
        this.outgoingConnection = outgoingConnection;
        initializeStreams();
    }

    @Override
    public void run() {
        if (in == null) return;
        handleCommunication();
    }

    private void initializeStreams() {
        try {
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());
        } catch (IOException e) {
            System.err.println("Error initializing streams: " + e.getMessage());
            closeResources();
        }
    }

    private void handleCommunication() {
        try {
            while (running) {
                byte typeId = in.readByte();
                int length = in.readInt();
                if (length < 0) throw new IOException("Invalid message length: " + length);

                if (length > Config.MAX_PAYLOAD_SIZE) {
                    throw new IOException("Payload too large: " + length + " bytes");
                }

                byte[] payload = new byte[length];
                in.readFully(payload);

                handleIncomingMessage(typeId, payload);
            }
        } catch (EOFException e) {
            System.err.println("End of stream reached unexpectedly.");
        } catch (IOException e) {
            System.err.println(node.getAddressAndPortFormated() + "IO error: " + e.getMessage());
        } finally {
            System.err.println("Closed connection for " + node.getAddressAndPortFormated());
            close();
        }
    }

    private void handleIncomingMessage(byte typeId, byte[] payload) throws IOException {
        switch (typeId) {
            case NewConnectionRequest.TYPE_ID:
                handleNewConnectionRequest(NewConnectionRequest.fromBytes(payload));
                break;
            case WordSearchMessage.TYPE_ID:
                handleWordSearchMessage(WordSearchMessage.fromBytes(payload));
                break;
            case FileSearchResult.ARRAY_TYPE_ID:
                handleFileSearchResults(FileSearchResult.arrayFromBytes(payload));
                break;
            case FileBlockRequestMessage.TYPE_ID:
                handleFileBlockRequest(FileBlockRequestMessage.fromBytes(payload));
                break;
            case FileBlockAnswerMessage.TYPE_ID:
                handleFileBlockAnswer(FileBlockAnswerMessage.fromBytes(payload));
                break;
            default:
                System.out.println(
                        node.getAddressAndPortFormated()
                                + "Received unknown message type ID: "
                                + typeId);
        }
    }

    /*
     * Handles a new connection request
     * The new connection request is used to send the port of the node to the new node
     * To override the one created by the Operative System
     */
    private void handleNewConnectionRequest(NewConnectionRequest request) {
        this.originalBeforeOSchangePort = request.getClientPort();

        if (!outgoingConnection && node.getListener() != null) {
            boolean accepted =
                    node.getListener()
                            .confirmIncomingConnection(
                                    request.getClientAddress().getHostAddress(),
                                    request.getClientPort());
            if (!accepted) {
                System.out.println(
                        node.getAddressAndPortFormated()
                                + "Rejected incoming connection from "
                                + request.getClientAddress().getHostAddress()
                                + ":"
                                + request.getClientPort());
                close();
                return;
            }
        }

        logNewConnection();
    }

    /*
     * Handles a word search message
     * The word search message is used to send the keyword to the node
     * and to send the files that contain the keyword
     */
    private void handleWordSearchMessage(WordSearchMessage message) {
        if (node.getFolder().exists() && node.getFolder().isDirectory()) {
            FileSearchResult[] results = fileSearcher.search(message);
            if (results.length > 0) sendObject(results);
        }
    }

    /*
     * Handles the file search results
     * The file search results are used to send the files that contain the keyword
     */
    private void handleFileSearchResults(FileSearchResult[] results) {
        NodeListener listener = node.getListener();
        if (listener == null) {
            System.out.println(
                    node.getAddressAndPortFormated()
                            + "No listener to show search results, ignoring them");
            return;
        }
        listener.loadListModel(results);
    }

    /*
     * Handles a file block request
     * When receives one, adds it to the list of requests to process in the node
     */
    private void handleFileBlockRequest(FileBlockRequestMessage request) {
        node.addBlockRequest(request);
    }

    /*
     * Handles a file block answer
     * When receives one, adds it to the list of answers to process in the node
     * And counts down the latch
     */
    private void handleFileBlockAnswer(FileBlockAnswerMessage answer) {
        int port =
                Utils.isValidPort(socket.getPort()) ? socket.getPort() : originalBeforeOSchangePort;

        node.writeBlockToFile(answer.getHash(), socket.getInetAddress(), port, answer);

        if (blockAnswerLatch != null) {
            blockAnswerLatch.countDown();
        }
    }

    /*
     * Sends a file block request
     * The file block request is used to request a part of a file
     */
    public void sendFileBlockRequest(FileBlockRequestMessage block) {
        block.setSenderAddress(node.getAddress().getHostAddress());
        block.setSenderPort(node.getPort());
        sendObject(block);
    }

    /*
     * Sends a word search message request
     * The word search message request is used to send the keyword to the node
     * and to basically ask for the files that contain the keyword
     */
    public void sendWordSearchMessageRequest(String keyword) {
        WordSearchMessage searchPackage = new WordSearchMessage(keyword);
        sendObject(searchPackage);
    }

    /*
     * Sends a new connection request
     * The new connection request is used to send the port of the node to the new node
     * To override the one created by the Operative System
     */
    public void sendNewConnectionRequest(InetAddress endereco, int thisPort) {
        if (out == null) {
            System.out.println(
                    node.getAddressAndPortFormated()
                            + "OutputStream is null [invalid port: "
                            + thisPort
                            + "]");
            return;
        }
        NewConnectionRequest request = new NewConnectionRequest(endereco, thisPort);
        sendObject(request);
        logNewConnection();
    }

    /*
     * Sends an object through the socket
     */
    public synchronized void sendObject(Object message) {
        if (out == null || socket.isClosed()) {
            System.out.println("Socket closed, cannot send");
            close();
            return;
        }

        try {
            byte typeId;
            byte[] payload;

            if (message instanceof NewConnectionRequest m) {
                typeId = NewConnectionRequest.TYPE_ID;
                payload = m.toBytes();
            } else if (message instanceof WordSearchMessage m) {
                typeId = WordSearchMessage.TYPE_ID;
                payload = m.toBytes();
            } else if (message instanceof FileSearchResult[] m) {
                typeId = FileSearchResult.ARRAY_TYPE_ID;
                payload = FileSearchResult.arrayToBytes(m);
            } else if (message instanceof FileBlockRequestMessage m) {
                typeId = FileBlockRequestMessage.TYPE_ID;
                payload = m.toBytes();
            } else if (message instanceof FileBlockAnswerMessage m) {
                typeId = FileBlockAnswerMessage.TYPE_ID;
                payload = m.toBytes();
            } else {
                throw new IllegalArgumentException("Unknown message type");
            }

            out.writeByte(typeId);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();

        } catch (IOException e) {
            e.printStackTrace();
            close();
        }
    }

    /*
     * Closes the socket, ends the thread
     * and removes the node from the list of peers
     */
    public void close() {
        if (!running) return;

        running = false;

        closeResources();
        int port =
                Utils.isValidPort(socket.getPort()) ? socket.getPort() : originalBeforeOSchangePort;
        System.out.println(
                node.getAddressAndPortFormated()
                        + "Thread closed for SubNode at "
                        + socket.getInetAddress().getHostAddress()
                        + ":"
                        + port);
        node.removePeer(this);
    }

    // Closes the socket
    private void closeResources() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            System.out.println(
                    node.getAddressAndPortFormated()
                            + "Error closing resources: "
                            + e.getMessage());
        }
    }

    public void setBlockAnswerLatch(CountDownLatch latch) {
        this.blockAnswerLatch = latch;
    }

    public int getOriginalBeforeOSchangePort() {
        return originalBeforeOSchangePort;
    }

    public Socket getSocket() {
        return socket;
    }

    private void logNewConnection() {
        int port =
                Utils.isValidPort(originalBeforeOSchangePort)
                        ? originalBeforeOSchangePort
                        : socket.getPort();
        System.out.println(
                node.getAddressAndPortFormated()
                        + "Added new node::NodeAddress [address="
                        + socket.getInetAddress().getHostAddress()
                        + " port="
                        + port
                        + "]");
    }

    @Override
    public String toString() {
        return ("SubNode [originalBeforeOSchangePort="
                + originalBeforeOSchangePort
                + ", socket="
                + socket
                + ", node="
                + node
                + ", listener="
                + node.getListener()
                + ", outgoingConnection="
                + outgoingConnection
                + ", running="
                + running
                + "]");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SubNode subNode = (SubNode) obj;
        return (outgoingConnection
                && this.socket.getPort() == subNode.socket.getPort()
                && socket.getInetAddress().equals(subNode.getSocket().getInetAddress()));
    }

    /*
     * Checks if the socket is connected to the given address and port.
     * If the socket is not connected to the given address and port, it will
     * return false.
     */
    public boolean hasConnectionWith(InetAddress address, int port) {
        int thisSocketPort =
                Utils.isValidPort(socket.getPort()) ? socket.getPort() : originalBeforeOSchangePort;
        return (this.socket.getLocalAddress().getHostAddress().equals(address.getHostAddress())
                && thisSocketPort == port);
    }

    @Override
    public int hashCode() {
        if (outgoingConnection) return Objects.hash(socket.getInetAddress(), socket.getPort());
        return super.hashCode();
    }

    public void sendFileBlockAnswer(FileBlockAnswerMessage answer) {
        sendObject(answer);
    }

    public String getDestinationAddress() {
        return socket.getInetAddress().getHostAddress();
    }

    public int getDestinationPort() {
        int port =
                Utils.isValidPort(socket.getPort()) ? socket.getPort() : originalBeforeOSchangePort;
        return port;
    }
}
