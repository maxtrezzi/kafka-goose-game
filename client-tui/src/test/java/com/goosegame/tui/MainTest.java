package com.goosegame.tui;

import com.goosegame.client.GameView;
import com.goosegame.protocol.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    private static final String GAME = "g";
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private static final GameView LOBBY = GameView.initial(GAME)
            .apply(new Event.PlayerJoined(GAME, T, "alice"))
            .apply(new Event.PlayerJoined(GAME, T, "bob"));
    private static final GameView RUNNING =
            LOBBY.apply(new Event.GameStarted(GAME, T, List.of("alice", "bob"), "alice"));
    private static final GameView FINISHED = RUNNING.apply(new Event.GameWon(GAME, T, "alice"));

    @Test
    void noHintWhenTheCommandIsExpectedToBeAccepted() {
        assertEquals(Optional.empty(), Main.likelyRejection("start", LOBBY, "alice"));
        assertEquals(Optional.empty(), Main.likelyRejection("roll", RUNNING, "alice"));
    }

    @Test
    void aPlayerWhoHasNotJoinedIsToldSo() {
        assertHint("has not joined", Main.likelyRejection("start", LOBBY, "carol"));
        assertHint("has not joined", Main.likelyRejection("roll", RUNNING, "carol"));
    }

    @Test
    void eachCommandGetsTheReasonThatFitsThePhase() {
        assertHint("already started", Main.likelyRejection("start", RUNNING, "alice"));
        assertHint("not started yet", Main.likelyRejection("roll", LOBBY, "alice"));
        assertHint("not bob's turn", Main.likelyRejection("roll", RUNNING, "bob"));
        assertHint("game is over", Main.likelyRejection("roll", FINISHED, "alice"));
    }

    private static void assertHint(String expected, Optional<String> hint) {
        assertTrue(hint.isPresent(), "expected a hint containing '" + expected + "'");
        assertTrue(hint.get().contains(expected), hint.get());
    }
}
