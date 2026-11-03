package dev.joaoreis.peerblocks.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import dev.joaoreis.peerblocks.filesearch.WordSearchMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageSerializationTest {

    private static final byte[] HASH = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    @Test
    void newConnectionRequestRoundTripsIPv4() throws Exception {
        InetAddress address = InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 1, 20});

        NewConnectionRequest decoded =
                NewConnectionRequest.fromBytes(new NewConnectionRequest(address, 8085).toBytes());

        assertEquals(address, decoded.getClientAddress());
        assertEquals(8085, decoded.getClientPort());
    }

    @Test
    void newConnectionRequestRoundTripsIPv6() throws Exception {
        InetAddress address = InetAddress.getByName("2001:db8::1");

        NewConnectionRequest decoded =
                NewConnectionRequest.fromBytes(new NewConnectionRequest(address, 9000).toBytes());

        assertEquals(address, decoded.getClientAddress());
        assertEquals(9000, decoded.getClientPort());
    }

    @Test
    void wordSearchMessageRoundTripsWithUnicode() throws Exception {
        WordSearchMessage decoded =
                WordSearchMessage.fromBytes(new WordSearchMessage("música-日本語").toBytes());

        assertEquals("música-日本語", decoded.getKeyword());
    }

    @Test
    void wordSearchMessageKeepsNullKeyword() throws Exception {
        WordSearchMessage decoded =
                WordSearchMessage.fromBytes(new WordSearchMessage(null).toBytes());

        assertNull(decoded.getKeyword());
    }

    @Test
    void blockRequestRoundTripsIncludingSender() throws Exception {
        FileBlockRequestMessage request = new FileBlockRequestMessage(HASH, 4096L, 1024);
        request.setSenderAddress("10.0.0.7");
        request.setSenderPort(8083);

        FileBlockRequestMessage decoded = FileBlockRequestMessage.fromBytes(request.toBytes());

        assertEquals(request, decoded);
        assertEquals("10.0.0.7", decoded.getSenderAddress());
        assertEquals(8083, decoded.getSenderPort());
    }

    @Test
    void blockRequestsForDifferentBlocksAreNotEqual() {
        FileBlockRequestMessage first = new FileBlockRequestMessage(HASH, 0, 1024);

        assertEquals(first, new FileBlockRequestMessage(HASH.clone(), 0, 1024));
        assertEquals(false, first.equals(new FileBlockRequestMessage(HASH, 1024, 1024)));
        assertEquals(false, first.equals(new FileBlockRequestMessage(new byte[] {9}, 0, 1024)));
    }

    @Test
    void blockAnswerRoundTripsWithTheRequestedBytes(@TempDir Path dir) throws Exception {
        byte[] content = new byte[10_000];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31);
        File file = Files.write(dir.resolve("data.bin"), content).toFile();
        FileBlockRequestMessage request = new FileBlockRequestMessage(HASH, 2000, 3000);

        FileBlockAnswerMessage answer =
                new FileBlockAnswerMessage("10.0.0.1", 8081, 1, request, file);
        FileBlockAnswerMessage decoded = FileBlockAnswerMessage.fromBytes(answer.toBytes());

        assertArrayEquals(Arrays.copyOfRange(content, 2000, 5000), decoded.getData());
        assertEquals(2000, decoded.getOffset());
        assertEquals(3000, decoded.getLength());
        assertEquals(request, decoded.getRequest());
        assertEquals("10.0.0.1", decoded.getSenderAddress());
        assertEquals(8081, decoded.getSenderPort());
    }

    @Test
    void searchResultArrayRoundTrips() throws Exception {
        InetAddress address = InetAddress.getByAddress(new byte[] {10, 0, 0, 5});
        FileSearchResult[] results = {
            new FileSearchResult(null, "a.mp3", HASH, 123_456L, address, 8081),
            new FileSearchResult(null, "ficheiro ç.txt", new byte[] {7, 7}, 0L, address, 8082),
        };

        FileSearchResult[] decoded =
                FileSearchResult.arrayFromBytes(FileSearchResult.arrayToBytes(results));

        assertEquals(2, decoded.length);
        for (int i = 0; i < results.length; i++) {
            assertEquals(results[i].getFileName(), decoded[i].getFileName());
            assertArrayEquals(results[i].getHash(), decoded[i].getHash());
            assertEquals(results[i].getFileSize(), decoded[i].getFileSize());
            assertEquals(results[i].getAddress(), decoded[i].getAddress());
            assertEquals(results[i].getPort(), decoded[i].getPort());
        }
    }

    @Test
    void emptySearchResultArrayRoundTrips() throws Exception {
        FileSearchResult[] decoded =
                FileSearchResult.arrayFromBytes(
                        FileSearchResult.arrayToBytes(new FileSearchResult[0]));

        assertEquals(0, decoded.length);
    }

    @Test
    void binaryProtocolStringsRoundTrip() throws Exception {
        for (String value : new String[] {"", "plain", "çãõ ✓ 日本", null}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            BinaryProtocol.writeString(new DataOutputStream(bytes), value);

            String decoded =
                    BinaryProtocol.readString(
                            new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

            assertEquals(value, decoded);
        }
    }

    @Test
    void truncatedMessagesFailInsteadOfReturningGarbage() throws Exception {
        byte[] full = new FileBlockRequestMessage(HASH, 0, 1024).toBytes();
        byte[] truncated = Arrays.copyOf(full, full.length - 3);

        assertThrows(EOFException.class, () -> FileBlockRequestMessage.fromBytes(truncated));
        assertThrows(IOException.class, () -> NewConnectionRequest.fromBytes(new byte[] {4, 1}));
    }
}
