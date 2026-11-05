package dev.joaoreis.peerblocks.services;

import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import dev.joaoreis.peerblocks.filesearch.WordSearchMessage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Finds the files in a node's folder whose name contains a search keyword. */
public class FileSearcher {

    private final Node node;

    public FileSearcher(Node node) {
        this.node = node;
    }

    /** Returns the matching files, or an empty array if there are none. */
    public FileSearchResult[] search(WordSearchMessage message) {
        File[] files = node.files().sharedFiles();

        String keyword = message.getKeyword().toLowerCase();
        List<FileSearchResult> results = new ArrayList<>();

        for (File file : files) {
            if (!file.getName().toLowerCase().contains(keyword)) continue;

            byte[] hash = node.files().getHash(file);
            if (hash == null) continue;

            results.add(
                    new FileSearchResult(
                            message,
                            file.getName(),
                            hash,
                            file.length(),
                            node.getAddress(),
                            node.getPort()));
        }
        return results.toArray(new FileSearchResult[0]);
    }
}
