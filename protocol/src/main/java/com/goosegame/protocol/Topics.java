package com.goosegame.protocol;

/**
 * The two Kafka topics that make up the wire protocol. Declared here — next to
 * the message types — so server and clients share one definition instead of
 * each hardcoding strings that must stay in sync.
 */
public final class Topics {

    /** Player intents ({@link Command}), keyed by gameId; consumed only by the server. */
    public static final String COMMANDS = "game.commands";

    /** Server-decided facts ({@link Event}), keyed by gameId; the log everyone folds. */
    public static final String EVENTS = "game.events";

    private Topics() {
    }
}
