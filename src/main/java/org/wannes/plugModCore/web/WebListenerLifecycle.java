package org.wannes.plugModCore.web;

import java.io.IOException;

/** Transactional replacement of the existing HTTP listener, retaining it until commit. */
public final class WebListenerLifecycle {
    public interface Listener {
        void start() throws IOException;
        void stop();
        int port();
    }
    @FunctionalInterface public interface Factory { Listener create(int port); }
    @FunctionalInterface public interface PortWriter { void save(int port) throws IOException; }
    private final Factory factory;
    private Listener current;
    public WebListenerLifecycle(Factory factory) { this.factory = factory; }
    public synchronized int port() { return current == null ? -1 : current.port(); }
    public synchronized void change(int port, PortWriter writer) throws IOException {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be an integer from 1 to 65535.");
        if (current != null && current.port() == port) return;
        Listener candidate = factory.create(port);
        try {
            candidate.start(); // Includes an HTTP response check, not just a socket bind.
            writer.save(port);
        } catch (IOException | RuntimeException failure) {
            candidate.stop();
            throw failure;
        }
        Listener previous = current;
        current = candidate;
        if (previous != null) previous.stop();
    }
    public synchronized void stop() {
        if (current != null) current.stop();
        current = null;
    }
}
