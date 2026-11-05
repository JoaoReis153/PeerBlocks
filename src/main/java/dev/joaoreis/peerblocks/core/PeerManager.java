package dev.joaoreis.peerblocks.core;

import dev.joaoreis.peerblocks.services.SubNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The connections a node has to other nodes: opening, accepting, finding and removing them. */
public class PeerManager {

    private static final Logger log = LoggerFactory.getLogger(PeerManager.class);

    private final Node node;
    private final Set<SubNode> peers = ConcurrentHashMap.newKeySet();

    public PeerManager(Node node) {
        this.node = node;
    }

    public Set<SubNode> all() {
        return Collections.unmodifiableSet(peers);
    }

    /** Opens a connection to another node. Problems are logged, not thrown. */
    public void connect(String targetAddress, int targetPort) {
        try {
            InetAddress target = resolveAddress(targetAddress);
            if (!isValidConnection(target, targetPort)) return;
            establishConnection(target, targetPort);
        } catch (Exception e) {
            log.error("Error connecting to node", e);
        }
    }

    /** Takes over a connection another node opened to this one. */
    public void accept(Socket socket) {
        SubNode handler = new SubNode(node, socket, false);
        peers.add(handler);
        handler.start();
    }

    /** Returns the peer with this address and server port, or null. */
    public SubNode find(String address, int port) {
        for (SubNode peer : peers) {
            if (peer.getDestinationAddress().equals(address) && peer.getDestinationPort() == port) {
                return peer;
            }
        }
        return null;
    }

    /** Asks every peer for the files whose name contains the keyword. */
    public void broadcastSearch(String keyword) {
        for (SubNode peer : peers) peer.sendWordSearchMessageRequest(keyword);
    }

    /** Forgets a peer. Returns false if it wasn't connected. */
    public boolean remove(SubNode peer) {
        if (!peers.remove(peer)) return false;
        notifyChanged();
        int port =
                Utils.isValidPort(peer.getSocket().getPort())
                        ? peer.getSocket().getPort()
                        : peer.getOriginalBeforeOSchangePort();
        log.info(
                node.getAddressAndPortFormated()
                        + "Removed connection with: "
                        + peer.getSocket().getInetAddress().getHostAddress()
                        + "::"
                        + port);
        return true;
    }

    public void notifyChanged() {
        NodeListener listener = node.getListener();
        if (listener != null) listener.peersChanged();
    }

    private InetAddress resolveAddress(String address) {
        try {
            return InetAddress.getByName(address);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to resolve address: " + address);
        }
    }

    private boolean isValidConnection(InetAddress targetAddress, int targetPort) {
        if (!Utils.isValidPort(targetPort)) {
            log.warn(node.getAddressAndPortFormated() + "Failed to connect: Invalid port range");
            return false;
        }
        if (targetAddress.equals(node.getAddress()) && targetPort == node.getPort()) {
            log.warn(
                    node.getAddressAndPortFormated()
                            + "Failed to connect: Cannot connect to itself");
            return false;
        }
        if (isAlreadyConnected(targetAddress, targetPort)) {
            log.warn(
                    node.getAddressAndPortFormated()
                            + "Failed to connect: Connection already exists");
            return false;
        }
        return true;
    }

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

    private void establishConnection(InetAddress targetAddress, int targetPort) {
        try {
            Socket socket = new Socket(targetAddress, targetPort);
            SubNode handler = new SubNode(node, socket, true);
            peers.add(handler);
            handler.start();
            notifyChanged();

            Thread.sleep(Config.CONNECTION_SETUP_DELAY_MS);
            handler.sendNewConnectionRequest(node.getAddress(), node.getPort());
        } catch (IOException e) {
            log.error(
                    node.getAddressAndPortFormated()
                            + "Failed to establish connection: "
                            + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(node.getAddressAndPortFormated() + "Interrupted while connecting");
        }
    }
}
