package com.syziege.nation;

import com.syziege.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stores nations and the player -> nation index, persisted to nations.json.
 * Methods are synchronized so the data can later be read by the web map's
 * HTTP threads as well as the main thread.
 */
public final class NationStore {

    private final Path file;
    private final Logger logger;
    private final Map<String, Nation> nations = new LinkedHashMap<>(); // key: lower(name)
    private final Map<UUID, String> playerIndex = new LinkedHashMap<>(); // uuid -> key

    public NationStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public synchronized Nation byName(String name) {
        return name == null ? null : nations.get(key(name));
    }

    public synchronized Nation byPlayer(UUID uuid) {
        String k = playerIndex.get(uuid);
        return k == null ? null : nations.get(k);
    }

    public synchronized boolean exists(String name) {
        return nations.containsKey(key(name));
    }

    public synchronized Collection<Nation> all() {
        return new ArrayList<>(nations.values());
    }

    public synchronized Nation create(String name, UUID leader, String leaderName) {
        Nation nation = new Nation(name, leader, leaderName, System.currentTimeMillis());
        nations.put(key(name), nation);
        playerIndex.put(leader, key(name));
        save();
        return nation;
    }

    public synchronized void disband(Nation nation) {
        nations.remove(key(nation.name()));
        for (UUID member : nation.members().keySet()) {
            playerIndex.remove(member);
        }
        save();
    }

    public synchronized void addMember(Nation nation, UUID uuid, String name) {
        nation.members().put(uuid, name);
        playerIndex.put(uuid, key(nation.name()));
        save();
    }

    public synchronized void removeMember(Nation nation, UUID uuid) {
        nation.members().remove(uuid);
        playerIndex.remove(uuid);
        save();
    }

    /** Refreshes a member's cached name (e.g. on login) if it changed. */
    public synchronized void refreshName(UUID uuid, String name) {
        Nation nation = byPlayer(uuid);
        if (nation != null && !name.equals(nation.members().get(uuid))) {
            nation.members().put(uuid, name);
            save();
        }
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
            nations.clear();
            playerIndex.clear();
            Object list = ((Map<String, Object>) root).get("nations");
            if (!(list instanceof List)) {
                return;
            }
            for (Object obj : (List<Object>) list) {
                if (!(obj instanceof Map)) {
                    continue;
                }
                Map<String, Object> n = (Map<String, Object>) obj;
                String name = string(n.get("name"));
                String leaderStr = string(n.get("leader"));
                if (name == null || leaderStr == null) {
                    continue;
                }
                UUID leader = parseUuid(leaderStr);
                if (leader == null) {
                    continue;
                }
                long createdAt = n.get("createdAt") instanceof Number
                        ? ((Number) n.get("createdAt")).longValue() : System.currentTimeMillis();

                String leaderName = leaderStr;
                LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
                Object memberList = n.get("members");
                if (memberList instanceof List) {
                    for (Object mo : (List<Object>) memberList) {
                        if (!(mo instanceof Map)) {
                            continue;
                        }
                        Map<String, Object> m = (Map<String, Object>) mo;
                        UUID id = parseUuid(string(m.get("id")));
                        if (id == null) {
                            continue;
                        }
                        String mn = string(m.get("name"));
                        members.put(id, mn == null ? id.toString().substring(0, 8) : mn);
                        if (id.equals(leader) && mn != null) {
                            leaderName = mn;
                        }
                    }
                }

                Nation nation = new Nation(name, leader, leaderName, createdAt);
                nation.members().clear();
                if (members.isEmpty()) {
                    nation.members().put(leader, leaderName);
                } else {
                    nation.members().putAll(members);
                    nation.members().putIfAbsent(leader, leaderName);
                }
                nations.put(key(name), nation);
                for (UUID member : nation.members().keySet()) {
                    playerIndex.put(member, key(name));
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Failed to load nations from " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to save nations to " + file, e);
        }
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder("{\"nations\":[");
        boolean first = true;
        for (Nation nation : nations.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":").append(str(nation.name()))
                    .append(",\"leader\":").append(str(nation.leader().toString()))
                    .append(",\"createdAt\":").append(nation.createdAt())
                    .append(",\"members\":[");
            boolean firstMember = true;
            for (Map.Entry<UUID, String> member : nation.members().entrySet()) {
                if (!firstMember) {
                    sb.append(',');
                }
                firstMember = false;
                sb.append("{\"id\":").append(str(member.getKey().toString()))
                        .append(",\"name\":").append(str(member.getValue())).append('}');
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    private static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
