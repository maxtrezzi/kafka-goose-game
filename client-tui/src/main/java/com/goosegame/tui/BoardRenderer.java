package com.goosegame.tui;

import com.goosegame.client.GameView;
import com.goosegame.protocol.Event;

import java.util.List;
import java.util.Locale;

/**
 * Pure {@link GameView} → ANSI text rendering: the 63-square board as a
 * serpentine grid (square 1 bottom-left, snaking up to 63 top-left), player
 * tokens as colored initials, special squares marked, followed by a status
 * block and the recent-event log. No I/O — {@link Main} decides where the
 * string goes, tests just read it.
 */
final class BoardRenderer {

    private static final int COLUMNS = 9;
    private static final int ROWS = 7; // 63 squares
    private static final int CELL_WIDTH = 8;
    private static final int EVENT_LINES = 5;

    private static final String RESET = "\u001B[0m";
    private static final String DIM = "\u001B[2m";
    private static final String BOLD = "\u001B[1m";
    /** One color per join position; 6 colors for max 6 players. */
    private static final List<String> PLAYER_COLORS = List.of(
            "\u001B[31m", "\u001B[32m", "\u001B[33m", "\u001B[34m", "\u001B[35m", "\u001B[36m");
    private static final String TRAP_COLOR = "\u001B[31m";
    private static final String JUMP_COLOR = "\u001B[36m";
    private static final String GOOSE_COLOR = "\u001B[32m";

    private static final String LEGEND = """
            legend: * goose (roll again)   > bridge 6→12   < maze 42→39   X death 58→1
                    I inn (miss a turn)    W well          P prison (stuck until relieved)
            """;

    private BoardRenderer() {
    }

    static String render(GameView view) {
        var sb = new StringBuilder();
        appendBoard(sb, view);
        appendStatus(sb, view);
        appendEvents(sb, view);
        sb.append(DIM).append(LEGEND).append(RESET);
        return sb.toString();
    }

    private static void appendBoard(StringBuilder sb, GameView view) {
        for (int row = ROWS - 1; row >= 0; row--) {
            int first = row * COLUMNS + 1;
            for (int i = 0; i < COLUMNS; i++) {
                // serpentine: even rows run left→right, odd rows right→left
                int square = row % 2 == 0 ? first + i : first + COLUMNS - 1 - i;
                appendCell(sb, view, square);
            }
            sb.append('\n');
        }
    }

    private static void appendCell(StringBuilder sb, GameView view, int square) {
        char marker = marker(square);
        String markerColor = markerColor(square);
        int visible = 3; // "NN" + marker
        sb.append(markerColor)
                .append(String.format(Locale.ROOT, "%2d", square))
                .append(marker)
                .append(RESET);
        for (String player : view.players()) {
            if (view.positions().getOrDefault(player, -1) == square) {
                sb.append(colorOf(view, player))
                        .append(BOLD)
                        .append(initialOf(player))
                        .append(RESET);
                visible++;
            }
        }
        // 6 tokens + number + marker is 9 visible chars in an 8-wide cell:
        // give up one alignment column rather than throw on a valid pileup
        sb.append(" ".repeat(Math.max(0, CELL_WIDTH - visible)));
    }

    private static void appendStatus(StringBuilder sb, GameView view) {
        sb.append('\n')
                .append(BOLD).append("Game ").append(view.gameId()).append(RESET)
                .append(" — ").append(view.phase());
        view.winner().ifPresent(winner ->
                sb.append("   ").append(BOLD).append("*** ").append(winner).append(" WINS! ***").append(RESET));
        sb.append('\n');

        if (view.players().isEmpty()) {
            sb.append("nobody has joined yet\n");
            return;
        }
        for (String player : view.players()) {
            sb.append("  ").append(colorOf(view, player)).append(initialOf(player)).append(RESET)
                    .append(' ').append(player)
                    .append(" @").append(view.positions().getOrDefault(player, 0));
            Integer stuckOn = view.stuck().get(player);
            if (stuckOn != null) {
                sb.append(TRAP_COLOR).append(" (stuck ").append(squareName(stuckOn)).append(')').append(RESET);
            }
            if (view.currentPlayer().filter(player::equals).isPresent()) {
                sb.append(BOLD).append("  <- to move").append(RESET);
            }
            sb.append('\n');
        }
    }

    private static void appendEvents(StringBuilder sb, GameView view) {
        List<Event> events = view.recentEvents();
        if (events.isEmpty()) {
            return;
        }
        sb.append(DIM);
        events.stream()
                .skip(Math.max(0, events.size() - EVENT_LINES))
                .forEach(event -> sb.append("  · ").append(eventLine(event)).append('\n'));
        sb.append(RESET);
    }

    /** One human-readable line per event; exhaustive over the sealed hierarchy. */
    static String eventLine(Event event) {
        return switch (event) {
            case Event.PlayerJoined e -> e.player() + " joined the lobby";
            case Event.GameStarted e -> "game started: " + String.join(", ", e.players())
                    + " — " + e.firstPlayer() + " moves first";
            case Event.TurnStarted e -> e.player() + "'s turn";
            case Event.DiceRolled e -> "%s rolled %d+%d = %d"
                    .formatted(e.player(), e.die1(), e.die2(), e.die1() + e.die2());
            case Event.PlayerMoved e -> "%s moved %d -> %d (%s)"
                    .formatted(e.player(), e.from(), e.to(),
                            e.reason().name().toLowerCase(Locale.ROOT));
            case Event.PlayerStuck e -> e.player() + " is stuck " + squareName(e.square());
            case Event.PlayerFreed e -> e.player() + " is free again";
            case Event.GameWon e -> e.player() + " landed on 63 and WINS!";
        };
    }

    private static String squareName(int square) {
        return switch (square) {
            case 19 -> "at the inn (19)";
            case 31 -> "in the well (31)";
            case 52 -> "in prison (52)";
            default -> "on " + square;
        };
    }

    private static char marker(int square) {
        return switch (square) {
            case 6 -> '>';
            case 19 -> 'I';
            case 31 -> 'W';
            case 42 -> '<';
            case 52 -> 'P';
            case 58 -> 'X';
            case 5, 9, 14, 18, 23, 27, 32, 36, 41, 45, 50, 54, 59 -> '*';
            default -> ' ';
        };
    }

    private static String markerColor(int square) {
        return switch (marker(square)) {
            case 'I', 'W', 'P' -> TRAP_COLOR;
            case '>', '<', 'X' -> JUMP_COLOR;
            case '*' -> GOOSE_COLOR;
            default -> DIM;
        };
    }

    private static String colorOf(GameView view, String player) {
        int index = view.players().indexOf(player);
        return PLAYER_COLORS.get(Math.max(0, index) % PLAYER_COLORS.size());
    }

    private static char initialOf(String player) {
        return Character.toUpperCase(player.charAt(0));
    }
}
