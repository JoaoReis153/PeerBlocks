package dev.joaoreis.peerblocks.services;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.messaging.FileBlockRequestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DownloadAssistant extends Thread {

    private static final Logger log = LoggerFactory.getLogger(DownloadAssistant.class);

    private final DownloadTasksManager taskManager;
    private final SubNode peerToRequestBlock;
    private int timeoutCount;

    public DownloadAssistant(DownloadTasksManager taskManager, SubNode peerToRequestBlock) {
        this.timeoutCount = 0;
        this.taskManager = taskManager;
        this.peerToRequestBlock = peerToRequestBlock;
    }

    /*
     * Keeps taking blocks from the taskManager and requesting them from this peer
     * until there's nothing left, the peer disconnects, or it times out too often.
     * Always tells the taskManager when it stops, so the download can fail
     * instead of hanging when no peers are left.
     */
    @Override
    public void run() {
        try {
            while (true) {
                FileBlockRequestMessage request =
                        taskManager.getDownloadRequest(peerToRequestBlock);
                if (request == null) break;
                if (!handleRequest(request)) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Download was cancelled
        } finally {
            taskManager.assistantStopped();
        }
    }

    /*
     * Requests one block from the peer and waits for it.
     *
     * If it doesn't arrive, the block goes back to the taskManager for another peer.
     * Returns false when this assistant should stop: the peer disconnected,
     * or it timed out more than the allowed number of times.
     */
    private boolean handleRequest(FileBlockRequestMessage request) throws InterruptedException {
        peerToRequestBlock.sendFileBlockRequest(request);

        if (taskManager.waitForBlock(request, peerToRequestBlock, Config.BLOCK_ANSWER_TIMEOUT_MS)) {
            return true;
        }

        taskManager.addDownloadRequest(request);
        if (taskManager.isPeerDisconnected(peerToRequestBlock)) {
            log.info("Peer disconnected, handing its block back");
            return false;
        }
        timeoutCount++;
        return timeoutCount <= Config.MAX_TIMEOUTS_PER_PEER;
    }
}
