package com.goosegame.tui;

import com.goosegame.client.GameView;
import com.goosegame.protocol.Event;
import com.goosegame.protocol.MoveReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardRendererTest {

    private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");
    private static final String GAME = "g";
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    private static String plain(GameView view) {
        return ANSI.matcher(BoardRenderer.render(view)).replaceAll("");
    }

    @Test
    void boardIsASerpentineOfSevenRows() {
        String[] lines = plain(GameView.initial(GAME)).split("\n");
        // top row runs 55..63 left to right, the row below runs 54..46
        assertTrue(lines[0].startsWith("55 "), lines[0]);
        assertTrue(lines[0].contains("63"), lines[0]);
        assertTrue(lines[1].startsWith("54*"), lines[1]);
        assertTrue(lines[1].contains("46"), lines[1]);
        // bottom row starts at 1 and ends the board
        assertTrue(lines[6].startsWith(" 1 "), lines[6]);
        assertTrue(lines[6].contains(" 9*"), lines[6]);
    }

    @Test
    void specialSquaresAreMarked() {
        String board = plain(GameView.initial(GAME));
        assertTrue(board.contains(" 6>"), "bridge");
        assertTrue(board.contains("19I"), "inn");
        assertTrue(board.contains("31W"), "well");
        assertTrue(board.contains("42<"), "maze");
        assertTrue(board.contains("52P"), "prison");
        assertTrue(board.contains("58X"), "death");
        assertTrue(board.contains(" 5*"), "goose");
    }

    @Test
    void tokensAppearOnTheirSquares() {
        var view = startedGame()
                .apply(new Event.PlayerMoved(GAME, NOW, "alice", 0, 5, MoveReason.NORMAL))
                .apply(new Event.PlayerMoved(GAME, NOW, "bob", 0, 12, MoveReason.NORMAL));
        String board = plain(view);
        assertTrue(board.contains(" 5*A"), "alice token on 5");
        assertTrue(board.contains("12 B"), "bob token on 12");
    }

    @Test
    void statusShowsTurnStuckAndPositions() {
        var view = startedGame()
                .apply(new Event.PlayerMoved(GAME, NOW, "bob", 0, 19, MoveReason.NORMAL))
                .apply(new Event.PlayerStuck(GAME, NOW, "bob", 19));
        String text = plain(view);
        assertTrue(text.contains("alice @0"), text);
        assertTrue(text.contains("bob @19"), text);
        assertTrue(text.contains("(stuck at the inn (19))"), text);
        assertTrue(text.contains("alice @0  <- to move"), text);
    }

    @Test
    void winnerBannerIsShown() {
        var view = startedGame().apply(new Event.GameWon(GAME, NOW, "alice"));
        assertTrue(plain(view).contains("*** alice WINS! ***"));
    }

    @Test
    void recentEventsAreRenderedAsLines() {
        var view = startedGame()
                .apply(new Event.DiceRolled(GAME, NOW, "alice", 3, 4));
        String text = plain(view);
        assertTrue(text.contains("· alice rolled 3+4 = 7"), text);
        assertTrue(text.contains("· game started: alice, bob — alice moves first"), text);
    }

    @Test
    void eventLinesCoverEveryEventType() {
        assertEquals("alice joined the lobby",
                BoardRenderer.eventLine(new Event.PlayerJoined(GAME, NOW, "alice")));
        assertEquals("alice moved 60 -> 61 (bounce)",
                BoardRenderer.eventLine(new Event.PlayerMoved(GAME, NOW, "alice", 60, 61, MoveReason.BOUNCE)));
        assertEquals("bob is stuck in the well (31)",
                BoardRenderer.eventLine(new Event.PlayerStuck(GAME, NOW, "bob", 31)));
        assertEquals("bob is free again",
                BoardRenderer.eventLine(new Event.PlayerFreed(GAME, NOW, "bob")));
        assertEquals("alice landed on 63 and WINS!",
                BoardRenderer.eventLine(new Event.GameWon(GAME, NOW, "alice")));
        assertEquals("bob's turn",
                BoardRenderer.eventLine(new Event.TurnStarted(GAME, NOW, "bob")));
    }

    @Test
    void sixPlayersOnOneSquareStillRender() {
        var view = GameView.initial(GAME);
        var names = List.of("ann", "ben", "cleo", "dan", "eve", "fay");
        for (String name : names) {
            view = view.apply(new Event.PlayerJoined(GAME, NOW, name));
        }
        view = view.apply(new Event.GameStarted(GAME, NOW, names, "ann"));
        for (String name : names) {
            view = view.apply(new Event.PlayerMoved(GAME, NOW, name, 0, 12, MoveReason.NORMAL));
        }
        String board = plain(view); // must not throw despite the overfull cell
        assertTrue(board.contains("12 ABCDEF"), board);
    }

    @Test
    void renderIsAnsiColoredButStripsClean() {
        String raw = BoardRenderer.render(startedGame());
        assertTrue(raw.contains("\u001B["), "expected ANSI colors in raw output");
        String stripped = ANSI.matcher(raw).replaceAll("");
        assertTrue(stripped.indexOf('\u001B') < 0, "no stray escapes after stripping");
    }

    private static GameView startedGame() {
        return GameView.initial(GAME)
                .apply(new Event.PlayerJoined(GAME, NOW, "alice"))
                .apply(new Event.PlayerJoined(GAME, NOW, "bob"))
                .apply(new Event.GameStarted(GAME, NOW, List.of("alice", "bob"), "alice"));
    }
}
