# 02 — Protocol: the Wire Contract

[← Architecture](01-architecture.md) · [Engine →](03-engine.md)

The `protocol` module is the shared language of the system: the only code
that both the server side (`engine`, `server`) and the client side
(`client-core`, `client-tui`) depend on. It contains the message hierarchies,
their validation, the JSON Serde, and the topic names — and nothing else. It
has no game rules and no I/O beyond serialization.

## The message hierarchies

Two sealed interfaces, one per topic:

**`Command`** — a player's intent (`Command.java`):

| Record | Fields | Meaning |
|---|---|---|
| `JoinGame` | gameId, player | Ask to enter the lobby |
| `StartGame` | gameId, player | Ask to close the lobby and begin |
| `RollDice` | gameId, player | Ask to roll on one's own turn |

**`Event`** — a fact decided by the server (`Event.java`):

| Record | Key fields | Meaning |
|---|---|---|
| `PlayerJoined` | player | Lobby entry accepted |
| `GameStarted` | players (turn order), firstPlayer | Lobby closed, play began |
| `TurnStarted` | player | Whose turn it now is |
| `DiceRolled` | player, die1, die2 | The server's roll (informational — the moves carry the state change) |
| `PlayerMoved` | player, from, to, `MoveReason` | One movement segment; a single roll can emit several (goose chains, bounce) |
| `PlayerStuck` | player, square | Trapped on inn 19 / well 31 / prison 52 |
| `PlayerFreed` | player | No longer trapped |
| `GameWon` | player | Landed exactly on 63; terminal |

Every event also carries `gameId` and an `Instant timestamp`. `MoveReason`
(`NORMAL`, `GOOSE`, `BRIDGE`, `BOUNCE`, `MAZE`, `DEATH`) makes each movement
segment self-describing, so clients can render *why* a token moved without
knowing any board rules.

### Why records + sealed interfaces

- **Records** give value semantics (`equals`/`hashCode`/`toString`) correct by
  construction and immutability by default — exactly what a wire message is.
  The E2E test compares whole events with `assertEquals`; that works only
  because records define equality by content.
- **Sealed** hierarchies make the message set *closed and compiler-checked*.
  Every `switch` over `Command` or `Event` in the codebase is exhaustive with
  **no `default` branch** — adding a ninth event type refuses to compile until
  the engine fold, the client fold, and the TUI renderer all handle it. The
  compiler becomes the checklist for protocol evolution.

### Granularity choice: many small facts over one big one

A single roll could have been one fat `TurnResolved` event carrying the dice,
all movement, traps and the next player. Instead it is a *sequence* of small
events (`DiceRolled`, `PlayerMoved`×n, `PlayerStuck`?, `PlayerFreed`?,
`TurnStarted`/`GameWon`). Motivations:

- each event has one meaning and one consumer-side fold case — folds stay
  trivial;
- the log is readable move-by-move in a console consumer (a learning feature);
- animation-grade granularity is available to future UIs for free.

The cost — a turn is not atomic in the log — is harmless: partial sequences
can only occur if the server dies mid-produce, and at-least-once redelivery
regenerates the remainder of the turn (see [chapter 4](04-server.md)).

## Validation at the boundary — and only there

Every field is validated in the record's **compact constructor**, with the
shared checks centralized in package-private `Validation`:

- `gameId`: 1–64 chars of `[A-Za-z0-9_-]`
- `player`: 1–20 chars of `[A-Za-z0-9_-]`
- `players` list: non-empty, each name valid, **no duplicates**, defensively
  copied with `List.copyOf`
- dice 1–6, squares 0–63 (0 = off-board start), timestamps non-null
- `GameStarted` additionally checks `firstPlayer ∈ players` — an invariant
  *between* fields belongs to the record that holds both

The consequence is a strong system-wide guarantee: **an invalid message
cannot exist as a Java object**. There is no code path — local construction,
deserialization from the wire, test fixture — that can produce a `Command` or
`Event` with a null field, an oversized name, or a die of 7, because Jackson
itself goes through the canonical constructor of each record. Everything
downstream (engine, server, clients) can consume messages without a single
defensive null check, and does.

The tight `[A-Za-z0-9_-]` charset is a security decision, not a style one: 
names end up in log lines, ANSI terminal output, and shell-adjacent contexts
(console consumers). Restricting the alphabet at the boundary kills injection
concerns (log forging, ANSI escape smuggling, path/shell metacharacters) in
one place instead of escaping in many.

## The Serde: one class, three interfaces

