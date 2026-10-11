package org.wannes.plugModCore.web;

import org.junit.Test;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class WebListenerLifecycleTest {
    private static final class Listener implements WebListenerLifecycle.Listener {
        final int port; boolean active; boolean fail;
        Listener(int port) { this.port = port; }
        public void start() throws IOException { if (fail) throw new IOException("occupied"); active = true; }
        public void stop() { active = false; }
        public int port() { return port; }
    }
    @Test public void commitAndRollback() throws Exception {
        var instances = new ArrayList<Listener>();
        var lifecycle = new WebListenerLifecycle(port -> { var l = new Listener(port); instances.add(l); return l; });
        lifecycle.change(8080, p -> {});
        try { lifecycle.change(8081, p -> { assertTrue(instances.get(0).active); assertTrue(instances.get(1).active); throw new IOException("disk failure"); }); fail(); } catch (IOException expected) {}
        assertEquals(8080, lifecycle.port()); assertTrue(instances.get(0).active); assertFalse(instances.get(1).active);
        lifecycle.change(8081, p -> assertTrue(instances.get(0).active));
        assertFalse(instances.get(0).active); assertEquals(8081, lifecycle.port());
        lifecycle.change(8081, p -> fail("same port must not rewrite")); assertEquals(3, instances.size());
        lifecycle.stop(); assertEquals(-1, lifecycle.port()); assertFalse(instances.get(2).active);
    }
    @Test public void invalidPortsNeverCreateListener() throws Exception {
        var lifecycle = new WebListenerLifecycle(p -> { fail("invalid port reached factory"); return null; });
        for (int port : new int[]{0, -1, 65536}) try { lifecycle.change(port, p -> {}); fail(); } catch (IllegalArgumentException expected) {}
    }
    @Test public void realHttpBindReplacementAndOccupiedPort() throws Exception {
        var lifecycle = new WebListenerLifecycle(port -> new WebServer(null, "127.0.0.1", port) {
            @Override public Response serve(IHTTPSession request) { return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "auth required"); }
        });
        try (ServerSocket occupied = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            int first = freePort(), second = freePort();
            lifecycle.change(first, p -> {}); assertEquals(401, status(first));
            try { lifecycle.change(occupied.getLocalPort(), p -> fail("occupied port saved")); fail(); } catch (IOException expected) {}
            assertEquals(first, lifecycle.port()); assertEquals(401, status(first));
            lifecycle.change(second, p -> {}); assertEquals(401, status(second));
            try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", first), 1000); fail("old port still open"); } catch (IOException expected) {}
        } finally { lifecycle.stop(); }
    }
    private static int freePort() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static int status(int port) throws IOException {
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
        try { return connection.getResponseCode(); } finally { connection.disconnect(); }
    }
}
