package dev.joaoreis.peerblocks.messaging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Answer to a FileBlockRequestMessage, containing the actual file data.
 *
 * <p>Wire layout: [int: nodeId] [byte[]: hash] [long: offset] [int: length] [int: senderPort]
 * [String: senderAddress] [FileBlockRequestMessage: request] [byte[]: data]
 */
public class FileBlockAnswerMessage {

    public static final byte TYPE_ID = 5;

    private final int nodeId;
    private final byte[] hash;
    private final long offset;
    private final int length;
    private int senderPort;
    private String senderAddress;
    private FileBlockRequestMessage request;
    private byte[] data;
    private File file;

    public FileBlockAnswerMessage(
            String senderAddress,
            int senderPort,
            int nodeId,
            FileBlockRequestMessage request,
            File file) {
        this.senderPort = senderPort;
        this.senderAddress = senderAddress;
        this.request = request;
        this.nodeId = nodeId;
        this.hash = request.getHash();
        this.offset = request.getOffset();
        this.length = request.getLength();
        this.file = file;
        if (length <= 0) {
            throw new IllegalArgumentException("Invalid length: length must be positive");
        }
        loadDataFromFile();
    }

    private FileBlockAnswerMessage(
            int nodeId,
            byte[] hash,
            long offset,
            int length,
            int senderPort,
            String senderAddress,
            FileBlockRequestMessage request,
            byte[] data) {
        this.nodeId = nodeId;
        this.hash = hash;
        this.offset = offset;
        this.length = length;
        this.senderPort = senderPort;
        this.senderAddress = senderAddress;
        this.request = request;
        this.data = data;
    }

    private void loadDataFromFile() {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileSize = raf.length();
            if (offset < 0 || length <= 0 || offset + length > fileSize) {
                throw new IllegalArgumentException("Invalid offset or length");
            }

            byte[] blockData = new byte[length];
            raf.seek(offset);
            raf.readFully(blockData);
            this.data = blockData;
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            this.data = new byte[0];
        } catch (IllegalArgumentException e) {
            System.err.println("Warning: " + e.getMessage());
            this.data = new byte[0];
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(nodeId);
        BinaryProtocol.writeBytes(dos, hash);
        dos.writeLong(offset);
        dos.writeInt(length);
        dos.writeInt(senderPort);
        BinaryProtocol.writeString(dos, senderAddress);
        request.writeToStream(dos);
        BinaryProtocol.writeBytes(dos, data);
        dos.flush();
        return baos.toByteArray();
    }

    public static FileBlockAnswerMessage fromBytes(byte[] bytes) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        int nodeId = dis.readInt();
        byte[] hash = BinaryProtocol.readBytes(dis);
        long offset = dis.readLong();
        int length = dis.readInt();
        int senderPort = dis.readInt();
        String senderAddress = BinaryProtocol.readString(dis);
        FileBlockRequestMessage request = FileBlockRequestMessage.readFromStream(dis);
        byte[] data = BinaryProtocol.readBytes(dis);

        return new FileBlockAnswerMessage(
                nodeId, hash, offset, length, senderPort, senderAddress, request, data);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public byte[] getHash() {
        return hash;
    }

    public long getOffset() {
        return offset;
    }

    public int getLength() {
        return length;
    }

    public byte[] getData() {
        return data;
    }

    public FileBlockRequestMessage getRequest() {
        return request;
    }

    public int getSenderPort() {
        return senderPort;
    }

    public String getSenderAddress() {
        return senderAddress;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileBlockAnswerMessage that = (FileBlockAnswerMessage) o;
        return nodeId == that.nodeId
                && offset == that.offset
                && length == that.length
                && Arrays.equals(hash, that.hash);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(nodeId);
        result = 31 * result + Arrays.hashCode(hash);
        result = 31 * result + Long.hashCode(offset);
        result = 31 * result + Integer.hashCode(length);
        return result;
    }

    @Override
    public String toString() {
        return "FileBlockAnswerMessage{"
                + "nodeId="
                + nodeId
                + ", hash="
                + Arrays.toString(hash)
                + ", offset="
                + offset
                + ", length="
                + length
                + ", dataSize="
                + (data != null ? data.length : 0)
                + '}';
    }
}
