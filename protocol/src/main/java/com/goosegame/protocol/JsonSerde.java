package com.goosegame.protocol;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * JSON Serializer/Deserializer for the sealed {@link Command} and {@link Event}
 * hierarchies, e.g. {@code new JsonSerde<>(Event.class)}. Also implements
 * {@link Serde}, so one instance serves plain producers/consumers and Kafka
 * Streams alike.
 *
 * <p>The {@code "type"} discriminator comes from the {@code @JsonTypeInfo} /
 * {@code @JsonSubTypes} annotations on the sealed interfaces — a closed set.
 * Jackson polymorphic default typing is never activated, so the wire format can
 * only ever name those explicitly listed record types.
 *
 * <p>{@link Instant} is written as an ISO-8601 string via a tiny inline module,
 * avoiding the extra jackson-datatype-jsr310 dependency.
 */
public final class JsonSerde<T> implements Serde<T>, Serializer<T>, Deserializer<T> {

    /** Game messages are tiny; anything bigger than this is garbage or abuse. */
    public static final int MAX_PAYLOAD_BYTES = 10 * 1024;

    private static final ObjectMapper MAPPER = createMapper();

    private final Class<T> type;

    public JsonSerde(Class<T> type) {
        Validation.notNull(type, "type");
        this.type = type;
    }

    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null) {
            return null; // null in, null out: Kafka tombstone contract
        }
        try {
            return MAPPER.writeValueAsBytes(data);
        } catch (IOException e) {
            throw new SerializationException("cannot serialize " + type.getSimpleName(), e);
        }
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null) {
            return null; // null in, null out: Kafka tombstone contract
        }
        if (data.length > MAX_PAYLOAD_BYTES) {
            throw new DeserializationException(
                    "payload of %d bytes exceeds the %d-byte limit".formatted(data.length, MAX_PAYLOAD_BYTES));
        }
        try {
            return MAPPER.readValue(data, type);
        } catch (IOException | RuntimeException e) {
            // e.toString(), not e.getMessage(): the message can be null and the
            // exception class name is what identifies a poison pill in the logs.
            throw new DeserializationException(
                    "cannot deserialize %s: %s".formatted(type.getSimpleName(), e), e);
        }
    }

    @Override
    public Serializer<T> serializer() {
        return this;
    }

    @Override
    public Deserializer<T> deserializer() {
        return this;
    }

    // Serde, Serializer and Deserializer all declare default configure()/close(),
    // so Java requires this class to tie-break with its own overrides.

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // no configuration needed
    }

    @Override
    public void close() {
        // stateless — nothing to release
    }

    private static ObjectMapper createMapper() {
        var instants = new SimpleModule("iso-instants")
                .addSerializer(Instant.class, new JsonSerializer<Instant>() {
                    @Override
                    public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                            throws IOException {
                        gen.writeString(value.toString());
                    }
                })
                .addDeserializer(Instant.class, new JsonDeserializer<Instant>() {
                    @Override
                    public Instant deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                        String text = p.getValueAsString();
                        if (text == null) { // non-string token, e.g. an object or a boolean
                            throw ctx.wrongTokenException(p, Instant.class, JsonToken.VALUE_STRING,
                                    "expected an ISO-8601 timestamp string");
                        }
                        return Instant.parse(text);
                    }
                });
        return new ObjectMapper()
                .registerModule(instants)
                // Tolerate unknown fields so newer producers can add fields without
                // breaking older consumers reading the same long-retention log.
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
