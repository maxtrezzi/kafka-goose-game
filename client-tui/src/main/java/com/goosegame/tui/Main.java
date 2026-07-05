package com.goosegame.tui;

import com.goosegame.client.GameClient;
import com.goosegame.client.GameListener;
import com.goosegame.client.GameView;
import com.goosegame.protocol.Event;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Terminal client. Usage:
 * {@code mvn -pl client-tui exec:java -Dexec.args="[bootstrap [gameId [player]]]"}
 * — defaults: {@code localhost:9092 game-1 <os user name>}.
 *
 * <p>The board reprints on every event (listener callbacks arrive on the
 * client's event-loop thread); stdin is read on the main thread. Both write to
 * {@code System.out} — an occasional interleaved prompt is the accepted cost of
 * keeping this a plain-stdio TUI.
 */
public final class Main {

    private static final String CLEAR_SCREEN = "\u001B[H\u001B[2J";

    private static final String HELP = """
            commands:
              join [name]   ask to join the game (default: your player name)
              start         start the game (2-6 players, from the lobby)
              roll          roll the dice on your turn
              board         reprint the board
              help          this text
              quit          leave (the game goes on; rejoin to catch up by replay)
            """;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        // kafka-clients INFO chatter would scribble all over the board
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");

        String bootstrap = arg(args, 0, "localhost:9092");
        String gameId = arg(args, 1, "game-1");
        var me = new AtomicReference<>(arg(args, 2, defaultName()));

        var out = System.out;
        GameListener listener = new GameListener() {
            @Override
            public void onEvent(Event event) {
                // the view carries the recent-event log; nothing extra to do
            }

            @Override
            public void onViewUpdated(GameView view) {
                out.print(CLEAR_SCREEN + BoardRenderer.render(view) + "\n> ");
                out.flush();
            }
        };

        try (var client = GameClient.connect(bootstrap, gameId, listener);
             var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            out.printf("connected to %s — game '%s', you are '%s'%n", bootstrap, gameId, me.get());
            out.print(HELP + "> ");
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                if (!handle(line.strip(), client, me, out)) {
                    break;
                }
                out.print("> ");
                out.flush();
            }
        }
        out.println("bye!");
    }

    /** @return false when the user asked to quit */
    private static boolean handle(
            String line, GameClient client, AtomicReference<String> me, PrintStream out) {
        String[] words = line.split("\\s+");
        try {
            switch (words[0]) {
                case "join" -> {
                    String name = words.length > 1 ? words[1] : me.get();
                    client.join(name);
                    me.set(name); // from now on, start/roll act as this player
                }
                case "start" -> client.start(me.get());
                case "roll" -> client.roll(me.get());
                case "board", "" -> out.print(CLEAR_SCREEN + BoardRenderer.render(client.view()) + "\n");
                case "help" -> out.print(HELP);
                case "quit", "exit" -> {
                    return false;
                }
                default -> out.println("unknown command — try 'help'");
            }
        } catch (IllegalArgumentException e) {
            // a bad name typed at the prompt is a user error, not a crash
            out.println("invalid: " + e.getMessage());
        }
        return true;
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }

    /** OS user name squeezed into the protocol's [A-Za-z0-9_-]{1,20} shape. */
    private static String defaultName() {
        String user = System.getProperty("user.name", "player")
                .replaceAll("[^A-Za-z0-9_-]", "");
        if (user.isEmpty()) {
            return "player";
        }
        return user.length() <= 20 ? user : user.substring(0, 20);
    }
}
