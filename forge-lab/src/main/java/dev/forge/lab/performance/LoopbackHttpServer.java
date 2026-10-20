package dev.forge.lab.performance;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Creates the JDK's built-in {@link HttpServer} for services that scenarios start themselves:
 * bound to 127.0.0.1 on an ephemeral port (checked), with {@code TCP_NODELAY} on accepted sockets.
 *
 * <p>Why {@code TCP_NODELAY}: the JDK server writes the status line and the body as separate small
 * writes. With Nagle's algorithm on, the second write waits until the first is acknowledged, and a
 * client using delayed ACKs (Linux: up to 40 ms) acknowledges late. FORGE measured exactly that on
 * Linux — a 10 ms service answering in ≈ 55 ms in some trials and ≈ 13 ms in others — before this
 * setting existed. The property is read once, when the server implementation initialises, so it must
 * be set before the first server is created in the JVM; every embedded server goes through here.
 */
public final class LoopbackHttpServer {

    static {
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    private LoopbackHttpServer() {
    }

    public static HttpServer create(int backlog) throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        HttpServer s = HttpServer.create(new InetSocketAddress(loopback, 0), backlog);
        if (!s.getAddress().getAddress().isLoopbackAddress()) {
            s.stop(0);
            throw new IllegalStateException("embedded service must bind to loopback, got " + s.getAddress());
        }
        return s;
    }
}
