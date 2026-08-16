package com.furnitureims.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Enforces NFR-07: exactly one instance of the application runs at a time, and starting a
 * second one focuses the first instead of opening a second connection to the SQLite file
 * (two writers on one SQLite database is exactly the kind of corruption risk this guard
 * exists to prevent).
 * <p>
 * Implemented as a loopback-only TCP listener on a fixed, application-specific port. The
 * first instance to bind the port is the primary; any later launch fails to bind, sends a
 * one-byte "please come to front" signal to whichever process is holding the port, and exits.
 * This doubles as the inter-process signal needed to bring the first window forward, so no
 * second mechanism (e.g. a file lock) is needed alongside it.
 */
@Component
public class SingleInstanceGuard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    /** Arbitrary high, application-specific port. A collision with an unrelated process on
     *  this exact port is unlikely enough on a single shop PC to accept; the failure mode if
     *  it ever happened is a false "already running" message, not silent data corruption. */
    private static final int PORT = 58217;

    private ServerSocket serverSocket;
    private Thread listenerThread;

    /**
     * @param onFocusRequested invoked (on a background thread - the caller must hop to the
     *                         JavaFX thread itself) whenever a second launch attempt is detected
     * @return true if this process acquired the lock and should proceed as the primary instance;
     *         false if another instance is already running and this process should exit
     */
    public boolean acquire(Runnable onFocusRequested) {
        try {
            serverSocket = new ServerSocket(PORT, 10, InetAddress.getLoopbackAddress());
        } catch (IOException alreadyBound) {
            signalPrimaryInstance();
            return false;
        }

        listenerThread = new Thread(() -> listenForDuplicateLaunches(onFocusRequested), "single-instance-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        return true;
    }

    private void listenForDuplicateLaunches(Runnable onFocusRequested) {
        while (!serverSocket.isClosed()) {
            try (Socket ignored = serverSocket.accept()) {
                log.info("Detected a second launch attempt; bringing the primary window to front");
                onFocusRequested.run();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    log.warn("Single-instance listener error", e);
                }
            }
        }
    }

    private void signalPrimaryInstance() {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT)) {
            socket.getOutputStream().write(1);
        } catch (IOException e) {
            log.warn("Could not signal the already-running instance; it may not come to front", e);
        }
    }

    @Override
    public void close() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }
    }
}
