package dev.joaoreis.peerblocks.messaging;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Wire formats: InetAddress : [1-byte addr length (4=IPv4, 16=IPv6)] [N raw bytes] byte[] : [4-byte
 * length] [N bytes] String : [4-byte UTF-8 length, -1 = null] [N bytes]
 */
public final class BinaryProtocol {

    private BinaryProtocol() {}

    // ── InetAddress ───────────────────────────────────────────────────────────

    public static void writeInetAddress(DataOutputStream dos, InetAddress addr) throws IOException {
        byte[] raw = addr.getAddress(); // 4 (IPv4) or 16 (IPv6)
        dos.writeByte(raw.length);
        dos.write(raw);
    }

    public static InetAddress readInetAddress(DataInputStream dis) throws IOException {
        int len = dis.readUnsignedByte(); // 4 or 16
        byte[] raw = new byte[len];
        dis.readFully(raw);
        return InetAddress.getByAddress(raw);
    }

    // ── byte[] ────────────────────────────────────────────────────────────────

    public static void writeBytes(DataOutputStream dos, byte[] data) throws IOException {
        dos.writeInt(data.length);
        dos.write(data);
    }

    public static byte[] readBytes(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        byte[] data = new byte[len];
        dis.readFully(data);
        return data;
    }

    // ── String ───────────────────────────────────────────────────────────────

    public static void writeString(DataOutputStream dos, String s) throws IOException {
        if (s == null) {
            dos.writeInt(-1);
        } else {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(bytes.length);
            dos.write(bytes);
        }
    }

    public static String readString(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        if (len == -1) return null;
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
