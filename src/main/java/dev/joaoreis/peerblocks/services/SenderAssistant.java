package dev.joaoreis.peerblocks.services;

import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.messaging.FileBlockAnswerMessage;
import dev.joaoreis.peerblocks.messaging.FileBlockRequestMessage;
import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SenderAssistant extends Thread {

    private static final Logger log = LoggerFactory.getLogger(SenderAssistant.class);

    private final Node node;

    public SenderAssistant(Node node) {
        this.node = node;
    }

    public void run() {
        while (true) {
            try {
                processBlockRequest();
            } catch (InterruptedException e) {
                log.debug(node.getAddressAndPortFormated() + " SenderAssistant stopping");
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn(
                        node.getAddressAndPortFormated()
                                + " SenderAssistant: skipped a request: "
                                + e.getMessage());
            }
        }
    }

    /*
     * Processes a block request message.
     *
     * It takes a block request message from the node's block request queue
     * and sends a block answer message to the peer that sent the request.
     */
    public void processBlockRequest() throws InterruptedException {
        FileBlockRequestMessage request = node.blockRequests().take();
        FileBlockAnswerMessage answer = fillRequest(request);
        if (answer == null) return;
        if (request.getSenderAddress() == null) {
            log.info(node.getAddressAndPortFormated() + " SenderAssistant: Null address");
            return;
        } else if (request.getSenderPort() == 0) {
            log.info(node.getAddressAndPortFormated() + " SenderAssistant: Null port");
            return;
        }

        SubNode peer = node.peers().find(request.getSenderAddress(), request.getSenderPort());
        if (peer == null) {
            log.info(
                    node.getAddressAndPortFormated() + " SenderAssistant: Requesting peer is gone");
            return;
        }
        peer.sendFileBlockAnswer(answer);
    }

    /*
     * Fills a block request message with the file block requested
     *
     * It takes a block request message and searches for the file with the same hash as the request
     *
     * If the file block is found, it returns a block answer message with the
     * file block requested.
     *
     * If the file block is not found, it returns null.
     */
    public FileBlockAnswerMessage fillRequest(FileBlockRequestMessage request) {
        byte[] hash = request.getHash();
        if (hash == null) return null;
        File file = node.files().findFileByHash(hash);
        if (file == null) return null;
        return new FileBlockAnswerMessage(
                node.getAddress().getHostAddress(), node.getPort(), node.getId(), request, file);
    }
}
