package com.arif.companion;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves the companion resource pack ZIP over plain HTTP on the LAN, so Minecraft
 * clients can download it straight from the server instead of installing by hand.
 *
 * <p>Uses the JDK's built-in {@code com.sun.net.httpserver} — no extra dependency.
 * The server binds to all interfaces so both localhost clients and LAN players can
 * fetch it. Point {@code resource-pack=} in server.properties at
 * {@code http://<server-ip>:<port>/companion-voices-26.3.zip}.</p>
 *
 * <p>Single endpoint by design: the pack file only. Anything else gets 404, so this
 * never behaves as a general file server.</p>
 */
final class PackHttpServer {

    private final Logger log;
    private final File zipFile;
    private final int port;
    private HttpServer http;

    PackHttpServer(File zipFile, int port, Logger log) {
        this.zipFile = zipFile;
        this.port = port;
        this.log = log;
    }

    /** Binds the HTTP server; logs a SEVERE and stays down when the port is taken. */
    void start() {
        if (!zipFile.isFile()) {
            log.severe("Pack HTTP server disabled: ZIP not found at " + zipFile.getPath());
            return;
        }
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/", this::respond);
            // Requests never block the main thread; a tiny pool is plenty for a 1 MB file.
            http.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CompanionPack-HTTP");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            log.info("Pack HTTP server on port " + port + " serving " + zipFile.getName()
                    + " (" + zipFile.length() + " bytes).");
        } catch (IOException ex) {
            http = null;
            log.log(Level.SEVERE, "Could not start pack HTTP server on port " + port
                    + " — resource-pack URL will not work.", ex);
        }
    }

    void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
            log.info("Pack HTTP server stopped.");
        }
    }

    boolean isRunning() {
        return http != null;
    }

    /** The URL to write into server.properties, or null when the server never started. */
    String url(String fileName) {
        return isRunning() ? "http://localhost:" + port + "/" + fileName : null;
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // Only the exact pack file name is served; everything else is a 404.
        if (!path.equals("/" + zipFile.getName())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] data = Files.readAllBytes(zipFile.toPath());
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }
}