`JsonSerde<T>` implements Kafka's `Serializer<T>`, `Deserializer<T>` **and**
`Serde<T>` in a single stateless class (`serializer()`/`deserializer()` return
`this`). One instance therefore serves plain producers/consumers today and
Kafka Streams / `TopologyTestDriver` tomorrow. It is constructed per
hierarchy: `new JsonSerde<>(Event.class)` or `new JsonSerde<>(Command.class)`.

Design points, each with its motivation:

### Closed polymorphism — the security core

The JSON `"type"` discriminator comes from `@JsonTypeInfo(use = NAME)` +
explicit `@JsonSubTypes` on the sealed interfaces. Jackson's **polymorphic
default typing is never activated**. This is the difference between "the wire
can name any class on the classpath" (the classic Jackson gadget-chain RCE
vector) and "the wire can name exactly these 11 record types and nothing
else". The closed set also mirrors the sealed hierarchy: the compiler enforces
it in Java, the annotation enforces it on the wire.

### Payload cap before parsing

`MAX_PAYLOAD_BYTES = 10 KiB`, checked on the raw byte array *before* Jackson
touches it. Real messages are a few hundred bytes; anything bigger is garbage
or abuse, and rejecting it early bounds the memory a hostile producer can make
the server parse.

### Unknown fields are tolerated — explicitly

`FAIL_ON_UNKNOWN_PROPERTIES = false`, with a why-comment and a pinning test.
This was a review finding (ISSUES.md #5): Jackson's default (`true`) was in
force *by accident* — chosen by nobody, tested by nothing. In an event-sourced
system the log lives indefinitely, so a newer producer must be able to add
fields without poisoning every not-yet-upgraded consumer. The leniency is safe
precisely because of the compact-constructor validation: an unknown *extra*
field is ignored, but a missing *required* field still fails construction.
Trade-off accepted: a misspelled field name is silently ignored.

### Tombstone contract: null in, null out

Both `serialize(null)` and `deserialize(null)` return `null` — a deliberate,
commented deviation from the project's "never return null" default, because
Kafka's log-compaction tombstones *are* null payloads and a Serde must pass
them through. Downstream code handles the null explicitly (the server treats
a null command/event as "nothing to do").

### Inline `Instant` handling

Timestamps serialize as ISO-8601 strings via a tiny inline `SimpleModule`
(two anonymous classes) instead of pulling in the `jackson-datatype-jsr310`
module — two small classes versus a whole dependency for one type. The
deserializer guards the non-string-token case (`p.getValueAsString()` returns
null for e.g. `"timestamp": {}`) and raises a proper
`ctx.wrongTokenException(...)` instead of a bare NPE — a review finding.

### Poison-pill contract

Any failure to deserialize — malformed JSON, unknown `"type"`, validation
failure inside a compact constructor, oversized payload — is wrapped in the
protocol's own `DeserializationException` with the cause attached. The message
uses `e.toString()` rather than `e.getMessage()` because the latter can be
null, and the exception class name is exactly what identifies a poison pill in
the logs (another review finding). Consumers catch Kafka's
`RecordDeserializationException` wrapper and **seek past** the bad record —
log-and-skip, never crash ([chapter 4](04-server.md#poison-pills)).

## Topic names live here too

`Topics.COMMANDS` / `Topics.EVENTS` are constants in the protocol module
because topic names *are* wire contract — server and clients must agree on
them exactly like they agree on field names. Before this, each side had its
own string literal (found in review). Known caveat, recorded in DECISIONS.md:
`docker-compose.yml`'s `init-topics` service still spells the names in YAML,
so renaming a topic touches `Topics.java` *and* the compose file.

## Patterns applied

- **Algebraic data types** (sealed interface + records) as the message model.
- **Parse, don't validate** — deserialization *is* validation; downstream code
  receives only proven-valid values.
- **Fail fast at the boundary** with named-parameter error messages.
- **Explicit policy over silent default** — the unknown-fields behavior is
  set in code, explained in a comment, and pinned by a test, so the policy is
  a visible choice instead of an inherited accident.

## Anti-patterns avoided

- **Jackson default typing** — the gadget-chain deserialization vector is
  designed out, not mitigated.
- **Stringly-typed messages** — no maps of strings, no "type" switches on raw
  JSON anywhere outside the Serde.
- **Defensive re-validation sprinkled downstream** — validation exists exactly
  once, at construction.
- **Null-returning APIs** — the two tombstone nulls are the single, commented,
  contract-required exception.
- **Swallowed causes** — every wrap passes the original exception along.

## Decisions (from DECISIONS.md)

Unknown-field tolerance; tombstone nulls; tri-interface Serde; inline Instant;
closed polymorphism; 10 KiB cap; poison-pill exception type; restricted name
charset; topic constants in protocol.

## Issues (from ISSUES.md)

**#5** — Jackson's unknown-field default silently in force; made explicit,
commented, and pinned by a test.
