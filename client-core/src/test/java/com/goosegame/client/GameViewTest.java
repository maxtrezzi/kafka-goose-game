package com.goosegame.client;

import com.goosegame.protocol.Event;
import com.goosegame.protocol.MoveReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay logic: folding a scripted event sequence produces the expected view. */
class GameViewTest {

    private static final Instant NOW = Instant.parse("2026-07-03T10:00:00Z");
    private static final String GAME = "g";

    @Test
    void initialViewIsAnEmptyLobby() {
        var view = GameView.initial(GAME);
        assertEquals(GameView.Phase.LOBBY, view.phase());
        assertTrue(view.players().isEmpty());
        assertTrue(view.positions().isEmpty());
        assertTrue(view.stuck().isEmpty());
        assertEquals(Optional.empty(), view.currentPlayer());
        assertEquals(Optional.empty(), view.winner());
        assertTrue(view.recentEvents().isEmpty());
    }

    @Test
    void foldingAScriptedGameProducesTheExpectedView() {
        var view = fold(GameView.initial(GAME),
                new Event.PlayerJoined(GAME, NOW, "alice"),
                new Event.PlayerJoined(GAME, NOW, "bob"),
                new Event.GameStarted(GAME, NOW, List.of("alice", "bob"), "alice"),
                new Event.DiceRolled(GAME, NOW, "alice", 3, 4),
                new Event.PlayerMoved(GAME, NOW, "alice", 0, 7, MoveReason.NORMAL),
                new Event.TurnStarted(GAME, NOW, "bob"),
                new Event.DiceRolled(GAME, NOW, "bob", 1, 1),
                new Event.PlayerMoved(GAME, NOW, "bob", 0, 2, MoveReason.NORMAL),
                new Event.TurnStarted(GAME, NOW, "alice"));

        assertEquals(GameView.Phase.RUNNING, view.phase());
        assertEquals(List.of("alice", "bob"), view.players());
        assertEquals(Map.of("alice", 7, "bob", 2), view.positions());
        assertEquals(Optional.of("alice"), view.currentPlayer());
        assertEquals(Optional.empty(), view.winner());
    }

    @Test
    void gameStartedSetsTheFirstTurnWithoutASeparateTurnStarted() {
        var view = fold(GameView.initial(GAME),
                new Event.PlayerJoined(GAME, NOW, "alice"),
                new Event.PlayerJoined(GAME, NOW, "bob"),
                new Event.GameStarted(GAME, NOW, List.of("alice", "bob"), "alice"));
        assertEquals(GameView.Phase.RUNNING, view.phase());
        assertEquals(Optional.of("alice"), view.currentPlayer());
    }

    @Test
    void stuckAndFreedAreTracked() {
        var view = fold(startedGame(),
                new Event.PlayerStuck(GAME, NOW, "alice", 19));
        assertEquals(Map.of("alice", 19), view.stuck());

        view = view.apply(new Event.PlayerFreed(GAME, NOW, "alice"));
        assertTrue(view.stuck().isEmpty());
    }

    @Test
    void gameWonFinishesTheGameAndClearsTheTurn() {
        var view = fold(startedGame(),
                new Event.PlayerMoved(GAME, NOW, "alice", 0, 63, MoveReason.NORMAL),
                new Event.GameWon(GAME, NOW, "alice"));
        assertEquals(GameView.Phase.FINISHED, view.phase());
        assertEquals(Optional.of("alice"), view.winner());
        assertEquals(Optional.empty(), view.currentPlayer());
        assertEquals(63, view.positions().get("alice"));
    }

    @Test
    void recentEventsKeepsTheLastTenOldestFirst() {
        var view = startedGame(); // already 3 events in the log
        for (int roll = 0; roll < 12; roll++) {
            view = view.apply(new Event.DiceRolled(GAME, NOW, "alice", 1, 2));
        }
        assertEquals(GameView.RECENT_EVENTS_LIMIT, view.recentEvents().size());
        // the 15 folded events ended with 12 identical rolls: all survivors are rolls
        assertTrue(view.recentEvents().stream().allMatch(Event.DiceRolled.class::isInstance));

        var won = view.apply(new Event.GameWon(GAME, NOW, "alice"));
        assertEquals(GameView.RECENT_EVENTS_LIMIT, won.recentEvents().size());
        assertEquals(new Event.GameWon(GAME, NOW, "alice"), won.recentEvents().getLast());
    }

    @Test
    void everyFoldedEventIsLogged() {
        var view = startedGame();
        assertEquals(3, view.recentEvents().size());
        assertTrue(view.recentEvents().getFirst() instanceof Event.PlayerJoined);
        assertTrue(view.recentEvents().getLast() instanceof Event.GameStarted);
    }

    @Test
    void eventFromAnotherGameIsRejected() {
        var view = GameView.initial(GAME);
        assertThrows(IllegalArgumentException.class,
                () -> view.apply(new Event.PlayerJoined("other", NOW, "alice")));
    }

    @Test
    void collectionsAreImmutable() {
        var view = startedGame();
        assertThrows(UnsupportedOperationException.class, () -> view.players().add("mallory"));
        assertThrows(UnsupportedOperationException.class, () -> view.positions().put("mallory", 1));
        assertThrows(UnsupportedOperationException.class, () -> view.stuck().put("mallory", 19));
        assertThrows(UnsupportedOperationException.class,
                () -> view.recentEvents().add(new Event.PlayerJoined(GAME, NOW, "mallory")));
    }

    @Test
    void restartMidGameRebuildsTheSameViewByReplay() {
        Event[] history = {
                new Event.PlayerJoined(GAME, NOW, "alice"),
                new Event.PlayerJoined(GAME, NOW, "bob"),
                new Event.GameStarted(GAME, NOW, List.of("alice", "bob"), "alice"),
                new Event.DiceRolled(GAME, NOW, "alice", 6, 6),
                new Event.PlayerMoved(GAME, NOW, "alice", 0, 12, MoveReason.NORMAL),
                new Event.TurnStarted(GAME, NOW, "bob")
        };
        var live = fold(GameView.initial(GAME), history);
        var replayed = fold(GameView.initial(GAME), history);
        assertEquals(live, replayed);
    }

    private static GameView startedGame() {
        return fold(GameView.initial(GAME),
                new Event.PlayerJoined(GAME, NOW, "alice"),
                new Event.PlayerJoined(GAME, NOW, "bob"),
                new Event.GameStarted(GAME, NOW, List.of("alice", "bob"), "alice"));
    }

    private static GameView fold(GameView view, Event... events) {
        for (Event event : events) {
            view = view.apply(event);
        }
        return view;
    }
}
