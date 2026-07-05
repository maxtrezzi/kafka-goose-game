package com.goosegame.engine;

import com.goosegame.protocol.Command;
import com.goosegame.protocol.Event;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rule book: {@link #decide} turns a command into the events it causes —
 * a pure function of state, command and dice, with no side effects. Invalid
 * commands (wrong phase, out of turn, duplicate name…) produce no events;
 * the server logs and moves on.
 *
 * <p>Turn flow after a roll: the token moves per {@link Board#resolve}; landing
 * exactly on 63 wins; landing on the inn (19) traps the player for one missed
 * turn; landing on the well (31) or prison (52) traps until another player
 * lands there and takes the place — with one exception: the last free player
 * never gets trapped. Without it, two trapped players (well + prison) freeze
 * the game forever; instead the lander rests on the square unharmed and play
 * continues. (Players at the inn don't count as trapped for this rule — the
 * rotation frees them by itself.)
 */
public final class GameEngine {

    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 6;

    private final Clock clock;

    public GameEngine() {
        this(Clock.systemUTC());
    }

    /** @param clock source of event timestamps; tests inject a fixed clock */
    public GameEngine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Decides which events {@code command} causes on {@code state}.
     *
     * @return the events, in application order; empty if the command is rejected
     */
    public List<Event> decide(GameState state, Command command, DiceRoller dice) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(dice, "dice");
        if (!command.gameId().equals(state.gameId())) {
            return List.of();
        }
        return switch (command) {
            case Command.JoinGame c -> joinGame(state, c);
            case Command.StartGame c -> startGame(state, c);
            case Command.RollDice c -> rollDice(state, c, dice);
        };
    }

    private List<Event> joinGame(GameState state, Command.JoinGame command) {
        boolean accepted = state.phase() == GameState.Phase.LOBBY
                && !state.players().contains(command.player())
                && state.players().size() < MAX_PLAYERS;
        if (!accepted) {
            return List.of();
        }
        return List.of(new Event.PlayerJoined(state.gameId(), clock.instant(), command.player()));
    }

    private List<Event> startGame(GameState state, Command.StartGame command) {
        boolean accepted = state.phase() == GameState.Phase.LOBBY
                && state.players().contains(command.player())
                && state.players().size() >= MIN_PLAYERS;
        if (!accepted) {
            return List.of();
        }
        return List.of(new Event.GameStarted(
                state.gameId(), clock.instant(), state.players(), state.players().getFirst()));
    }

    private List<Event> rollDice(GameState state, Command.RollDice command, DiceRoller dice) {
        String player = command.player();
        boolean accepted = state.phase() == GameState.Phase.RUNNING
                && state.currentPlayer().map(player::equals).orElse(false)
                && !state.stuck().containsKey(player); // unreachable via turn logic, guarded anyway
        if (!accepted) {
            return List.of();
        }
        Instant now = clock.instant();
        String gameId = state.gameId();
        var events = new ArrayList<Event>();

        int die1 = dice.roll();
        int die2 = dice.roll();
        events.add(new Event.DiceRolled(gameId, now, player, die1, die2));

        List<Board.Move> moves = Board.resolve(state.positions().get(player), die1 + die2);
        for (Board.Move move : moves) {
            events.add(new Event.PlayerMoved(gameId, now, player, move.from(), move.to(), move.reason()));
        }

        int landed = moves.getLast().to();
        if (landed == Board.LAST_SQUARE) {
            events.add(new Event.GameWon(gameId, now, player));
            return List.copyOf(events);
        }
        if (Board.traps(landed) && !freezesTheGame(state, player, landed)) {
            events.add(new Event.PlayerStuck(gameId, now, player, landed));
            if (Board.holdsUntilReplaced(landed)) {
                state.stuck().entrySet().stream()
                        .filter(e -> e.getValue() == landed && !e.getKey().equals(player))
                        .findFirst() // at most one: the trap always swaps its occupant
                        .ifPresent(e -> events.add(new Event.PlayerFreed(gameId, now, e.getKey())));
            }
        }

        GameState after = state;
        for (Event event : events) {
            after = after.apply(event);
        }
        events.addAll(advanceTurn(after, now));
        return List.copyOf(events);
    }

    /**
     * True when trapping {@code lander} on {@code landed} would leave nobody
     * able to move — every other player already held in the well/prison and no
     * occupant on this square for the swap to free. In that case the trap is
     * waived: the last free player never gets stuck.
     */
    private static boolean freezesTheGame(GameState state, String lander, int landed) {
        if (!Board.holdsUntilReplaced(landed)) {
            return false; // the inn releases by itself; it can never freeze the game
        }
        if (state.stuck().containsValue(landed)) {
            return false; // the swap frees the occupant, so someone stays free
        }
        return state.players().stream()
                .filter(player -> !player.equals(lander))
                .allMatch(player -> {
                    Integer square = state.stuck().get(player);
                    return square != null && Board.holdsUntilReplaced(square);
                });
    }

    /**
     * Picks the next player to move, starting after the current one. Players in
     * the well/prison are skipped; a player at the inn is freed but misses this
     * one turn (they become eligible again when the rotation next reaches them —
     * with two players that is the very next pass).
     */
    private List<Event> advanceTurn(GameState state, Instant now) {
        var events = new ArrayList<Event>();
        List<String> players = state.players();
        int current = players.indexOf(state.currentPlayer().orElseThrow());
        GameState folded = state;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i <= players.size(); i++) {
                String candidate = players.get((current + i) % players.size());
                Integer square = folded.stuck().get(candidate);
                if (square == null) {
                    events.add(new Event.TurnStarted(folded.gameId(), now, candidate));
                    return events;
                }
                if (pass == 0 && square == Board.INN) {
                    var freed = new Event.PlayerFreed(folded.gameId(), now, candidate);
                    events.add(freed);
                    folded = folded.apply(freed);
                }
            }
        }
        // Unreachable through decide() since the last free player never gets
        // trapped, but a replayed log predating that rule could still fold to
        // an all-trapped state: emit no turn rather than a wrong one.
        return events;
    }
}
