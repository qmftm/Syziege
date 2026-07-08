package com.syziege.webmap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded HTTP server for the web map. Serves the viewer page, map
 * tiles rendered from region files, and small JSON APIs.
 */
public final class WebServer {

    private static final Pattern TILE_PATH =
            Pattern.compile("/tiles/([^/]+)/(-?\\d+)_(-?\\d+)\\.png");

    private final WorldRegistry worlds;
    private final TileService tiles;
    private final PlayerTracker players;
    private final Supplier<InputStream> indexPage;
    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;

    public WebServer(WorldRegistry worlds, TileService tiles, PlayerTracker players,
                     Supplier<InputStream> indexPage, Logger logger) {
        this.worlds = worlds;
        this.tiles = tiles;
        this.players = players;
        this.indexPage = indexPage;
        this.logger = logger;
    }

    public void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "Syziege-WebMap");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        logger.info("Web map listening on " + bind + ":" + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                serveIndex(exchange);
                return;
            }
            Matcher tile = TILE_PATH.matcher(path);
            if (tile.matches()) {
                serveTile(exchange, tile.group(1),
                        Integer.parseInt(tile.group(2)), Integer.parseInt(tile.group(3)));
                return;
            }
            if (path.equals("/api/worlds")) {
                send(exchange, 200, "application/json", worldsJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/api/players")) {
                send(exchange, 200, "application/json", playersJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Web map request failed: " + exchange.getRequestURI(), e);
            try {
                send(exchange, 500, "text/plain", "Internal Server Error".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // response already started
            }
        } finally {
            exchange.close();
        }
    }

    private void serveIndex(HttpExchange exchange) throws IOException {
        try (InputStream in = indexPage.get()) {
            if (in == null) {
                send(exchange, 500, "text/plain", "index.html missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = in.readAllBytes();
            send(exchange, 200, "text/html; charset=utf-8", body);
        }
    }

    private void serveTile(HttpExchange exchange, String world, int regionX, int regionZ) throws IOException {
        byte[] png = tiles.getTile(world, regionX, regionZ);
        if (png == null) {
            exchange.getResponseHeaders().set("Cache-Control", "max-age=60");
            send(exchange, 404, "text/plain", "No such tile".getBytes(StandardCharsets.UTF_8));
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "max-age=15");
        send(exchange, 200, "image/png", png);
    }

    private String worldsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (WorldRegistry.WorldInfo world : worlds.all()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":").append(jsonString(world.name))
                    .append(",\"spawnX\":").append(world.spawnX)
                    .append(",\"spawnZ\":").append(world.spawnZ)
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private String playersJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (PlayerTracker.PlayerInfo player : players.players()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":").append(jsonString(player.name))
                    .append(",\"world\":").append(jsonString(player.world))
                    .append(",\"x\":").append(player.x)
                    .append(",\"y\":").append(player.y)
                    .append(",\"z\":").append(player.z)
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
