package Support;

import Core.Config;
import Core.Node;
import Core.Utils;
import FileSearch.FileSearchResult;
import FileSearch.WordSearchMessage;
import Messaging.FileBlockAnswerMessage;
import Messaging.FileBlockRequestMessage;
import Messaging.NewConnectionRequest;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A seeder that speaks the wire protocol by hand and hangs up after a set number of answers. It
 * lets tests drop a peer in the middle of a download at an exact point.
 */
public class FakeSeeder {

    private final Node downloader;
    private final File backingFile;
    private final byte[] hash;
    private final int answersBeforeDrop;
    private final int id = TestNodes.reserveId();
    private final int port = Config.BASE_PORT + id;
    private final AtomicInteger requestsSeen = new AtomicInteger();

    private Socket socket;
    private DataOutputStream out;

    /**
     * @param answersBeforeDrop how many block requests to answer before closing the connection
     */
    public FakeSeeder(Node downloader, File backingFile, int answersBeforeDrop) {
        this.downloader = downloader;
        this.backingFile = backingFile;
        this.hash = Utils.calculateFileHash(backingFile.getAbsolutePath());
        this.answersBeforeDrop = answersBeforeDrop;
    }

    /** Connects to the downloader and announces itself, like a real node's handshake. */
    public void connect() throws IOException {
        socket = new Socket(downloader.getAddress(), downloader.getPort());
        out = new DataOutputStream(socket.getOutputStream());
        send(
                NewConnectionRequest.TYPE_ID,
                new NewConnectionRequest(downloader.getAddress(), port).toBytes());

        Thread listener = new Thread(this::listen, "fake-seeder-" + id);
        listener.setDaemon(true);
        listener.start();
    }

    public int blockRequestsSeen() {
        return requestsSeen.get();
    }

    private void listen() {
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
            while (true) {
                byte type = in.readByte();
                byte[] payload = new byte[in.readInt()];
                in.readFully(payload);
                handle(type, payload);
            }
        } catch (IOException e) {
            // The connection was closed, either by the downloader or by us
        }
    }

    private void handle(byte type, byte[] payload) throws IOException {
        if (type == WordSearchMessage.TYPE_ID) {
            FileSearchResult result =
                    new FileSearchResult(
                            null,
                            backingFile.getName(),
                            hash,
                            backingFile.length(),
                            downloader.getAddress(),
                            port);
            send(
                    FileSearchResult.ARRAY_TYPE_ID,
                    FileSearchResult.arrayToBytes(new FileSearchResult[] {result}));
        } else if (type == FileBlockRequestMessage.TYPE_ID) {
            FileBlockRequestMessage request = FileBlockRequestMessage.fromBytes(payload);
            if (requestsSeen.incrementAndGet() > answersBeforeDrop) {
                socket.close(); // hang up without answering this block
                return;
            }
            FileBlockAnswerMessage answer =
                    new FileBlockAnswerMessage(
                            downloader.getAddress().getHostAddress(),
                            port,
                            id,
                            request,
                            backingFile);
            send(FileBlockAnswerMessage.TYPE_ID, answer.toBytes());
        }
    }

    private synchronized void send(byte type, byte[] payload) throws IOException {
        out.writeByte(type);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }
}
