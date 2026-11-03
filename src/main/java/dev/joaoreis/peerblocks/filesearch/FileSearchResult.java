package dev.joaoreis.peerblocks.filesearch;

import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.messaging.BinaryProtocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * Result of a file search containing file metadata and the node hosting it.
 *
 * <p>Wire layout (single item): [String: fileName] [byte[]: hash] [long: fileSize] [InetAddress:
 * address] [int: port] [int: displayNumber]
 *
 * <p>Wire layout (array): [int: length] [items...]
 */
public class FileSearchResult implements Comparable<FileSearchResult> {

    public static final byte ARRAY_TYPE_ID = 3;

    private WordSearchMessage searchMessage;
    private String fileName;
    private byte[] hash;
    private long fileSize;
    private InetAddress address;
    private int port;
    private int displayNumber;

    public FileSearchResult(
            WordSearchMessage searchMessage,
            String fileName,
            byte[] hash,
            long fileSize,
            InetAddress address,
            int port) {
        this.searchMessage = searchMessage;
        this.fileName = fileName;
        this.hash = hash;
        this.fileSize = fileSize;
        this.address = address;
        this.port = port;
    }

    public FileSearchResult(File file, Node node) {
        this.searchMessage = null;
        this.fileName = file.getName();
        this.hash = node.getHash(file.getAbsolutePath());
        this.fileSize = file.length();
        this.address = node.getAddress();
        this.port = node.getPort();
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    public void writeToStream(DataOutputStream dos) throws IOException {
        BinaryProtocol.writeString(dos, fileName);
        BinaryProtocol.writeBytes(dos, hash);
        dos.writeLong(fileSize);
        BinaryProtocol.writeInetAddress(dos, address);
        dos.writeInt(port);
        dos.writeInt(displayNumber);
    }

    public static FileSearchResult readFromStream(DataInputStream dis) throws IOException {
        String fileName = BinaryProtocol.readString(dis);
        byte[] hash = BinaryProtocol.readBytes(dis);
        long fileSize = dis.readLong();
        InetAddress address = BinaryProtocol.readInetAddress(dis);
        int port = dis.readInt();
        int displayNumber = dis.readInt();

        FileSearchResult res = new FileSearchResult(null, fileName, hash, fileSize, address, port);
        res.setDisplayNumber(displayNumber);
        return res;
    }

    public static byte[] arrayToBytes(FileSearchResult[] array) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(array.length);
        for (FileSearchResult item : array) {
            item.writeToStream(dos);
        }
        dos.flush();
        return baos.toByteArray();
    }

    public static FileSearchResult[] arrayFromBytes(byte[] bytes) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        int len = dis.readInt();
        FileSearchResult[] array = new FileSearchResult[len];
        for (int i = 0; i < len; i++) {
            array[i] = readFromStream(dis);
        }
        return array;
    }

    // ── Getters/Setters ───────────────────────────────────────────────────────

    public WordSearchMessage getSearchMessage() {
        return searchMessage;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getHash() {
        return hash;
    }

    public long getFileSize() {
        return fileSize;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public int getDisplayNumber() {
        return displayNumber;
    }

    public void setDisplayNumber(int displayNumber) {
        this.displayNumber = displayNumber;
    }

    @Override
    public String toString() {
        return displayNumber == 0 ? fileName : fileName + " (" + displayNumber + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileSearchResult that = (FileSearchResult) o;
        return Arrays.equals(hash, that.hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    @Override
    public int compareTo(FileSearchResult o) {
        if (o == null) {
            throw new NullPointerException("Cannot compare with null.");
        }
        return this.fileName.compareToIgnoreCase(o.fileName);
    }
}
