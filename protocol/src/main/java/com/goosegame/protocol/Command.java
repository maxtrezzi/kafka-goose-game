package com.goosegame.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A player's intent, sent by clients to the {@code game.commands} topic.
 * The server is the only authority: a command produces zero or more {@link Event}s.
 *
 * <p>The hierarchy is sealed and mapped to JSON through an explicit, closed
 * {@code "type"} property — Jackson default typing is never enabled.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Command.JoinGame.class, name = "JoinGame"),
    @JsonSubTypes.Type(value = Command.StartGame.class, name = "StartGame"),
    @JsonSubTypes.Type(value = Command.RollDice.class, name = "RollDice")
})
public sealed interface Command {

    String gameId();

    /** The player issuing the command. */
    String player();

    /** Ask to join the lobby of {@code gameId} under the given name. */
    record JoinGame(String gameId, String player) implements Command {
        public JoinGame {
            gameId = Validation.gameId(gameId);
            player = Validation.playerName(player);
        }
    }

    /** Ask to start the game; only valid in the lobby phase. */
    record StartGame(String gameId, String player) implements Command {
        public StartGame {
            gameId = Validation.gameId(gameId);
            player = Validation.playerName(player);
        }
    }

    /** Ask to roll the dice; only valid on the player's own turn. */
    record RollDice(String gameId, String player) implements Command {
        public RollDice {
            gameId = Validation.gameId(gameId);
            player = Validation.playerName(player);
        }
    }
}
