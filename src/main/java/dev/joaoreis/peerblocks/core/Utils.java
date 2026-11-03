package dev.joaoreis.peerblocks.core;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Enumeration;

public class Utils {

    /*
     * Calculates the hash of a file
     * The hash is calculated using the SHA-256 algorithm
     */
    public static byte[] calculateFileHash(String filePath) {
        try (InputStream is = Files.newInputStream(Paths.get(filePath))) {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] buffer = new byte[Config.HASH_BUFFER_SIZE];
            int read;

            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }

            return digest.digest();

        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate file hash for: " + filePath, e);
        }
    }

    // Checks if a port is valid
    public static Boolean isValidPort(int port) {
        return port >= Config.MIN_NODE_PORT && port <= Config.MAX_NODE_PORT;
    }

    // Checks if a node ID is valid
    public static Boolean isValidID(int id) {
        return id >= Config.MIN_NODE_ID && id <= Config.MAX_NODE_ID;
    }

    // Returns the local IP address of the computer that the program is running on
    public static InetAddress getLocalIPAddress() throws Exception {

        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (inetAddress instanceof Inet4Address) { // Get IPv4 address
                        return inetAddress;
                    }
                }
            }
        }

        return null;
    }
}
