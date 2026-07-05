package com.goosegame.protocol;

/**
 * A record on the wire could not be turned into a valid {@link Command} or
 * {@link Event}: malformed JSON, unknown {@code "type"}, oversized payload, or
 * a value rejected by a record's compact constructor.
 *
 * <p>The server treats this as a poison pill: log it and skip the record,
 * never crash the consumer loop.
 */
public class DeserializationException extends RuntimeException {

    public DeserializationException(String message) {
        super(message);
    }

    public DeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
