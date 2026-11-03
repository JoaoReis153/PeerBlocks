package dev.joaoreis.peerblocks.support;

import dev.joaoreis.peerblocks.core.NodeListener;
import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Headless listener that records what a node reports, so tests can wait for it. */
public class TestListener implements NodeListener {

    private final List<FileSearchResult> results = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile boolean succeeded;

    public List<FileSearchResult> getResults() {
        synchronized (results) {
            return new ArrayList<>(results);
        }
    }

    /** Waits until a download succeeds or fails. Returns false on timeout. */
    public boolean awaitFinished(long timeoutMs) throws InterruptedException {
        return finished.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean downloadSucceeded() {
        return succeeded;
    }

    @Override
    public boolean confirmIncomingConnection(String address, int port) {
        return true;
    }

    @Override
    public void loadListModel(FileSearchResult[] list) {
        Collections.addAll(results, list);
    }

    @Override
    public void completeDownloadProgress(
            byte[] hash, long durationInMs, Map<String, Integer> nodesNBlocks) {
        succeeded = true;
        finished.countDown();
    }

    @Override
    public void finishDownloadProgress(byte[] hash) {
        finished.countDown();
    }

    @Override
    public void startDownloadProgress(byte[] hash, String fileName, int totalBlocks) {}

    @Override
    public void updateDownloadProgress(byte[] hash, int completedBlocks) {}

    @Override
    public void reloadListModel() {}
}
