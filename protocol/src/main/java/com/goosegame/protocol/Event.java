package com.goosegame.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;

/**
 * A fact decided by the server, appended to the {@code game.events} topic.
 * Events are the single source of truth: every state (server or client) is a
 * fold over this sequence.
 *
 * <p>Sealed hierarchy mapped to JSON through an explicit, closed {@code "type"}
 * property — Jackson default typing is never enabled.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Event.PlayerJoined.class, name = "PlayerJoined"),
    @JsonSubTypes.Type(value = Event.GameStarted.class, name = "GameStarted"),
    @JsonSubTypes.Type(value = Event.TurnStarted.class, name = "TurnStarted"),
    @JsonSubTypes.Type(value = Event.DiceRolled.class, name = "DiceRolled"),
    @JsonSubTypes.Type(value = Event.PlayerMoved.class, name = "PlayerMoved"),
    @JsonSubTypes.Type(value = Event.PlayerStuck.class, name = "PlayerStuck"),
    @JsonSubTypes.Type(value = Event.PlayerFreed.class, name = "PlayerFreed"),
    @JsonSubTypes.Type(value = Event.GameWon.class, name = "GameWon")
})
public sealed interface Event {

    String gameId();

    Instant timestamp();

    /** A player entered the lobby. */
    record PlayerJoined(String gameId, Instant timestamp, String player) implements Event {
        public PlayerJoined {
            gameId = Validation.gameId(gameId);
            timestamp = Validation.timestamp(timestamp);
            player = Validation.playerName(player);
        }
    }

    /** The lobby closed and play began; {@code players} is the fixed turn order. */
    record GameStarted(String gameId, Instant timestamp, List<String> players, String firstPlayer)
            implements Event {
        public GameStarted {
            gameId = Validation.gameId(gameId);
            timestamp = Validation.timestamp(timestamp);
            players = Validation.playerNames(players);
            firstPlayer = Validation.playerName(firstPlayer);
            if (!players.contains(firstPlayer)) {
                throw new IllegalArgumentException(
                        "firstPlayer '%s' is not among the players %s".formatted(firstPlayer, players));
            }
        }
    }

    /** It is now {@code player}'s turn. */
    record TurnStarted(String gameId, Instant timestamp, String player) implements Event {
        public TurnStarted {
            gameId = Validation.gameId(gameId);
            timestamp = Validation.timestamp(timestamp);
            player = Validation.playerName(player);
        }
    }

    /** The server rolled two dice for {@code player}. */
    record DiceRolled(String gameId, Instant timestamp, String player, int die1, int die2)
            implements Event {
        public DiceRolled {
            gameId = Validation.gameId(gameId);
            timestamp = Validation.timestamp(timestamp);
            player = Validation.playerName(player);
            die1 = Validation.die(die1);
            die2 = Validation.die(die2);
        }
    }

    /** A token moved on the board; one roll can produce several of these (goose chains, bounce…). */
    record PlayerMoved(String gameId, Instant timestamp, String player, int from, int to, MoveReason reason)
            implements Event {
        public PlayerMoved {
            gameId = Validation.gameId(gameId);
            timestamp = Validation.timestamp(timestamp);
            player = Validation.playerName(player);
            from = Validation.square(from);
            to = Validation.square(to);
            Validation.notNull(reason, "reason");
        }
    }

    /** {@code player} is trapped on {@code square} (inn 19, well 31 or prison 52). */
    record PlayerStuck(String gameId, Instant timestamp, String player, int square) implements Event {
        public PlayerStuck {
            gameId = Validation.gameId(gameId);
            timestamp = Validation.timestamp(timestamp);
            player = Validation.playerName(player);
            square = Validation.square(square);
        }
    }

    /** {@code player} is no longer trapped and plays again on their next turn. */
    record PlayerFreed(String gameId, Instant timestamp, String player) implements Event {
        public PlayerFreed {
            gameId = Validation.gameId(gameId);
            timestamp = Validation.timestamp(timestamp);
            player = Validation.playerName(player);
        }
    }

    /** {@code player} landed exactly on 63. The game is over. */
    record GameWon(String gameId, Instant timestamp, String player) implements Event {
        public GameWon {
            gameId = Validation.gameId(gameId);
            timestamp = Validation.timestamp(timestamp);
            player = Validation.playerName(player);
        }
    }
}
