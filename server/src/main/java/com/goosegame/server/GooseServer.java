package com.goosegame.server;

import com.goosegame.engine.DiceRoller;
import com.goosegame.engine.GameEngine;
import com.goosegame.engine.GameState;
import com.goosegame.protocol.Command;
import com.goosegame.protocol.Event;
import com.goosegame.protocol.JsonSerde;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * The authoritative game host. On {@link #run()} it first replays the full
 * {@code game.events} topic to rebuild every game's state, then consumes
 * {@code game.commands}: each command goes through {@link GameEngine#decide},
 * the resulting events are produced back to {@code game.events} (keyed by
 * gameId, so one game's history stays in one partition, in order) and folded
 * into the local state.
 *
 * <p><b>Threading:</b> the server is single-threaded by design — the thread
 * that calls {@code run()} owns every Kafka client and all game state, so the
 * state map needs no synchronization. {@link #close()} may be called from any
 * thread: it only signals shutdown ({@link KafkaConsumer#wakeup()} is the one
 * thread-safe consumer method) and waits; the run thread closes its own
 * resources on the way out.
 *
 * <p><b>Delivery:</b> the producer is idempotent with {@code acks=all}; offsets
 * are committed only after every produced event is acknowledged, giving
 * at-least-once processing — a crash between produce and commit replays the
 * command, which the (deterministic-per-state) engine mostly rejects, but a
 * redelivered {@code RollDice} rolls again. Exactly-once would need Kafka
 * transactions; deliberately out of scope for this project.
 *
 * <p>Poison pills (malformed commands or events) are logged and skipped by
 * seeking past them, never crashing the loop.
 */
public final class GooseServer implements AutoCloseable {

    public static final String COMMANDS_TOPIC = "game.commands";
    public static final String EVENTS_TOPIC = "game.events";

    private static final String GROUP_ID = "goose-server";
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(GooseServer.class);

    private final String bootstrapServers;
    private final DiceRoller dice;
    private final GameEngine engine = new GameEngine();
    private final Map<String, GameState> states = new HashMap<>(); // confined to the run() thread
    private final AtomicReference<KafkaConsumer<?, ?>> activeConsumer = new AtomicReference<>();
    private final CountDownLatch shutdownComplete = new CountDownLatch(1);
    private volatile boolean running = true;
    private volatile boolean started;

    public GooseServer(String bootstrapServers, DiceRoller dice) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        this.dice = Objects.requireNonNull(dice, "dice");
    }

    public static void main(String[] args) {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";
        var server = new GooseServer(bootstrap, secureDice());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "goose-server-shutdown"));
        log.info("goose-server starting against {}", bootstrap);
        server.run();
    }

    /** Dice are only ever rolled here, server-side, from a CSPRNG. */
    private static DiceRoller secureDice() {
        RandomGenerator random = new SecureRandom();
        return () -> random.nextInt(6) + 1;
    }

    /**
     * Replays history, then processes commands until {@link #close()} is called.
     * Blocks the calling thread for the server's whole lifetime; run it on its
     * own (virtual) thread if the caller needs to keep going.
     */
    public void run() {
        started = true;
        try (var producer = new KafkaProducer<>(
                producerConfig(), new StringSerializer(), new JsonSerde<>(Event.class))) {
            replayEvents();
            processCommands(producer);
        } finally {
            shutdownComplete.countDown();
            log.info("goose-server stopped");
        }
    }

    /** Signals the run thread to stop and waits for it to release its resources. */
    @Override
    public void close() {
        running = false;
        var consumer = activeConsumer.get();
        if (consumer != null) {
            try {
                consumer.wakeup();
            } catch (IllegalStateException alreadyClosed) {
                // run() is past polling and shutting down on its own
            }
        }
        if (!started) {
            return;
        }
        try {
            if (!shutdownComplete.await(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("server loop did not stop within {}", CLOSE_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void replayEvents() {
        try (var consumer = new KafkaConsumer<>(
                replayConfig(), new StringDeserializer(), new JsonSerde<>(Event.class))) {
            activeConsumer.set(consumer);
            List<PartitionInfo> infos = consumer.partitionsFor(EVENTS_TOPIC);
            if (infos == null || infos.isEmpty()) {
                throw new IllegalStateException(
                        "topic '%s' does not exist — is the cluster up and init-topics done?"
                                .formatted(EVENTS_TOPIC));
            }
            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(EVENTS_TOPIC, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            long replayed = 0;
            while (running && !caughtUp(consumer, endOffsets)) {
                for (ConsumerRecord<String, Event> record : poll(consumer)) {
                    applyEvent(record.value());
                    replayed++;
                }
            }
            log.info("replayed {} event(s) into {} game(s)", replayed, states.size());
        } catch (WakeupException e) {
            log.info("replay interrupted by shutdown");
        } finally {
            activeConsumer.set(null);
        }
    }

    private static boolean caughtUp(KafkaConsumer<?, ?> consumer, Map<TopicPartition, Long> endOffsets) {
        return endOffsets.entrySet().stream()
                .allMatch(end -> consumer.position(end.getKey()) >= end.getValue());
    }

    private void processCommands(KafkaProducer<String, Event> producer) {
        try (var consumer = new KafkaConsumer<>(
                commandsConfig(), new StringDeserializer(), new JsonSerde<>(Command.class))) {
            activeConsumer.set(consumer);
            consumer.subscribe(List.of(COMMANDS_TOPIC));
            while (running) {
                ConsumerRecords<String, Command> records = poll(consumer);
                if (records.isEmpty()) {
                    continue;
                }
                var pending = new ArrayList<Future<RecordMetadata>>();
                for (ConsumerRecord<String, Command> record : records) {
                    handleCommand(record.value(), producer, pending);
                }
                producer.flush();
                awaitAll(pending); // never commit offsets for events that failed to reach the log
                consumer.commitSync();
            }
        } catch (WakeupException e) {
            log.info("command loop interrupted by shutdown");
        } finally {
            activeConsumer.set(null);
        }
    }

    private void handleCommand(
            Command command, KafkaProducer<String, Event> producer, List<Future<RecordMetadata>> pending) {
        if (command == null) {
            return; // tombstone — nothing to decide
        }
        GameState state = states.computeIfAbsent(command.gameId(), GameState::newGame);
        List<Event> events = engine.decide(state, command, dice);
        if (events.isEmpty()) {
            log.info("rejected {}", command);
            return;
        }
        for (Event event : events) {
            pending.add(producer.send(new ProducerRecord<>(EVENTS_TOPIC, event.gameId(), event)));
        }
        for (Event event : events) {
            applyEvent(event);
        }
        log.info("{} -> {} event(s)", command, events.size());
    }

    private void applyEvent(Event event) {
        if (event == null) {
            return; // tombstone or skipped poison pill in the log
        }
        states.compute(event.gameId(), (gameId, state) ->
                (state == null ? GameState.newGame(gameId) : state).apply(event));
    }

    /** Polls, seeking past any record that cannot be deserialized (poison pill). */
    private <T> ConsumerRecords<String, T> poll(KafkaConsumer<String, T> consumer) {
        while (true) {
            try {
                return consumer.poll(POLL_TIMEOUT);
            } catch (RecordDeserializationException e) {
                log.warn("skipping poison pill at {} offset {}: {}",
                        e.topicPartition(), e.offset(), e.getCause() == null ? e : e.getCause().toString());
                consumer.seek(e.topicPartition(), e.offset() + 1);
            }
        }
    }

    private static void awaitAll(List<Future<RecordMetadata>> pending) {
        for (Future<RecordMetadata> future : pending) {
            try {
                future.get(); // already completed by flush(); surfaces produce failures
            } catch (ExecutionException e) {
                throw new IllegalStateException("producing events failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while confirming produced events", e);
            }
        }
    }

    private Properties producerConfig() {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return props;
    }

    private Properties commandsConfig() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    /** Replay uses manual partition assignment — no group, no commits. */
    private Properties replayConfig() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }
}
