package dev.joaoreis.peerblocks.core;

public final class Config {

    private Config() {}

    // ---- Network ----

    // Each node listens on BASE_PORT + its ID
    public static final int BASE_PORT = 8080;
    // Largest message accepted from a peer (blocks are at most 1 MB)
    public static final int MAX_PAYLOAD_SIZE = 2 * 1024 * 1024;

    // ---- Nodes ----

    public static final int MIN_NODE_ID = 1;
    public static final int MAX_NODE_ID = 1920;

    public static final int MIN_NODE_PORT = BASE_PORT + MIN_NODE_ID;
    public static final int MAX_NODE_PORT = BASE_PORT + MAX_NODE_ID;
    // Each node's folder is WORK_FOLDER + its ID
    public static final String WORK_FOLDER = "nodes/node-";
    // Threads that answer block requests
    public static final int SENDER_THREADS = 5;
    // Block requests a download keeps waiting on per peer, so the peer is never idle
    public static final int REQUESTS_IN_FLIGHT_PER_PEER = 3;
    // Downloads that can run at the same time
    public static final int MAX_CONCURRENT_DOWNLOADS = 10;
    // Unfinished downloads end with this, and are never shared
    public static final String PARTIAL_DOWNLOAD_SUFFIX = ".part";

    // ---- Blocks ----

    // Files are split into about this many blocks, within the size limits below
    public static final int TARGET_BLOCK_COUNT = 64;
    public static final int MIN_BLOCK_SIZE = 1024;
    public static final int MAX_BLOCK_SIZE = 1024 * 1024;

    // ---- Timeouts ----

    // How long to wait for a peer to answer one block request
    public static final long BLOCK_ANSWER_TIMEOUT_MS = 100_000;
    // Timeouts allowed per peer before giving up on it
    public static final int MAX_TIMEOUTS_PER_PEER = 5;
    // Pause after opening a connection before sending the handshake
    public static final long CONNECTION_SETUP_DELAY_MS = 100;

    // ---- Hashing ----

    public static final int HASH_BUFFER_SIZE = 8192;
}
