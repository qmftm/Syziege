package com.syziege.region;

import com.syziege.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stores region types (kinds of territory, each with a color) and the chunks
 * assigned to each type, per world. This is the foundation for the Towny-style
 * admin tools: admins group chunks into regions of different types.
 *
 * All mutating methods are synchronized and persist to a JSON file. Nothing
 * here touches the Bukkit API, so it is safe to call from HTTP threads.
 */
public final class RegionStore {

    /** A kind of territory: e.g. spawn, warzone, nation capital. */
    public static final class RegionType {
        public final String id;
        public String name;
        public String color;

        RegionType(String id, String name, String color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    /** The capture core of a region: the block attackers must contest. */
    public static final class Core {
        public final String world;
        public final int x;
        public final int y;
        public final int z;

        Core(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<String, RegionType> types = new LinkedHashMap<>();
    // world -> (packed chunk key -> type id)
    private final Map<String, Map<Long, String>> claims = new LinkedHashMap<>();
    // region type id -> capture core
    private final Map<String, Core> cores = new LinkedHashMap<>();

    public RegionStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int keyX(long k) {
        return (int) (k >> 32);
    }

    private static int keyZ(long k) {
        return (int) k;
    }

    // ---- mutations (admin) ----

    public synchronized void putType(String id, String name, String color) {
        RegionType existing = types.get(id);
        if (existing == null) {
            types.put(id, new RegionType(id, name, color));
        } else {
            existing.name = name;
            existing.color = color;
        }
        save();
    }

    public synchronized boolean removeType(String id) {
        if (types.remove(id) == null) {
            return false;
        }
        for (Map<Long, String> worldClaims : claims.values()) {
            worldClaims.values().removeIf(id::equals);
        }
        cores.remove(id);
        save();
        return true;
    }

    /** All region type display names, for tab completion. */
    public synchronized List<String> typeNames() {
        List<String> names = new ArrayList<>();
        for (RegionType type : types.values()) {
            names.add(type.name);
        }
        return names;
    }

    /** Finds a region type by its (case-insensitive) display name. */
    public synchronized RegionType typeByName(String name) {
        for (RegionType type : types.values()) {
            if (type.name.equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    /** Sets (or moves) the capture core of an existing region type. */
    public synchronized void setCore(String typeId, String world, int x, int y, int z) {
        if (!types.containsKey(typeId)) {
            throw new IllegalArgumentException("Unknown region type: " + typeId);
        }
        cores.put(typeId, new Core(world, x, y, z));
        save();
    }

    public synchronized Core getCore(String typeId) {
        return cores.get(typeId);
    }

    /** The region type id claiming the given chunk, or null. */
    public synchronized String claimAt(String world, int chunkX, int chunkZ) {
        Map<Long, String> worldClaims = claims.get(world);
        return worldClaims == null ? null : worldClaims.get(key(chunkX, chunkZ));
    }

    /**
     * Assigns or clears a set of chunks. When {@code typeId} is null the chunks
     * are unclaimed; otherwise they are assigned to that (existing) type.
     */
    public synchronized void claim(String world, String typeId, List<int[]> chunks) {
        if (typeId != null && !types.containsKey(typeId)) {
            throw new IllegalArgumentException("Unknown region type: " + typeId);
        }
        Map<Long, String> worldClaims = claims.computeIfAbsent(world, w -> new LinkedHashMap<>());
        for (int[] chunk : chunks) {
            long k = key(chunk[0], chunk[1]);
            if (typeId == null) {
                worldClaims.remove(k);
            } else {
                worldClaims.put(k, typeId);
            }
        }
        if (worldClaims.isEmpty()) {
            claims.remove(world);
        }
        save();
    }

    // ---- reads (both views) ----

    /** Full state as JSON: region types and per-world chunk claims. */
    public synchronized String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"types\":[");
        boolean first = true;
        for (RegionType type : types.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(str(type.id))
                    .append(",\"name\":").append(str(type.name))
                    .append(",\"color\":").append(str(type.color)).append('}');
        }
        sb.append("],\"claims\":{");
        first = true;
        for (Map.Entry<String, Map<Long, String>> world : claims.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(str(world.getKey())).append(":[");
            boolean firstChunk = true;
            for (Map.Entry<Long, String> chunk : world.getValue().entrySet()) {
                if (!firstChunk) {
                    sb.append(',');
                }
                firstChunk = false;
                sb.append("{\"x\":").append(keyX(chunk.getKey()))
                        .append(",\"z\":").append(keyZ(chunk.getKey()))
                        .append(",\"type\":").append(str(chunk.getValue())).append('}');
            }
            sb.append(']');
        }
        sb.append("},\"cores\":{");
        first = true;
        for (Map.Entry<String, Core> core : cores.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Core c = core.getValue();
            sb.append(str(core.getKey())).append(":{\"world\":").append(str(c.world))
                    .append(",\"x\":").append(c.x)
                    .append(",\"y\":").append(c.y)
                    .append(",\"z\":").append(c.z).append('}');
        }
        sb.append("}}");
        return sb.toString();
    }

    // ---- persistence ----

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return;
            }
            Object root = Json.parse(text);
            if (!(root instanceof Map)) {
                return;
            }
            types.clear();
            claims.clear();
            cores.clear();
            Map<String, Object> map = (Map<String, Object>) root;

            Object typeList = map.get("types");
            if (typeList instanceof List) {
                for (Object obj : (List<Object>) typeList) {
                    if (!(obj instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> t = (Map<String, Object>) obj;
                    String id = string(t.get("id"));
                    if (id == null) {
                        continue;
                    }
                    types.put(id, new RegionType(id,
                            string(t.getOrDefault("name", id)),
                            string(t.getOrDefault("color", "#888888"))));
                }
            }

            Object claimMap = map.get("claims");
            if (claimMap instanceof Map) {
                for (Map.Entry<String, Object> world : ((Map<String, Object>) claimMap).entrySet()) {
                    if (!(world.getValue() instanceof List)) {
                        continue;
                    }
                    Map<Long, String> worldClaims = new LinkedHashMap<>();
                    for (Object obj : (List<Object>) world.getValue()) {
                        if (!(obj instanceof Map)) {
                            continue;
                        }
                        Map<String, Object> c = (Map<String, Object>) obj;
                        String typeId = string(c.get("type"));
                        if (typeId == null || !types.containsKey(typeId)) {
                            continue;
                        }
                        worldClaims.put(key(intOf(c.get("x")), intOf(c.get("z"))), typeId);
                    }
                    if (!worldClaims.isEmpty()) {
                        claims.put(world.getKey(), worldClaims);
                    }
                }
            }

            Object coreMap = map.get("cores");
            if (coreMap instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) coreMap).entrySet()) {
                    if (!(entry.getValue() instanceof Map) || !types.containsKey(entry.getKey())) {
                        continue;
                    }
                    Map<String, Object> c = (Map<String, Object>) entry.getValue();
                    String world = string(c.get("world"));
                    if (world != null) {
                        cores.put(entry.getKey(),
                                new Core(world, intOf(c.get("x")), intOf(c.get("y")), intOf(c.get("z"))));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Failed to load regions from " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to save regions to " + file, e);
        }
    }

    private static int intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static String string(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static String str(String value) {
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
}
