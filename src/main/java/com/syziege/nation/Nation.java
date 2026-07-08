package com.syziege.nation;

import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * A nation: a named group of players with one leader. Membership is ordered
 * (leader first) and each member's last known name is cached for display when
 * the player is offline.
 */
public final class Nation {

    private final String name;
    private final UUID leader;
    private final long createdAt;
    /** member UUID -> last known name, leader included. */
    private final LinkedHashMap<UUID, String> members = new LinkedHashMap<>();

    public Nation(String name, UUID leader, String leaderName, long createdAt) {
        this.name = name;
        this.leader = leader;
        this.createdAt = createdAt;
        this.members.put(leader, leaderName);
    }

    public String name() {
        return name;
    }

    public UUID leader() {
        return leader;
    }

    public long createdAt() {
        return createdAt;
    }

    public LinkedHashMap<UUID, String> members() {
        return members;
    }

    public int size() {
        return members.size();
    }

    public boolean isLeader(UUID uuid) {
        return leader.equals(uuid);
    }

    public boolean isMember(UUID uuid) {
        return members.containsKey(uuid);
    }
}
