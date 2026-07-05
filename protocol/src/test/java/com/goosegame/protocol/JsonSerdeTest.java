package com.goosegame.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSerdeTest {

    private static final String TOPIC = "game.events";
    private static final Instant NOW = Instant.parse("2026-07-02T10:15:30.123456789Z");

    private final JsonSerde<Command> commandSerde = new JsonSerde<>(Command.class);
    private final JsonSerde<Event> eventSerde = new JsonSerde<>(Event.class);

    static Stream<Command> allCommands() {
        return Stream.of(
                new Command.JoinGame("game-1", "alice"),
                new Command.StartGame("game-1", "alice"),
                new Command.RollDice("game-1", "bob"));
    }

    static Stream<Event> allEvents() {
        return Stream.of(
                new Event.PlayerJoined("game-1", NOW, "alice"),
                new Event.GameStarted("game-1", NOW, List.of("alice", "bob"), "alice"),
                new Event.TurnStarted("game-1", NOW, "alice"),
                new Event.DiceRolled("game-1", NOW, "alice", 4, 2),
                new Event.PlayerMoved("game-1", NOW, "alice", 0, 6, MoveReason.NORMAL),
                new Event.PlayerMoved("game-1", NOW, "alice", 6, 12, MoveReason.BRIDGE),
                new Event.PlayerStuck("game-1", NOW, "alice", 31),
                new Event.PlayerFreed("game-1", NOW, "alice"),
                new Event.GameWon("game-1", NOW, "alice"));
    }

    @ParameterizedTest
    @MethodSource("allCommands")
    void everyCommandRoundTrips(Command command) {
        byte[] bytes = commandSerde.serialize(TOPIC, command);
        assertEquals(command, commandSerde.deserialize(TOPIC, bytes));
    }

    @ParameterizedTest
    @MethodSource("allEvents")
    void everyEventRoundTrips(Event event) {
        byte[] bytes = eventSerde.serialize(TOPIC, event);
        assertEquals(event, eventSerde.deserialize(TOPIC, bytes));
    }

    @Test
    void wireFormatCarriesTypeAndIsoTimestamp() {
        var json = new String(
                eventSerde.serialize(TOPIC, new Event.PlayerJoined("game-1", NOW, "alice")),
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"type\":\"PlayerJoined\""), json);
        assertTrue(json.contains("\"timestamp\":\"2026-07-02T10:15:30.123456789Z\""), json);
    }

    @Test
    void handCraftedJsonDeserializes() {
        var json = """
                {"type":"RollDice","gameId":"game-1","player":"alice"}""";
        assertEquals(new Command.RollDice("game-1", "alice"),
                commandSerde.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() {
        var json = """
                {"type":"RollDice","gameId":"game-1","player":"alice","newField":42}""";
        assertEquals(new Command.RollDice("game-1", "alice"),
                commandSerde.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void actsAsItsOwnSerde() {
        var event = new Event.GameWon("game-1", NOW, "alice");
        byte[] bytes = eventSerde.serializer().serialize(TOPIC, event);
        assertEquals(event, eventSerde.deserializer().deserialize(TOPIC, bytes));
    }

    @Test
    void nonStringTimestampIsRejected() {
        var json = """
                {"type":"PlayerFreed","gameId":"game-1","timestamp":{},"player":"alice"}""";
        assertThrows(DeserializationException.class,
                () -> eventSerde.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unknownTypeIsRejected() {
        var json = """
                {"type":"DropTables","gameId":"game-1","player":"mallory"}""";
        assertThrows(DeserializationException.class,
                () -> commandSerde.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingTypeIsRejected() {
        var json = """
                {"gameId":"game-1","player":"alice"}""";
        assertThrows(DeserializationException.class,
                () -> commandSerde.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(DeserializationException.class,
                () -> eventSerde.deserialize(TOPIC, "{not json!".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void payloadFailingRecordValidationIsRejected() {
        var json = """
                {"type":"DiceRolled","gameId":"game-1","timestamp":"2026-07-02T10:15:30Z",\
                "player":"alice","die1":99,"die2":1}""";
        assertThrows(DeserializationException.class,
                () -> eventSerde.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unparsableTimestampIsRejected() {
        var json = """
                {"type":"PlayerFreed","gameId":"game-1","timestamp":"yesterday","player":"alice"}""";
        assertThrows(DeserializationException.class,
                () -> eventSerde.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void oversizedPayloadIsRejectedBeforeParsing() {
        byte[] huge = new byte[JsonSerde.MAX_PAYLOAD_BYTES + 1];
        Arrays.fill(huge, (byte) ' ');
        assertThrows(DeserializationException.class, () -> eventSerde.deserialize(TOPIC, huge));
    }

    @Test
    void payloadAtTheLimitIsParsed() {
        byte[] json = """
                {"type":"JoinGame","gameId":"game-1","player":"alice"}""".getBytes(StandardCharsets.UTF_8);
        byte[] padded = Arrays.copyOf(json, JsonSerde.MAX_PAYLOAD_BYTES);
        Arrays.fill(padded, json.length, padded.length, (byte) ' ');
        assertEquals(new Command.JoinGame("game-1", "alice"), commandSerde.deserialize(TOPIC, padded));
    }

    @Test
    void nullPassesThroughBothWays() {
        assertNull(commandSerde.serialize(TOPIC, null));
        assertNull(commandSerde.deserialize(TOPIC, null));
    }
}
