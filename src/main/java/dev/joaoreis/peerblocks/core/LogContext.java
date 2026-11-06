package dev.joaoreis.peerblocks.core;

import org.slf4j.MDC;

/**
 * Puts a node's address into the logging context of the current thread, so logback.xml can print it
 * on every line that node logs.
 *
 * <pre>{@code
 * try (var logContext = LogContext.open(node.getAddressAndPort())) {
 *     log.info("Shutting down"); // printed as "Node [127.0.0.1:8081] - Shutting down"
 * }
 * }</pre>
 */
public final class LogContext {

    /** The key logback.xml reads with %X{node}. */
    public static final String KEY = "node";

    private LogContext() {}

    /** Undoes an {@link #open}. Closing it never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Puts the node's address in this thread's log context until the returned scope is closed,
     * which restores whatever was there before.
     */
    public static Scope open(String node) {
        String previous = MDC.get(KEY);
        MDC.put(KEY, node);
        return () -> {
            if (previous == null) MDC.remove(KEY);
            else MDC.put(KEY, previous);
        };
    }

    /** Wraps a task so it runs with the node's address in the log context, for another thread. */
    public static Runnable wrap(String node, Runnable task) {
        return () -> {
            try (Scope scope = open(node)) {
                task.run();
            }
        };
    }
}
