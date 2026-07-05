package com.goosegame.engine;

import com.goosegame.protocol.Event;
import com.goosegame.protocol.MoveReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameStateTest {

    private static final Instant NOW = Instant.parse("2026-07-02T12:00:00Z");

    @Test
    void newGameIsAnEmptyLobby() {
        var state = GameState.newGame("g");
        assertEquals(GameState.Phase.LOBBY, state.phase());
        assertTrue(state.players().isEmpty());
        assertTrue(state.positions().isEmpty());
        assertTrue(state.stuck().isEmpty());
        assertEquals(Optional.empty(), state.currentPlayer());
        assertEquals(Optional.empty(), state.winner());
    }

    @Test
    void foldingTheLogRebuildsTheState() {
        var state = GameState.newGame("g")
                .apply(new Event.PlayerJoined("g", NOW, "alice"))
                .apply(new Event.PlayerJoined("g", NOW, "bob"))
                .apply(new Event.GameStarted("g", NOW, List.of("alice", "bob"), "alice"))
                .apply(new Event.DiceRolled("g", NOW, "alice", 3, 4))
                .apply(new Event.PlayerMoved("g", NOW, "alice", 0, 7, MoveReason.NORMAL))
                .apply(new Event.TurnStarted("g", NOW, "bob"))
                .apply(new Event.DiceRolled("g", NOW, "bob", 1, 1))
                .apply(new Event.PlayerMoved("g", NOW, "bob", 0, 2, MoveReason.NORMAL))
                .apply(new Event.PlayerStuck("g", NOW, "bob", 19));

        assertEquals(GameState.Phase.RUNNING, state.phase());
        assertEquals(List.of("alice", "bob"), state.players());
        assertEquals(Map.of("alice", 7, "bob", 2), state.positions());
        assertEquals(Map.of("bob", 19), state.stuck());
        assertEquals(Optional.of("bob"), state.currentPlayer());

        var freed = state.apply(new Event.PlayerFreed("g", NOW, "bob"));
        assertTrue(freed.stuck().isEmpty());
    }

    @Test
    void gameWonFinishesTheGameAndClearsTheTurn() {
        var state = GameState.newGame("g")
                .apply(new Event.PlayerJoined("g", NOW, "alice"))
                .apply(new Event.PlayerJoined("g", NOW, "bob"))
                .apply(new Event.GameStarted("g", NOW, List.of("alice", "bob"), "alice"))
                .apply(new Event.GameWon("g", NOW, "alice"));
        assertEquals(GameState.Phase.FINISHED, state.phase());
        assertEquals(Optional.of("alice"), state.winner());
        assertEquals(Optional.empty(), state.currentPlayer());
    }

    @Test
    void diceRolledLeavesTheStateUntouched() {
        var state = GameState.newGame("g").apply(new Event.PlayerJoined("g", NOW, "alice"));
        assertSame(state, state.apply(new Event.DiceRolled("g", NOW, "alice", 2, 5)));
    }

    @Test
    void eventFromAnotherGameIsRejected() {
        var state = GameState.newGame("g");
        assertThrows(IllegalArgumentException.class,
                () -> state.apply(new Event.PlayerJoined("other", NOW, "alice")));
    }

    @Test
    void collectionsAreImmutable() {
        var state = GameState.newGame("g").apply(new Event.PlayerJoined("g", NOW, "alice"));
        assertThrows(UnsupportedOperationException.class, () -> state.players().add("mallory"));
        assertThrows(UnsupportedOperationException.class, () -> state.positions().put("mallory", 1));
        assertThrows(UnsupportedOperationException.class, () -> state.stuck().put("mallory", 19));
    }
}
