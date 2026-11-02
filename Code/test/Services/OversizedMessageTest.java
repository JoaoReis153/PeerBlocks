package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import Core.Config;
import Core.Node;
import Messaging.FileBlockAnswerMessage;
import Support.TestListener;
import Support.TestNodes;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A peer that announces a huge message must be disconnected before the node allocates for it. */
@Timeout(30)
class OversizedMessageTest {

    @AfterEach
    void cleanUp() {
        TestNodes.cleanup();
    }

    @Test
    void closesTheConnectionWhenTheAnnouncedSizeIsOverTheLimit() throws Exception {
        Node node = TestNodes.startNode(new TestListener());

        assertEquals(-1, readAfterSending(node, Config.MAX_PAYLOAD_SIZE + 1));
    }

    @Test
    void closesTheConnectionWhenTheAnnouncedSizeIsNegative() throws Exception {
        Node node = TestNodes.startNode(new TestListener());

        assertEquals(-1, readAfterSending(node, -5));
    }

    /**
     * Sends a message header announcing {@code announcedSize} bytes (and no payload), then returns
     * what the node does: -1 when it closes the connection, -2 if it keeps it open.
     */
    private static int readAfterSending(Node node, int announcedSize) throws IOException {
        try (Socket peer = new Socket(node.getAddress(), node.getPort())) {
            peer.setSoTimeout(5_000);
            DataOutputStream out = new DataOutputStream(peer.getOutputStream());
            out.writeByte(FileBlockAnswerMessage.TYPE_ID);
            out.writeInt(announcedSize);
            out.flush();
            try {
                return peer.getInputStream().read();
            } catch (SocketTimeoutException e) {
                return -2;
            }
        }
    }
}
