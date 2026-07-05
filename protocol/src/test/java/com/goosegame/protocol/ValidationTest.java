package com.goosegame.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Compact-constructor validation of every Command and Event record. */
class ValidationTest {

    private static final Instant NOW = Instant.parse("2026-07-02T10:00:00Z");

    @Test
    void validMessagesAreAccepted() {
        new Command.JoinGame("game-1", "alice");
        new Command.StartGame("game-1", "Bob_2");
        new Command.RollDice("game-1", "x".repeat(20));
        new Event.DiceRolled("game-1", NOW, "alice", 1, 6);
        new Event.PlayerMoved("game-1", NOW, "alice", 0, 63, MoveReason.NORMAL);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "way_too_long_for_a_player_name", "sp ace", "évil", "a;b", "<script>"})
    void badPlayerNameIsRejected(String name) {
        assertThrows(IllegalArgumentException.class, () -> new Command.JoinGame("game-1", name));
        assertThrows(IllegalArgumentException.class, () -> new Event.PlayerJoined("game-1", NOW, name));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "has space", "id/../../etc"})
    void badGameIdIsRejected(String gameId) {
        assertThrows(IllegalArgumentException.class, () -> new Command.RollDice(gameId, "alice"));
    }

    @Test
    void nullTimestampIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Event.PlayerJoined("game-1", null, "alice"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7, -1})
    void dieOutsideOneToSixIsRejected(int die) {
        assertThrows(IllegalArgumentException.class,
                () -> new Event.DiceRolled("game-1", NOW, "alice", die, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new Event.DiceRolled("game-1", NOW, "alice", 3, die));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 64})
    void squareOutsideBoardIsRejected(int square) {
        assertThrows(IllegalArgumentException.class,
                () -> new Event.PlayerMoved("game-1", NOW, "alice", square, 5, MoveReason.NORMAL));
        assertThrows(IllegalArgumentException.class,
                () -> new Event.PlayerStuck("game-1", NOW, "alice", square));
    }

    @Test
    void nullMoveReasonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Event.PlayerMoved("game-1", NOW, "alice", 1, 5, null));
    }

    @Test
    void gameStartedValidatesThePlayerList() {
        assertThrows(IllegalArgumentException.class, // empty
                () -> new Event.GameStarted("game-1", NOW, List.of(), "alice"));
        assertThrows(IllegalArgumentException.class, // duplicate names
                () -> new Event.GameStarted("game-1", NOW, List.of("alice", "alice"), "alice"));
        assertThrows(IllegalArgumentException.class, // firstPlayer not in list
                () -> new Event.GameStarted("game-1", NOW, List.of("alice", "bob"), "carol"));
        assertThrows(IllegalArgumentException.class, // invalid name inside the list
                () -> new Event.GameStarted("game-1", NOW, List.of("alice", "b b"), "alice"));
    }

    @Test
    void gameStartedCopiesThePlayerListDefensively() {
        var mutable = new ArrayList<>(List.of("alice", "bob"));
        var event = new Event.GameStarted("game-1", NOW, mutable, "alice");
        mutable.add("mallory");
        assertEquals(List.of("alice", "bob"), event.players());
        assertThrows(UnsupportedOperationException.class, () -> event.players().add("mallory"));
    }
}
