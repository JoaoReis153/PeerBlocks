package dev.joaoreis.peerblocks.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.core.Utils;
import dev.joaoreis.peerblocks.messaging.FileBlockAnswerMessage;
import dev.joaoreis.peerblocks.messaging.FileBlockRequestMessage;
import dev.joaoreis.peerblocks.messaging.NewConnectionRequest;
import dev.joaoreis.peerblocks.support.TestNodes;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Bad block requests must not stop a node from answering the good ones that follow. */
@Timeout(30)
class SenderAssistantTest {

    @AfterEach
    void cleanUp() {
        TestNodes.cleanup();
    }

    @Test
    void keepsAnsweringAfterMoreBadRequestsThanSenderThreads() throws Exception {
        Node seeder = TestNodes.startSeeder("song.bin", TestNodes.randomBytes(10_000, 1));
        byte[] hash = Utils.calculateFileHash(TestNodes.fileOf(seeder, "song.bin").getPath());
        int peerPort = Config.BASE_PORT + TestNodes.reserveId();

        try (Socket peer = new Socket(seeder.getAddress(), seeder.getPort())) {
            peer.setSoTimeout(5_000);
            DataOutputStream out = new DataOutputStream(peer.getOutputStream());
            send(
                    out,
                    NewConnectionRequest.TYPE_ID,
                    new NewConnectionRequest(peer.getLocalAddress(), peerPort).toBytes());

            // A length of 0 is invalid and makes the node fail while building the answer
            for (int i = 0; i < Config.SENDER_THREADS + 3; i++) {
                send(out, blockRequest(peer, peerPort, hash, 0));
            }
            send(out, blockRequest(peer, peerPort, hash, 1_000));

            DataInputStream in = new DataInputStream(peer.getInputStream());
            assertEquals(FileBlockAnswerMessage.TYPE_ID, in.readByte());
        }
    }

    private static FileBlockRequestMessage blockRequest(
            Socket peer, int peerPort, byte[] hash, int length) {
        FileBlockRequestMessage request = new FileBlockRequestMessage(hash, 0, length);
        request.setSenderAddress(peer.getLocalAddress().getHostAddress());
        request.setSenderPort(peerPort);
        return request;
    }

    private static void send(DataOutputStream out, FileBlockRequestMessage request)
            throws IOException {
        send(out, FileBlockRequestMessage.TYPE_ID, request.toBytes());
    }

    private static void send(DataOutputStream out, byte type, byte[] payload) throws IOException {
        out.writeByte(type);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }
}
