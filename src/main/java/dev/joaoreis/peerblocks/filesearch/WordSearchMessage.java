package dev.joaoreis.peerblocks.filesearch;

import dev.joaoreis.peerblocks.messaging.BinaryProtocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Message sent to request a word search across the network.
 *
 * <p>Wire layout: [String: keyword]
 */
public class WordSearchMessage {

    public static final byte TYPE_ID = 2;
    private String keyword;

    public WordSearchMessage(String keyword) {
        this.keyword = keyword;
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        BinaryProtocol.writeString(dos, keyword);
        dos.flush();
        return baos.toByteArray();
    }

    public static WordSearchMessage fromBytes(byte[] bytes) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        String keyword = BinaryProtocol.readString(dis);
        return new WordSearchMessage(keyword);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getKeyword() {
        return keyword;
    }

    @Override
    public String toString() {
        return "WordSearchMessage [keyword=" + keyword + "]";
    }
}
