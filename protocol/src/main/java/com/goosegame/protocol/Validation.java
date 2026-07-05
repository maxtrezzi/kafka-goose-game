package com.goosegame.protocol;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared checks for the compact constructors of {@link Command} and {@link Event}
 * records. Everything that crosses the wire is validated here, so no invalid
 * message can ever be constructed — locally or via deserialization.
 */
final class Validation {

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final Pattern GAME_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private Validation() {
    }

    static String gameId(String gameId) {
        notNull(gameId, "gameId");
        if (!GAME_ID.matcher(gameId).matches()) {
            throw new IllegalArgumentException(
                    "gameId must be 1-64 chars of [A-Za-z0-9_-], got: '%s'".formatted(gameId));
        }
        return gameId;
    }

    static String playerName(String player) {
        notNull(player, "player");
        if (!PLAYER_NAME.matcher(player).matches()) {
            throw new IllegalArgumentException(
                    "player name must be 1-20 chars of [A-Za-z0-9_-], got: '%s'".formatted(player));
        }
        return player;
    }

    /** Validates every name, rejects duplicates, and returns an immutable copy. */
    static List<String> playerNames(List<String> players) {
        notNull(players, "players");
        if (players.isEmpty()) {
            throw new IllegalArgumentException("players must not be empty");
        }
        players.forEach(Validation::playerName);
        if (new HashSet<>(players).size() != players.size()) {
            throw new IllegalArgumentException("players contains duplicates: " + players);
        }
        return List.copyOf(players);
    }

    static Instant timestamp(Instant timestamp) {
        notNull(timestamp, "timestamp");
        return timestamp;
    }

    static int die(int value) {
        if (value < 1 || value > 6) {
            throw new IllegalArgumentException("die must be 1-6, got: " + value);
        }
        return value;
    }

    /** Board squares run 1-63; 0 is the off-board start position. */
    static int square(int value) {
        if (value < 0 || value > 63) {
            throw new IllegalArgumentException("square must be 0-63, got: " + value);
        }
        return value;
    }

    static void notNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
    }
}
