package dev.joaoreis.peerblocks.core;

import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import java.util.Map;

/*
 * Callback interface that decouples Node (and its services) from the concrete GUI class.
 * GUI implements this interface; a null/no-op implementation can be used for headless / test runs.
 */
public interface NodeListener {

    /*
     * Called when an incoming connection request arrives.
     * Returns true if the connection should be accepted.
     */
    boolean confirmIncomingConnection(String address, int port);

    /*
     * Called when a download starts, so the listener can show a progress indicator.
     */
    void startDownloadProgress(byte[] hash, String fileName, int totalBlocks);

    /*
     * Called each time a new block is written, so the listener can update the progress indicator.
     */
    void updateDownloadProgress(byte[] hash, int completedBlocks);

    /*
     * Called when a download is aborted / fails, so the listener can close the progress indicator.
     */
    void finishDownloadProgress(byte[] hash);

    /*
     * Called when a download completes successfully, passing duration and per-peer block counts.
     */
    void completeDownloadProgress(
            byte[] hash, long durationInMs, Map<String, Integer> nodesNBlocks);

    /*
     * Called when the node's file list may have changed and the listener should refresh its view.
     */
    void reloadListModel();

    /*
     * Called when a peer connects or disconnects, so the listener can refresh its list of peers.
     */
    default void peersChanged() {}

    /*
     * Called when a peer returns search results that the listener should display.
     */
    void loadListModel(FileSearchResult[] list);
}
