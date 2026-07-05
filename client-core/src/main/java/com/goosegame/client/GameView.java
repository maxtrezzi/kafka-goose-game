package com.goosegame.client;

import com.goosegame.protocol.Event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, displayable snapshot of one game as seen by a client — a fold over
 * the {@code game.events} stream, like the server's state but shaped for UIs:
 * it additionally keeps a short log of the most recent events for rendering.
 *
 * <p>This deliberately re-implements the event fold instead of reusing the
 * engine's {@code GameState}: {@code client-core} depends only on
 * {@code protocol} (a client needs no game rules on its classpath), and the
 * wire protocol — not a shared class — is the contract that keeps the two
 * folds in agreement.
 *
 * @param gameId        the game this view belongs to
 * @param phase         lobby, running or finished
 * @param players       join order, which is also the turn order
 * @param positions     current square per player; 0 is the off-board start
 * @param stuck         players currently trapped, mapped to the trapping square
 * @param currentPlayer whose turn it is; empty in the lobby and after the game ends
 * @param winner        present only once the game is finished
 * @param recentEvents  the last {@value #RECENT_EVENTS_LIMIT} events, oldest first
 */
public record GameView(
        String gameId,
        Phase phase,
        List<String> players,
        Map<String, Integer> positions,
        Map<String, Integer> stuck,
        Optional<String> currentPlayer,
        Optional<String> winner,
        List<Event> recentEvents) {

    /** How many events {@link #recentEvents} keeps for display. */
    public static final int RECENT_EVENTS_LIMIT = 10;

    public enum Phase {
        LOBBY, RUNNING, FINISHED
    }

    public GameView {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(phase, "phase");
        players = List.copyOf(players);
        positions = Map.copyOf(positions);
        stuck = Map.copyOf(stuck);
        Objects.requireNonNull(currentPlayer, "currentPlayer");
        Objects.requireNonNull(winner, "winner");
        recentEvents = List.copyOf(recentEvents);
    }

    /** The view of a game nobody has joined yet. */
    public static GameView initial(String gameId) {
        return new GameView(gameId, Phase.LOBBY, List.of(), Map.of(), Map.of(),
                Optional.empty(), Optional.empty(), List.of());
    }

    /**
     * Folds one event into a new view and appends it to {@link #recentEvents}.
     * Exhaustive over the sealed {@link Event} hierarchy.
     *
     * @throws IllegalArgumentException if the event belongs to a different game
     */
    public GameView apply(Event event) {
        Objects.requireNonNull(event, "event");
        if (!event.gameId().equals(gameId)) {
            throw new IllegalArgumentException(
                    "event for game '%s' applied to view of game '%s'".formatted(event.gameId(), gameId));
        }
        GameView updated = switch (event) {
            case Event.PlayerJoined e -> withJoined(e.player());
            case Event.GameStarted e -> withStarted(e.players(), e.firstPlayer());
            case Event.TurnStarted e -> withCurrentPlayer(e.player());
            case Event.DiceRolled e -> this; // display-only: shown via recentEvents
            case Event.PlayerMoved e -> withPosition(e.player(), e.to());
            case Event.PlayerStuck e -> withStuck(e.player(), e.square());
            case Event.PlayerFreed e -> withFreed(e.player());
            case Event.GameWon e -> withWinner(e.player());
        };
        return updated.withLogged(event);
    }

    private GameView withJoined(String player) {
        var newPlayers = new ArrayList<>(players);
        newPlayers.add(player);
        var newPositions = new HashMap<>(positions);
        newPositions.put(player, 0);
        return new GameView(gameId, phase, newPlayers, newPositions, stuck,
                currentPlayer, winner, recentEvents);
    }

    private GameView withStarted(List<String> turnOrder, String firstPlayer) {
        return new GameView(gameId, Phase.RUNNING, turnOrder, positions, stuck,
                Optional.of(firstPlayer), winner, recentEvents);
    }

    private GameView withCurrentPlayer(String player) {
        return new GameView(gameId, phase, players, positions, stuck,
                Optional.of(player), winner, recentEvents);
    }

    private GameView withPosition(String player, int square) {
        var newPositions = new HashMap<>(positions);
        newPositions.put(player, square);
        return new GameView(gameId, phase, players, newPositions, stuck,
                currentPlayer, winner, recentEvents);
    }

    private GameView withStuck(String player, int square) {
        var newStuck = new HashMap<>(stuck);
        newStuck.put(player, square);
        return new GameView(gameId, phase, players, positions, newStuck,
                currentPlayer, winner, recentEvents);
    }

    private GameView withFreed(String player) {
        var newStuck = new HashMap<>(stuck);
        newStuck.remove(player);
        return new GameView(gameId, phase, players, positions, newStuck,
                currentPlayer, winner, recentEvents);
    }

    private GameView withWinner(String player) {
        return new GameView(gameId, Phase.FINISHED, players, positions, stuck,
                Optional.empty(), Optional.of(player), recentEvents);
    }

    private GameView withLogged(Event event) {
        var log = new ArrayList<>(recentEvents);
        log.add(event);
        if (log.size() > RECENT_EVENTS_LIMIT) {
            log.removeFirst();
        }
        return new GameView(gameId, phase, players, positions, stuck,
                currentPlayer, winner, log);
    }
}
