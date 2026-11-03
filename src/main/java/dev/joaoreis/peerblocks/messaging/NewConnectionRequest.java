package dev.joaoreis.peerblocks.messaging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;

/**
 * Sent once by the connecting side to announce its actual listen port (which differs from the
 * ephemeral OS port visible on the remote side).
 *
 * <p>Wire layout (8–20 bytes): [1 byte : addr length (4=IPv4, 16=IPv6)] [N bytes: raw address] [4
 * bytes: clientPort]
 */
public class NewConnectionRequest {

    public static final byte TYPE_ID = 1;

    private final InetAddress clientAddress;
    private final int clientPort;

    public NewConnectionRequest(InetAddress clientAddress, int clientPort) {
        this.clientAddress = clientAddress;
        this.clientPort = clientPort;
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(24);
        DataOutputStream dos = new DataOutputStream(baos);
        BinaryProtocol.writeInetAddress(dos, clientAddress);
        dos.writeInt(clientPort);
        dos.flush();
        return baos.toByteArray();
    }

    public static NewConnectionRequest fromBytes(byte[] bytes) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        InetAddress address = BinaryProtocol.readInetAddress(dis);
        int port = dis.readInt();
        return new NewConnectionRequest(address, port);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public InetAddress getClientAddress() {
        return clientAddress;
    }

    public int getClientPort() {
        return clientPort;
    }

    @Override
    public String toString() {
        return "NewConnectionRequest [clientAddress="
                + clientAddress
                + ", clientPort="
                + clientPort
                + "]";
    }
}
