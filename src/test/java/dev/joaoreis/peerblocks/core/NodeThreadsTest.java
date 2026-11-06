package dev.joaoreis.peerblocks.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.joaoreis.peerblocks.support.TestListener;
import dev.joaoreis.peerblocks.support.TestNodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NodeThreadsTest {

    @AfterEach
    void cleanUp() {
        TestNodes.cleanup();
    }

    @Test
    void threadNamesNeverRepeatAcrossPools() throws Exception {
        Node node = TestNodes.startNode(new TestListener());
        String prefix = "node-" + node.getPort() + "-fetch-";

        // Each download asks for its own fetch threads, like two downloads running at once
        Thread first = node.namedThreads("fetch").newThread(() -> {});
        Thread second = node.namedThreads("fetch").newThread(() -> {});

        assertEquals(prefix + "1", first.getName());
        assertEquals(prefix + "2", second.getName());
        assertNotEquals(first.getName(), second.getName());
    }
}
