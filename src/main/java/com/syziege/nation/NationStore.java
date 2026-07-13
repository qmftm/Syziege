package com.syziege.nation;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Stores nations and the player -> nation index, persisted to nations.yml.
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

    /**
     * Finds a member across all nations by (case-insensitive) name, for admin
     * tools that must act on players who may be offline. Returns the member's
     * UUID, or null if no nation contains a member with that name.
     */
    public synchronized UUID findMemberByName(String name) {
        for (Nation nation : nations.values()) {
            for (Map.Entry<UUID, String> member : nation.members().entrySet()) {
                if (member.getValue().equalsIgnoreCase(name)) {
                    return member.getKey();
                }
            }
        }
        return null;
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

    // ---- persistence (YAML) ----

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            nations.clear();
            playerIndex.clear();
            for (Map<?, ?> entry : yaml.getMapList("nations")) {
                String name = str(entry.get("name"));
                UUID leader = uuid(entry.get("leader"));
                if (name == null || leader == null) {
                    continue;
                }
                long createdAt = entry.get("createdAt") instanceof Number
                        ? ((Number) entry.get("createdAt")).longValue() : System.currentTimeMillis();

                LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
                String leaderName = leader.toString().substring(0, 8);
                Object memberList = entry.get("members");
                if (memberList instanceof List) {
                    for (Object mo : (List<?>) memberList) {
                        if (!(mo instanceof Map)) {
                            continue;
                        }
                        Map<?, ?> m = (Map<?, ?>) mo;
                        UUID id = uuid(m.get("id"));
                        if (id == null) {
                            continue;
                        }
                        String mn = str(m.get("name"));
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
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Failed to load nations from " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            YamlConfiguration yaml = new YamlConfiguration();
            List<Map<String, Object>> list = new ArrayList<>();
            for (Nation nation : nations.values()) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("name", nation.name());
                n.put("leader", nation.leader().toString());
                n.put("createdAt", nation.createdAt());
                List<Map<String, Object>> members = new ArrayList<>();
                for (Map.Entry<UUID, String> member : nation.members().entrySet()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", member.getKey().toString());
                    m.put("name", member.getValue());
                    members.add(m);
                }
                n.put("members", members);
                list.add(n);
            }
            yaml.set("nations", list);
            yaml.save(file.toFile());
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Failed to save nations to " + file, e);
        }
    }

    private static UUID uuid(Object o) {
        if (!(o instanceof String)) {
            return null;
        }
        try {
            return UUID.fromString((String) o);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
