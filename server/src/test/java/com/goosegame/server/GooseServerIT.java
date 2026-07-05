package com.goosegame.server;

import com.goosegame.engine.DiceRoller;
import com.goosegame.engine.GameState;
import com.goosegame.protocol.Command;
import com.goosegame.protocol.Event;
import com.goosegame.protocol.JsonSerde;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end: a real Kafka broker (Testcontainers), the real server on a
 * virtual thread, and a scripted two-player game driven purely through the
 * topics — commands in, events out, no access to server internals.
 *
 * <p>The dice are injected as a fixed script, so the whole game — every move,
 * the goose jump, the bridge, the winner — is deterministic.
 */
@Testcontainers
class GooseServerIT {

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.0");

    private static final String GAME = "e2e";
    private static final Duration GAME_DEADLINE = Duration.ofSeconds(90);

    /**
     * Interleaved rolls, two per turn, alice first:
     * alice 12,12,12,12 then 3; bob always 3.
     * alice: 0 -> 12 -> 24 -> 36(goose)+12 -> 48 -> 60 -> 63 wins.
     * bob:   0 -> 3 -> 6(bridge) -> 12 -> 15 -> 18(goose)+3 -> 21.
     */
    private static final int[] DICE_SCRIPT = {
            6, 6, /* bob */ 1, 2,
            6, 6, /* bob */ 1, 2,
            6, 6, /* bob */ 1, 2,
            6, 6, /* bob */ 1, 2,
            1, 2
    };

    @Test
    void scriptedTwoPlayerGameIsPlayedToVictoryOverKafka() throws Exception {
        createTopics();
        var server = new GooseServer(KAFKA.getBootstrapServers(), scriptedDice());
        Thread serverThread = Thread.ofVirtual().name("goose-server").start(server::run);
        try {
            List<Event> history = driveGame();

            // opening: both joins, then the start with join order and first turn
            assertEquals(new Event.PlayerJoined(GAME, history.get(0).timestamp(), "alice"), history.get(0));
            assertEquals(new Event.PlayerJoined(GAME, history.get(1).timestamp(), "bob"), history.get(1));
            var started = assertInstanceOf(Event.GameStarted.class, history.get(2));
            assertEquals(List.of("alice", "bob"), started.players());
            assertEquals("alice", started.firstPlayer());

            // the scripted special squares actually happened
            assertTrue(history.stream().anyMatch(e -> e instanceof Event.PlayerMoved m
                    && m.player().equals("bob") && m.from() == 6 && m.to() == 12), "bob's bridge jump");
            assertTrue(history.stream().anyMatch(e -> e instanceof Event.PlayerMoved m
                    && m.player().equals("alice") && m.from() == 36 && m.to() == 48), "alice's goose hop");

            // ending: alice lands exactly on 63 and wins
            assertEquals(new Event.GameWon(GAME, history.getLast().timestamp(), "alice"), history.getLast());

            // the folded state agrees with the events
            GameState state = fold(history);
            assertEquals(GameState.Phase.FINISHED, state.phase());
            assertEquals(Optional.of("alice"), state.winner());
            assertEquals(Map.of("alice", 63, "bob", 21), state.positions());
        } finally {
            server.close();
            serverThread.join(Duration.ofSeconds(10));
        }
        assertFalse(serverThread.isAlive(), "server thread should have stopped");
    }

    /**
     * Plays the game from outside: sends the lobby commands, then reacts to
     * {@code GameStarted}/{@code TurnStarted} by sending exactly one
     * {@code RollDice} for the announced player, until {@code GameWon}.
     */
    private List<Event> driveGame() {
        var history = new ArrayList<Event>();
        try (var commands = new KafkaProducer<String, Command>(
                producerConfig(), new StringSerializer(), new JsonSerde<>(Command.class));
             var events = new KafkaConsumer<String, Event>(
                     consumerConfig(), new StringDeserializer(), new JsonSerde<>(Event.class))) {

            events.subscribe(List.of(GooseServer.EVENTS_TOPIC));
            send(commands, new Command.JoinGame(GAME, "alice"));
            send(commands, new Command.JoinGame(GAME, "bob"));
            send(commands, new Command.StartGame(GAME, "alice"));

            Instant deadline = Instant.now().plus(GAME_DEADLINE);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, Event> record : events.poll(Duration.ofMillis(250))) {
                    Event event = record.value();
                    history.add(event);
                    switch (event) {
                        case Event.GameStarted e -> send(commands, new Command.RollDice(GAME, e.firstPlayer()));
                        case Event.TurnStarted e -> send(commands, new Command.RollDice(GAME, e.player()));
                        case Event.GameWon e -> {
                            return history;
                        }
                        default -> { /* moves, rolls, joins: just recorded */ }
                    }
                }
            }
        }
        return fail("game did not finish within %s; events so far: %s".formatted(GAME_DEADLINE, history));
    }

    private static void send(KafkaProducer<String, Command> producer, Command command) {
        producer.send(new ProducerRecord<>(GooseServer.COMMANDS_TOPIC, command.gameId(), command));
        producer.flush();
    }

    private static GameState fold(List<Event> history) {
        GameState state = GameState.newGame(GAME);
        for (Event event : history) {
            state = state.apply(event);
        }
        return state;
    }

    private static DiceRoller scriptedDice() {
        var queue = new ArrayDeque<Integer>();
        Arrays.stream(DICE_SCRIPT).forEach(queue::add);
        return queue::remove; // exhaustion -> NoSuchElementException -> loud test failure
    }

    private static void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(GooseServer.COMMANDS_TOPIC, 3, (short) 1),
                    new NewTopic(GooseServer.EVENTS_TOPIC, 3, (short) 1))).all().get();
        }
    }

    private static Properties producerConfig() {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    private static Properties consumerConfig() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-observer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }
}
