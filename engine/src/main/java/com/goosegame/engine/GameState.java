package com.goosegame.engine;

import com.goosegame.protocol.Event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of one game, defined entirely as a fold over the
 * {@link Event} log: {@code state.apply(event)} returns the next state and is
 * the single place where events acquire meaning. Server and clients rebuild
 * the same state by replaying the same events.
 *
 * <p>The fold trusts its input: events are assumed to come from the
 * server-authoritative log in emission order. Cross-event invariants (e.g.
 * that a {@code TurnStarted} names a player who joined) are not re-checked
 * here — they are guaranteed by {@link GameEngine} at decision time.
 *
 * @param gameId        the game this state belongs to
 * @param phase         lobby, running or finished
 * @param players       join order, which is also the fixed turn order
 * @param positions     current square per player; 0 is the off-board start
 * @param stuck         players currently trapped, mapped to the trapping square
 *                      (inn 19, well 31 or prison 52)
 * @param currentPlayer whose turn it is; empty in the lobby and after the game ends
 * @param winner        the winner; present only in the {@code FINISHED} phase
 */
public record GameState(
        String gameId,
        Phase phase,
        List<String> players,
        Map<String, Integer> positions,
        Map<String, Integer> stuck,
        Optional<String> currentPlayer,
        Optional<String> winner) {

    public enum Phase {
        LOBBY, RUNNING, FINISHED
    }

    public GameState {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(phase, "phase");
        players = List.copyOf(players);
        positions = Map.copyOf(positions);
        stuck = Map.copyOf(stuck);
        Objects.requireNonNull(currentPlayer, "currentPlayer");
        Objects.requireNonNull(winner, "winner");
    }

    /** A fresh game in the lobby phase with no players. */
    public static GameState newGame(String gameId) {
        return new GameState(gameId, Phase.LOBBY, List.of(), Map.of(), Map.of(),
                Optional.empty(), Optional.empty());
    }

    /**
     * Folds one event into a new state. Exhaustive over the sealed {@link Event}
     * hierarchy — adding an event type will not compile until it is handled here.
     *
     * @throws IllegalArgumentException if the event belongs to a different game
     */
    public GameState apply(Event event) {
        Objects.requireNonNull(event, "event");
        if (!event.gameId().equals(gameId)) {
            throw new IllegalArgumentException(
                    "event for game '%s' applied to game '%s'".formatted(event.gameId(), gameId));
        }
        return switch (event) {
            case Event.PlayerJoined e -> join(e.player());
            case Event.GameStarted e -> start(e.players(), e.firstPlayer());
            case Event.TurnStarted e -> withCurrentPlayer(e.player());
            case Event.DiceRolled e -> this; // informational: the moves carry the state change
            case Event.PlayerMoved e -> withPosition(e.player(), e.to());
            case Event.PlayerStuck e -> withStuck(e.player(), e.square());
            case Event.PlayerFreed e -> withFreed(e.player());
            case Event.GameWon e -> won(e.player());
        };
    }

    private GameState join(String player) {
        var newPlayers = new ArrayList<>(players);
        newPlayers.add(player);
        var newPositions = new HashMap<>(positions);
        newPositions.put(player, 0);
        return new GameState(gameId, phase, newPlayers, newPositions, stuck, currentPlayer, winner);
    }

    private GameState start(List<String> turnOrder, String firstPlayer) {
        return new GameState(gameId, Phase.RUNNING, turnOrder, positions, stuck,
                Optional.of(firstPlayer), winner);
    }

    private GameState withCurrentPlayer(String player) {
        return new GameState(gameId, phase, players, positions, stuck,
                Optional.of(player), winner);
    }

    private GameState withPosition(String player, int square) {
        var newPositions = new HashMap<>(positions);
        newPositions.put(player, square);
        return new GameState(gameId, phase, players, newPositions, stuck, currentPlayer, winner);
    }

    private GameState withStuck(String player, int square) {
        var newStuck = new HashMap<>(stuck);
        newStuck.put(player, square);
        return new GameState(gameId, phase, players, positions, newStuck, currentPlayer, winner);
    }

    private GameState withFreed(String player) {
        var newStuck = new HashMap<>(stuck);
        newStuck.remove(player);
        return new GameState(gameId, phase, players, positions, newStuck, currentPlayer, winner);
    }

    private GameState won(String player) {
        return new GameState(gameId, Phase.FINISHED, players, positions, stuck,
                Optional.empty(), Optional.of(player));
    }
}
