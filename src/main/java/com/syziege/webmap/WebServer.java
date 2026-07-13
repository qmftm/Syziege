package com.syziege.webmap;

import com.syziege.nation.Nation;
import com.syziege.nation.NationStore;
import com.syziege.region.RegionStore;
import com.syziege.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded HTTP server for the web map. Serves the read-only user viewer, the
 * admin region editor, map tiles, and JSON APIs. Admin mutations require the
 * configured admin key.
 */
public final class WebServer {

    private static final Pattern TILE_PATH =
            Pattern.compile("/tiles/([^/]+)/(-?\\d+)_(-?\\d+)\\.png");
    private static final int MAX_BODY = 1 << 20; // 1 MiB
    private static final int MAX_CHUNKS_PER_REQUEST = 20000;

    private static final String SESSION_COOKIE = "syziege_session";

    private final WorldRegistry worlds;
    private final TileService tiles;
    private final PlayerTracker players;
    private final ServerStateTracker serverState;
    private final RegionStore regions;
    private final NationStore nations;
    private final WebAuth auth;
    private final Function<String, InputStream> resource;
    private final String adminKey;
    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;

    public WebServer(WorldRegistry worlds, TileService tiles, PlayerTracker players,
                     ServerStateTracker serverState, RegionStore regions, NationStore nations,
                     WebAuth auth, Function<String, InputStream> resource, String adminKey, Logger logger) {
        this.worlds = worlds;
        this.tiles = tiles;
        this.players = players;
        this.serverState = serverState;
        this.regions = regions;
        this.nations = nations;
        this.auth = auth;
        this.resource = resource;
        this.adminKey = adminKey;
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
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange, path);
            } else if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange, path);
            } else {
                send(exchange, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
            }
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

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/") || path.equals("/index.html")) {
            servePage(exchange, "web/index.html");
            return;
        }
        if (path.equals("/admin") || path.equals("/admin.html")) {
            servePage(exchange, "web/admin.html");
            return;
        }
        if (path.matches("/[a-zA-Z0-9_-]+\\.(js|css)")) {
            serveAsset(exchange, "web" + path);
            return;
        }
        Matcher tile = TILE_PATH.matcher(path);
        if (tile.matches()) {
            serveTile(exchange, tile.group(1),
                    Integer.parseInt(tile.group(2)), Integer.parseInt(tile.group(3)));
            return;
        }
        switch (path) {
            case "/api/worlds":
                sendJson(exchange, 200, worldsJson());
                return;
            case "/api/players":
                servePlayers(exchange);
                return;
            case "/api/serverinfo":
                sendJson(exchange, 200, serverInfoJson());
                return;
            case "/api/regions":
                sendJson(exchange, 200, regions.toJson());
                return;
            case "/api/session":
                serveSession(exchange);
                return;
            default:
                send(exchange, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handlePost(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/login")) {
            handleLogin(exchange);
            return;
        }
        if (path.equals("/api/logout")) {
            auth.logout(sessionToken(exchange));
            exchange.getResponseHeaders().add("Set-Cookie",
                    SESSION_COOKIE + "=; Path=/; HttpOnly; Max-Age=0; SameSite=Lax");
            sendJson(exchange, 200, "{}");
            return;
        }
        if (!path.startsWith("/api/admin/")) {
            send(exchange, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!authorized(exchange)) {
            sendJson(exchange, 403, "{\"error\":\"invalid admin key\"}");
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        if (body == null) {
            sendJson(exchange, 400, "{\"error\":\"invalid JSON body\"}");
            return;
        }
        try {
            switch (path) {
                case "/api/admin/types":
                    handlePutType(exchange, body);
                    return;
                case "/api/admin/types/delete":
                    handleDeleteType(exchange, body);
                    return;
                case "/api/admin/claim":
                    handleClaim(exchange, body);
                    return;
                default:
                    send(exchange, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\":" + jsonString(e.getMessage()) + "}");
        }
    }

    private void handlePutType(HttpExchange exchange, Map<String, Object> body) throws IOException {
        String id = sanitizeId(asString(body.get("id")));
        String name = asString(body.get("name"));
        String color = normalizeColor(asString(body.get("color")));
        if (id == null || name == null || color == null) {
            throw new IllegalArgumentException("id, name and color are required");
        }
        regions.putType(id, name, color);
        sendJson(exchange, 200, regions.toJson());
    }

    private void handleDeleteType(HttpExchange exchange, Map<String, Object> body) throws IOException {
        String id = asString(body.get("id"));
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        regions.removeType(id);
        sendJson(exchange, 200, regions.toJson());
    }

    private void handleClaim(HttpExchange exchange, Map<String, Object> body) throws IOException {
        String world = asString(body.get("world"));
        if (world == null || worlds.get(world) == null) {
            throw new IllegalArgumentException("unknown world");
        }
        Object typeObj = body.get("type");
        String type = typeObj == null ? null : asString(typeObj); // null = unclaim
        Object chunksObj = body.get("chunks");
        if (!(chunksObj instanceof List)) {
            throw new IllegalArgumentException("chunks must be an array");
        }
        List<?> raw = (List<?>) chunksObj;
        if (raw.size() > MAX_CHUNKS_PER_REQUEST) {
            throw new IllegalArgumentException("too many chunks in one request");
        }
        List<int[]> chunks = new ArrayList<>(raw.size());
        for (Object obj : raw) {
            if (!(obj instanceof List) || ((List<?>) obj).size() < 2) {
                throw new IllegalArgumentException("each chunk must be [x, z]");
            }
            List<?> pair = (List<?>) obj;
            chunks.add(new int[]{asInt(pair.get(0)), asInt(pair.get(1))});
        }
        regions.claim(world, type, chunks);
        sendJson(exchange, 200, regions.toJson());
    }

    private boolean authorized(HttpExchange exchange) {
        if (adminKey == null || adminKey.isEmpty()) {
            return false; // admin API disabled until a key is configured
        }
        String header = exchange.getRequestHeaders().getFirst("X-Admin-Key");
        return adminKey.equals(header);
    }

    private void servePage(HttpExchange exchange, String resourcePath) throws IOException {
        try (InputStream in = resource.apply(resourcePath)) {
            if (in == null) {
                send(exchange, 500, "text/plain", (resourcePath + " missing").getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonBody(exchange);
        if (body == null) {
            sendJson(exchange, 400, "{\"error\":\"invalid JSON body\"}");
            return;
        }
        String token = auth.login(asString(body.get("name")), asString(body.get("password")));
        if (token == null) {
            sendJson(exchange, 401, "{\"error\":\"아이디 또는 비밀번호가 올바르지 않습니다\"}");
            return;
        }
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; Max-Age=604800; SameSite=Lax");
        UUID uuid = auth.sessionUser(token);
        sendJson(exchange, 200, "{\"name\":" + jsonString(auth.nameOf(uuid)) + "}");
    }

    private void serveSession(HttpExchange exchange) throws IOException {
        UUID uuid = auth.sessionUser(sessionToken(exchange));
        if (uuid == null) {
            sendJson(exchange, 200, "{\"loggedIn\":false}");
            return;
        }
        Nation nation = nations.byPlayer(uuid);
        sendJson(exchange, 200, "{\"loggedIn\":true,\"name\":" + jsonString(auth.nameOf(uuid))
                + ",\"nation\":" + (nation == null ? "null" : jsonString(nation.name())) + "}");
    }

    /** Serves only the logged-in player's own team (nation) member positions. */
    private void servePlayers(HttpExchange exchange) throws IOException {
        UUID uuid = auth.sessionUser(sessionToken(exchange));
        if (uuid == null) {
            sendJson(exchange, 401, "{\"error\":\"login required\"}");
            return;
        }
        Nation nation = nations.byPlayer(uuid);
        Set<UUID> allowed = nation == null ? Set.of(uuid) : nation.members().keySet();

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (PlayerTracker.PlayerInfo player : players.players()) {
            if (!allowed.contains(player.uuid)) {
                continue;
            }
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
        sendJson(exchange, 200, sb.append(']').toString());
    }

    private String sessionToken(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String header : cookies) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).trim().equals(SESSION_COOKIE)) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    private void serveAsset(HttpExchange exchange, String resourcePath) throws IOException {
        try (InputStream in = resource.apply(resourcePath)) {
            if (in == null) {
                send(exchange, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String type = resourcePath.endsWith(".css")
                    ? "text/css; charset=utf-8" : "application/javascript; charset=utf-8";
            exchange.getResponseHeaders().set("Cache-Control", "max-age=300");
            send(exchange, 200, type, in.readAllBytes());
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY);
        if (raw.length == 0) {
            return null;
        }
        try {
            Object parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (RuntimeException e) {
            return null;
        }
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

    private String serverInfoJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ServerStateTracker.WorldState state : serverState.states()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"world\":").append(jsonString(state.world))
                    .append(",\"time\":").append(state.timeOfDay)
                    .append(",\"day\":").append(state.day)
                    .append(",\"weather\":").append(jsonString(state.weather))
                    .append(",\"players\":").append(state.playerCount)
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private static String sanitizeId(String id) {
        if (id == null) {
            return null;
        }
        String cleaned = id.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        return cleaned.isEmpty() ? null : (cleaned.length() > 48 ? cleaned.substring(0, 48) : cleaned);
    }

    private static String normalizeColor(String color) {
        if (color == null) {
            return null;
        }
        String c = color.trim();
        if (!c.startsWith("#")) {
            c = "#" + c;
        }
        return c.matches("#[0-9a-fA-F]{6}") ? c.toLowerCase() : null;
    }

    private static String asString(Object o) {
        if (o instanceof String) {
            String s = ((String) o).trim();
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    private static int asInt(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        throw new IllegalArgumentException("expected a number");
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
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

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
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
