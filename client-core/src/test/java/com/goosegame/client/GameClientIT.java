package com.goosegame.client;

import com.goosegame.protocol.Command;
import com.goosegame.protocol.Event;
import com.goosegame.protocol.JsonSerde;
import com.goosegame.protocol.MoveReason;
import com.goosegame.protocol.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link GameClient} against a real Kafka broker (Testcontainers), with no
 * server: the test writes the events itself, standing in for the server, and
 * reads the commands the client sends. That isolates what this module
 * promises — the replay, the filter by game, skipping unreadable records, and
 * the commands reaching {@code game.commands} — from the game rules.
 */
@Testcontainers
class GameClientIT {

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeAll
    static void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(Topics.COMMANDS, 3, (short) 1),
                    new NewTopic(Topics.EVENTS, 3, (short) 1))).all().get();
        }
    }

    @Test
    void viewIsRebuiltFromTheLogIgnoringOtherGamesAndUnreadableRecords() {
        String game = "replay-" + UUID.randomUUID().toString().substring(0, 8);
        publish(List.of(
                new Event.PlayerJoined(game, T, "alice"),
                new Event.PlayerJoined("other-" + game, T, "mallory"), // another game, same topic
                new Event.PlayerJoined(game, T, "bob")));
        publishRaw(game, "{not json");                                 // a poison pill
        publish(List.of(
                new Event.GameStarted(game, T, List.of("alice", "bob"), "alice"),
                new Event.DiceRolled(game, T, "alice", 2, 2),
                new Event.PlayerMoved(game, T, "alice", 0, 4, MoveReason.NORMAL),
                new Event.TurnStarted(game, T, "bob")));

        Predicate<GameView> bobToMove = v -> v.currentPlayer().equals(Optional.of("bob"));

        var first = new Recording();
        try (var client = GameClient.connect(KAFKA.getBootstrapServers(), game, first)) {
            GameView view = first.awaitView(bobToMove);
            assertEquals(GameView.Phase.RUNNING, view.phase());
            assertEquals(List.of("alice", "bob"), view.players());
            assertEquals(Map.of("alice", 4, "bob", 0), view.positions());
            assertEquals(view, client.view(), "view() publishes the latest fold");
            assertTrue(first.events.stream().allMatch(e -> e.gameId().equals(game)),
                    "only this game's events: " + first.events);
            assertEquals(6, first.events.size(),
                    "the poison pill and the other game are skipped: " + first.events);
        }

        // a second client, started after the fact, rebuilds the same view from the log alone
        var late = new Recording();
        try (var client = GameClient.connect(KAFKA.getBootstrapServers(), game, late)) {
            GameView view = late.awaitView(bobToMove);
            assertEquals(Map.of("alice", 4, "bob", 0), view.positions());
        }
    }

    @Test
    void commandsReachTheCommandsTopicKeyedByGame() {
        String game = "commands-" + UUID.randomUUID().toString().substring(0, 8);
        try (var client = GameClient.connect(KAFKA.getBootstrapServers(), game, new Recording())) {
            client.join("alice");
            client.start("alice");
            client.roll("alice");
        }
        List<ConsumerRecord<String, Command>> records = readCommands(game, 3);
        assertTrue(records.stream().allMatch(r -> r.key().equals(game)), "keyed by gameId");
        assertEquals(List.of(
                new Command.JoinGame(game, "alice"),
                new Command.StartGame(game, "alice"),
                new Command.RollDice(game, "alice")),
                records.stream().map(ConsumerRecord::value).toList());
    }

    /**
     * Records every event and hands every view to the test thread through a
     * queue, so the test waits for the view it expects instead of sleeping.
     */
    private static final class Recording implements GameListener {

        final List<Event> events = new CopyOnWriteArrayList<>();
        private final BlockingQueue<GameView> views = new LinkedBlockingQueue<>();

        @Override
        public void onEvent(Event event) {
            events.add(event);
        }

        @Override
        public void onViewUpdated(GameView view) {
            views.add(view);
        }

        GameView awaitView(Predicate<GameView> condition) {
            Instant deadline = Instant.now().plus(DEADLINE);
            GameView last = null;
            try {
                while (Instant.now().isBefore(deadline)) {
                    GameView view = views.poll(250, TimeUnit.MILLISECONDS);
                    if (view != null && condition.test(view)) {
                        return view;
                    }
                    last = view == null ? last : view;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fail("view did not reach the expected state within %s; last view: %s".formatted(DEADLINE, last));
        }
    }

    private static void publish(List<Event> events) {
        try (var producer = new KafkaProducer<String, Event>(
                producerConfig(), new StringSerializer(), new JsonSerde<>(Event.class))) {
            for (Event event : events) {
                producer.send(new ProducerRecord<>(Topics.EVENTS, event.gameId(), event));
            }
        }
    }

    private static void publishRaw(String key, String payload) {
        try (var producer = new KafkaProducer<String, byte[]>(
                producerConfig(), new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(Topics.EVENTS, key, payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static List<ConsumerRecord<String, Command>> readCommands(String game, int expected) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-observer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var found = new ArrayList<ConsumerRecord<String, Command>>();
        try (var consumer = new KafkaConsumer<String, Command>(
                props, new StringDeserializer(), new JsonSerde<>(Command.class))) {
            consumer.subscribe(List.of(Topics.COMMANDS));
            Instant deadline = Instant.now().plus(DEADLINE);
            while (found.size() < expected && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, Command> record : consumer.poll(Duration.ofMillis(250))) {
                    if (record.key().equals(game)) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    private static Properties producerConfig() {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }
}
