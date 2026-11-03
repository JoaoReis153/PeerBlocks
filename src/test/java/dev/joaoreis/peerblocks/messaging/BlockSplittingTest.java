package dev.joaoreis.peerblocks.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.joaoreis.peerblocks.core.Config;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockSplittingTest {

    private static final byte[] HASH = {1, 2, 3, 4};

    @ParameterizedTest
    @ValueSource(
            longs = {
                1L,
                1023L,
                1024L,
                1025L,
                64L * 1024,
                1_000_000L,
                10_000_000L,
                64L * 1024 * 1024,
                200L * 1024 * 1024 + 3
            })
    void blocksCoverTheWholeFileWithoutGapsOrOverlaps(long fileSize) {
        List<FileBlockRequestMessage> blocks =
                FileBlockRequestMessage.createBlockList(HASH, fileSize);

        long expectedOffset = 0;
        for (FileBlockRequestMessage block : blocks) {
            assertEquals(expectedOffset, block.getOffset(), "blocks must be contiguous");
            assertTrue(block.getLength() > 0, "blocks must not be empty");
            assertTrue(block.getLength() <= Config.MAX_BLOCK_SIZE, "block larger than the limit");
            expectedOffset += block.getLength();
        }
        assertEquals(fileSize, expectedOffset, "blocks must add up to the file size");
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 5_000L, 1_000_000L, 64L * 1024 * 1024, 500L * 1024 * 1024})
    void blockSizeStaysWithinLimitsExceptForTheLastBlock(long fileSize) {
        List<FileBlockRequestMessage> blocks =
                FileBlockRequestMessage.createBlockList(HASH, fileSize);

        for (int i = 0; i < blocks.size() - 1; i++) {
            int length = blocks.get(i).getLength();
            assertTrue(length >= Config.MIN_BLOCK_SIZE, "block smaller than the minimum");
            assertTrue(length <= Config.MAX_BLOCK_SIZE, "block larger than the maximum");
        }
    }

    @Test
    void midSizedFilesAreSplitIntoAboutTheTargetNumberOfBlocks() {
        List<FileBlockRequestMessage> blocks =
                FileBlockRequestMessage.createBlockList(HASH, 32L * 1024 * 1024);

        assertEquals(Config.TARGET_BLOCK_COUNT, blocks.size());
    }

    @Test
    void emptyFileHasNoBlocks() {
        assertTrue(FileBlockRequestMessage.createBlockList(HASH, 0).isEmpty());
    }

    @Test
    void everyBlockIsDistinct() {
        List<FileBlockRequestMessage> blocks =
                FileBlockRequestMessage.createBlockList(HASH, 7_654_321L);

        Set<FileBlockRequestMessage> unique = new HashSet<>(blocks);

        assertEquals(blocks.size(), unique.size());
    }

    @Test
    void explicitBlockSizeIsRespected() {
        List<FileBlockRequestMessage> blocks =
                FileBlockRequestMessage.createBlockList(HASH, 2500, 1000);

        assertEquals(3, blocks.size());
        assertEquals(1000, blocks.get(0).getLength());
        assertEquals(1000, blocks.get(1).getLength());
        assertEquals(500, blocks.get(2).getLength());
        assertFalse(blocks.get(2).getOffset() + blocks.get(2).getLength() > 2500);
    }
}
