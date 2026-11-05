package dev.joaoreis.peerblocks.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.joaoreis.peerblocks.messaging.FileBlockRequestMessage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BlockRequestQueueTest {

    private static List<FileBlockRequestMessage> blocks() {
        return FileBlockRequestMessage.createBlockList(new byte[32], 10_000);
    }

    @Test
    void handsOutRequestsInOrder() throws InterruptedException {
        BlockRequestQueue queue = new BlockRequestQueue();
        List<FileBlockRequestMessage> blocks = blocks();
        queue.add(blocks.get(0));
        queue.add(blocks.get(1));

        assertSame(blocks.get(0), queue.take());
        assertSame(blocks.get(1), queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void takeWaitsForARequest() throws Exception {
        BlockRequestQueue queue = new BlockRequestQueue();
        CompletableFuture<FileBlockRequestMessage> taken =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return queue.take();
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        });

        Thread.sleep(100);
        assertTrue(!taken.isDone(), "take() should wait while the queue is empty");

        FileBlockRequestMessage request = blocks().get(0);
        queue.add(request);
        assertSame(request, taken.get(2, TimeUnit.SECONDS));
    }
}
