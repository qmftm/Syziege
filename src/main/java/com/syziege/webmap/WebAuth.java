package com.syziege.webmap;

import com.syziege.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Web map accounts and login sessions. Each player has a password (shown
 * in-game via /webmap pw) used to log into the user map; a successful login
 * gets a random session token stored in memory. Passwords persist to
 * webusers.json; sessions do not survive a restart.
 */
public final class WebAuth {

    private static final long SESSION_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final String PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    private static final class User {
        final UUID id;
        String name;
        String password;

        User(UUID id, String name, String password) {
            this.id = id;
            this.name = name;
            this.password = password;
        }
    }

    private static final class Session {
        final UUID uuid;
        final long expiry;

        Session(UUID uuid, long expiry) {
            this.uuid = uuid;
            this.expiry = expiry;
        }
    }

    private final Path file;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, User> users = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public WebAuth(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Returns the player's password, creating one on first use. */
    public synchronized String ensurePassword(UUID uuid, String name) {
        User user = users.get(uuid);
        if (user == null) {
            user = new User(uuid, name, randomString(8));
            users.put(uuid, user);
        } else {
            user.name = name;
        }
        save();
        return user.password;
    }

    /** Regenerates and returns a new password for the player. */
    public synchronized String resetPassword(UUID uuid, String name) {
        User user = new User(uuid, name, randomString(8));
        users.put(uuid, user);
        // Invalidate existing sessions for this player.
        sessions.values().removeIf(s -> s.uuid.equals(uuid));
        save();
        return user.password;
    }

    /** Verifies a name/password login, returning a new session token or null. */
    public synchronized String login(String name, String password) {
        if (name == null || password == null) {
            return null;
        }
        for (User user : users.values()) {
            if (user.name.equalsIgnoreCase(name) && constantTimeEquals(user.password, password)) {
                String token = randomString(32);
                sessions.put(token, new Session(user.id, System.currentTimeMillis() + SESSION_TTL_MILLIS));
                return token;
            }
        }
        return null;
    }

    /** The player UUID behind a session token, or null if missing/expired. */
    public synchronized UUID sessionUser(String token) {
        if (token == null) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.expiry < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return session.uuid;
    }

    public synchronized String nameOf(UUID uuid) {
        User user = users.get(uuid);
        return user == null ? null : user.name;
    }

    public synchronized void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
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
            if (!(root instanceof List)) {
                return;
            }
            users.clear();
            for (Object obj : (List<Object>) root) {
                if (!(obj instanceof Map)) {
                    continue;
                }
                Map<String, Object> u = (Map<String, Object>) obj;
                String id = string(u.get("id"));
                String name = string(u.get("name"));
                String password = string(u.get("password"));
                if (id == null || name == null || password == null) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(id);
                    users.put(uuid, new User(uuid, name, password));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entry
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Failed to load web users from " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (User user : users.values()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"id\":").append(str(user.id.toString()))
                        .append(",\"name\":").append(str(user.name))
                        .append(",\"password\":").append(str(user.password)).append('}');
            }
            sb.append(']');
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to save web users to " + file, e);
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
