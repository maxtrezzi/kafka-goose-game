package com.goosegame.client;

import com.goosegame.protocol.Command;
import com.goosegame.protocol.Event;
import com.goosegame.protocol.JsonSerde;
import com.goosegame.protocol.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UI-agnostic client for one game: sends {@link Command}s and follows the
 * {@code game.events} topic on its own virtual thread, folding this game's
 * events into a {@link GameView} and notifying a {@link GameListener}.
 *
 * <p>Every client instance uses a fresh consumer group reading from the
 * earliest offset, so a client started (or restarted) mid-game rebuilds the
 * whole view by replay — the Kafka log, not the client, is the source of truth.
 *
 * <p><b>Threading:</b> the event-loop virtual thread owns the consumer and the
 * fold; listener callbacks run on it. Command-sending methods and
 * {@link #close()} may be called from any thread (the producer is thread-safe;
 * shutdown is signalled via {@link KafkaConsumer#wakeup()}). A listener
 * exception is logged and skipped — a UI bug must not stop the event stream.
 */
public final class GameClient implements AutoCloseable {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(GameClient.class);

    private final String bootstrapServers;
    private final String gameId;
    private final GameListener listener;
    private final KafkaProducer<String, Command> producer;
    private final Thread eventLoop;
    private final AtomicReference<KafkaConsumer<String, Event>> activeConsumer = new AtomicReference<>();
    private volatile GameView view;
    private volatile boolean running = true;

    private GameClient(String bootstrapServers, String gameId, GameListener listener) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.view = GameView.initial(gameId);
        this.producer = new KafkaProducer<>(
                producerConfig(), new StringSerializer(), new JsonSerde<>(Command.class));
        this.eventLoop = Thread.ofVirtual()
                .name("goose-client-" + gameId)
                .unstarted(this::runEventLoop); // started by connect(), after construction
    }

    /** Connects to the cluster and starts following {@code gameId}'s events. */
    public static GameClient connect(String bootstrapServers, String gameId, GameListener listener) {
        var client = new GameClient(bootstrapServers, gameId, listener);
        client.eventLoop.start();
        return client;
    }

    /** Ask to join the game under {@code player}. The server decides. */
    public void join(String player) {
        send(new Command.JoinGame(gameId, player));
    }

    /** Ask to start the game. Only valid from the lobby, issued by a member. */
    public void start(String player) {
        send(new Command.StartGame(gameId, player));
    }

    /** Ask to roll the dice. Only valid on {@code player}'s own turn. */
    public void roll(String player) {
        send(new Command.RollDice(gameId, player));
    }

    /** The most recently folded view; {@link GameView#initial} until events arrive. */
    public GameView view() {
        return view;
    }

    /** Signals the event loop to stop, waits for it, and releases the producer. */
    @Override
    public void close() {
        running = false;
        var consumer = activeConsumer.get();
        if (consumer != null) {
            try {
                consumer.wakeup();
            } catch (IllegalStateException alreadyClosed) {
                // event loop is past polling and shutting down on its own
            }
        }
        try {
            if (!eventLoop.join(CLOSE_TIMEOUT)) {
                log.warn("event loop did not stop within {}", CLOSE_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        producer.close();
    }

    private void send(Command command) {
        // the log line is the only failure signal a lost command gets — never drop it
        producer.send(new ProducerRecord<>(Topics.COMMANDS, gameId, command),
                (metadata, e) -> {
                    if (e != null) {
                        log.error("failed to send {}", command, e);
                    }
                });
        producer.flush(); // human-scale traffic: prompt delivery over batching
    }

    private void runEventLoop() {
        try (var consumer = new KafkaConsumer<>(
                consumerConfig(), new StringDeserializer(), new JsonSerde<>(Event.class))) {
            activeConsumer.set(consumer);
            consumer.subscribe(List.of(Topics.EVENTS));
            while (running) {
                for (ConsumerRecord<String, Event> record : poll(consumer)) {
                    Event event = record.value();
                    if (event == null || !event.gameId().equals(gameId)) {
                        continue; // tombstone, or another game's event on the shared topic
                    }
                    view = view.apply(event);
                    notifyListener(event, view);
                }
            }
        } catch (WakeupException e) {
            log.debug("event loop interrupted by shutdown");
        } finally {
            activeConsumer.set(null);
        }
    }

    private void notifyListener(Event event, GameView updated) {
        try {
            listener.onEvent(event);
            listener.onViewUpdated(updated);
        } catch (RuntimeException e) {
            log.error("listener failed on {}", event, e);
        }
    }

    /** Polls, seeking past any record that cannot be deserialized (poison pill). */
    private ConsumerRecords<String, Event> poll(KafkaConsumer<String, Event> consumer) {
        while (true) {
            try {
                return consumer.poll(POLL_TIMEOUT);
            } catch (RecordDeserializationException e) {
                log.warn("skipping poison pill at {} offset {}", e.topicPartition(), e.offset());
                consumer.seek(e.topicPartition(), e.offset() + 1);
            }
        }
    }

    private Properties producerConfig() {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    /** Fresh group + earliest offset: every client start is a full replay. */
    private Properties consumerConfig() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "goose-client-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }
}
