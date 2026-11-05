package dev.joaoreis.peerblocks.core;

import dev.joaoreis.peerblocks.messaging.FileBlockRequestMessage;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Block requests from peers, waiting for a sender thread to answer them. */
public class BlockRequestQueue {

    private final BlockingQueue<FileBlockRequestMessage> requests = new LinkedBlockingQueue<>();

    public void add(FileBlockRequestMessage request) {
        requests.add(request);
    }

    /** Waits until a request is available and removes it. */
    public FileBlockRequestMessage take() throws InterruptedException {
        return requests.take();
    }

    public int size() {
        return requests.size();
    }
}
