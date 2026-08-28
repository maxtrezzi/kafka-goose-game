# 02 — Protocol: the Wire Contract

[← Architecture](01-architecture.md) · [Engine →](03-engine.md)

The `protocol` module is the shared language of the system, and the
[wire contract](11-glossary.md#wire-contract) between the two sides: it is the
only code that both the server side (`engine`, `server`) and the client side
(`client-core`, `client-tui`) depend on. It contains the message types, their
validation, the JSON [Serde](11-glossary.md#serde) and the topic names, and
nothing else. It holds no game rules, and does no input or output beyond
turning messages into bytes and back.

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
| `GameWon` | player | Landed exactly on 63; the game ends here |

Every event also carries `gameId` and an `Instant timestamp`. `MoveReason`
(`NORMAL`, `GOOSE`, `BRIDGE`, `BOUNCE`, `MAZE`, `DEATH`) makes each movement
segment self-describing, so clients can render *why* a token moved without
knowing any board rules.

### Why records and sealed interfaces

- **[Records](11-glossary.md#record)** compare by content, not by identity, and
  cannot be changed after they are built. That is exactly what a message on the
  network is. The end-to-end test compares whole events with `assertEquals`,
  which only works because two records with the same values are equal.
- **[Sealed](11-glossary.md#sealed-interface)** interfaces fix the list of
  message types, and the compiler knows that list. Every `switch` over
  `Command` or `Event` in this codebase covers all cases and has **no
  `default` branch** (see [exhaustive pattern
  matching](11-glossary.md#exhaustive-pattern-matching)). Adding a ninth event
  type breaks the build until the engine fold, the client fold and the terminal
  renderer all handle it. The compiler, not a checklist, tells you what still
  has to change.

### How much each event should say: many small facts, not one big one

One roll could have produced a single large `TurnResolved` event, carrying the
dice, all the movement, any traps and the next player. Instead it produces a
*sequence* of small events: `DiceRolled`, one `PlayerMoved` per movement step,
then `PlayerStuck` or `PlayerFreed` if they apply, then `TurnStarted` or
`GameWon`. The reasons:

- each event means one thing and needs one case in the fold, so the folds stay
  simple;
- the log can be read move by move in a console consumer, which is useful while
  learning;
- a future UI can animate each step, because each step is already a separate
  event.

The price is that a turn is not written as one indivisible unit. That turns out
not to matter: an incomplete sequence can only happen if the server dies while
writing it, and when the command is delivered again the rest of the turn is
produced (see [chapter 4](04-server.md)).

## Validation at the boundary — and only there

Every field is checked in the record's **[compact
constructor](11-glossary.md#compact-constructor)**, with the checks that repeat
collected in the package-private `Validation` class:

- `gameId`: 1–64 chars of `[A-Za-z0-9_-]`
- `player`: 1–20 chars of `[A-Za-z0-9_-]`
- `players` list: non-empty, each name valid, **no duplicates**, defensively
  copied with `List.copyOf`
- dice 1–6, squares 0–63 (0 = off-board start), timestamps non-null
- `GameStarted` also checks that `firstPlayer` is one of `players`. A rule
  that links two fields belongs in the record that holds them both

This gives one strong guarantee for the whole system: **an invalid message
cannot exist as a Java object**. There is no way to build one — not in normal
code, not when reading from Kafka, not in a test — because Jackson also goes
through each record's main constructor. A `Command` or `Event` with a null
field, a name that is too long, or a die showing 7, simply cannot be created.
Everything further along — engine, server, clients — can therefore use messages
without a single defensive null check, and does.

Allowing only the characters `[A-Za-z0-9_-]` in names is a security decision,
not a matter of taste. Names appear in log files, in terminal output, and in
the output of command-line tools. Limiting the characters at the boundary
removes a whole family of injection attacks at once: fake log lines, hidden
ANSI escape sequences that rewrite what the terminal shows, and characters with
a special meaning to a shell or a file path. The alternative is escaping the
same values correctly in every place they are printed.

## The Serde: one class, three interfaces

`JsonSerde<T>` implements Kafka's `Serializer<T>`, `Deserializer<T>` **and**
`Serde<T>` in one class that holds no state (`serializer()` and
`deserializer()` both return `this`). A single instance therefore works with
ordinary producers and consumers today, and would work with Kafka Streams
later without changes. One instance is built per message family:
`new JsonSerde<>(Event.class)` or `new JsonSerde<>(Command.class)`.

Design points, each with its motivation:

### A closed set of types — the heart of the security design

The `"type"` field in the JSON comes from `@JsonTypeInfo(use = NAME)` together
with an explicit `@JsonSubTypes` list on each sealed interface. Jackson's
**default typing is never switched on**. The difference matters: with default
typing, an incoming message can name *any* class available to the program,
which is the well-known Jackson attack that ends in remote code execution. With
an explicit list, a message can name exactly these 11 record types and nothing
else. The list also matches the sealed interface: the compiler enforces it
inside Java, the annotation enforces it on the network.

### Payload cap before parsing

`MAX_PAYLOAD_BYTES = 10 KiB`, checked on the raw bytes *before* Jackson sees
them. Real messages are a few hundred bytes, so anything larger is either
broken or hostile. Refusing it early puts a fixed limit on how much memory an
attacker can make the server use for parsing.

### Unknown fields are tolerated — explicitly

`FAIL_ON_UNKNOWN_PROPERTIES = false`, with a comment saying why and a test
that fails if anyone changes it. This came out of a review (ISSUES.md #5):
Jackson's default of `true` was in force *by accident*, chosen by nobody and
covered by no test. In an event-sourced system the log is kept forever, so a
newer producer has to be able to add a field without breaking every consumer
that has not been updated yet. Accepting unknown fields is safe here only
because of the checks in the compact constructors: an extra field that nobody
knows about is ignored, but a required field that is missing still fails.
The accepted downside is that a misspelled field name is ignored in silence.

### Tombstones: null in, null out

Both `serialize(null)` and `deserialize(null)` return `null`. This breaks the
project's own "never return null" rule on purpose, and the code says so in a
comment: a Kafka [tombstone](11-glossary.md#tombstone) *is* a message with a
null value, and a Serde has to let it through unchanged. The code that receives
it handles the null openly — the server treats a null command or event as
"nothing to do".

### Inline `Instant` handling

Timestamps are written as ISO-8601 strings by a very small `SimpleModule`
defined inline (two anonymous classes), instead of adding the
`jackson-datatype-jsr310` dependency: two short classes against a whole library
for one type. The reader also handles the case where the value is not a string
at all. For input such as `"timestamp": {}`, `p.getValueAsString()` returns
null, so the code raises a proper `ctx.wrongTokenException(...)` rather than
letting a `NullPointerException` escape — another finding from review.

### What happens to a message that cannot be read

Every failure to deserialize — broken JSON, an unknown `"type"`, a check that
fails inside a compact constructor, a payload that is too large — is wrapped in
the protocol's own `DeserializationException`, keeping the original exception
as the cause. The message text uses `e.toString()` and not `e.getMessage()`,
because `getMessage()` can be null and because the class name of the exception
is what identifies a [poison pill](11-glossary.md#poison-pill) in the logs
(again, a review finding). Consumers catch Kafka's
`RecordDeserializationException` and **move past** the bad record: log it, skip
it, never stop ([chapter 4](04-server.md#messages-that-cannot-be-read)).

## Topic names live here too

`Topics.COMMANDS` and `Topics.EVENTS` are constants in the protocol module,
because topic names are part of the wire contract: the server and the clients
must agree on them just as they agree on field names. Before this, each side
had its own copy of the string, which a review found. One known weak point is
recorded in DECISIONS.md: the `init-topics` service in `docker-compose.yml`
still writes the names out in YAML, so renaming a topic means changing
`Topics.java` *and* the compose file.

## Patterns applied

- **Algebraic data types** — a sealed interface plus records as the message
  model: a closed list of shapes, each holding its own fields.
- **[Parse, don't validate](11-glossary.md#parse-dont-validate)** — reading a
  message *is* validating it, so the rest of the program only ever sees values
  that are already known to be correct.
- **Fail immediately at the boundary**, with error messages that name the field
  at fault.
- **State the policy instead of inheriting a default** — the handling of
  unknown fields is set in code, explained in a comment and held in place by a
  test, so it is a visible choice rather than an accident.

## Anti-patterns avoided

- **Jackson default typing** — the attack it enables is removed by design,
  not merely made harder.
- **Messages built out of plain strings** — no maps of strings, and no
  switching on a `"type"` field of raw JSON anywhere outside the Serde.
- **Checking the same values again further along** — validation happens exactly
  once, when the object is built.
- **Methods that return null** — the two tombstone nulls are the only
  exception, they are commented, and the Kafka contract requires them.
- **Losing the cause of an exception** — every wrapper passes the original
  exception on.

## Decisions (from DECISIONS.md)

- Unknown fields in incoming JSON are ignored, on purpose and with a test.
- The Serde passes null values through, because Kafka tombstones need it.
- One class implements all three Kafka Serde interfaces.
- `Instant` support is written inline instead of adding a dependency.
- The set of message types on the wire is closed and listed explicitly.
- Payloads larger than 10 KiB are rejected before parsing.
- Deserialization failures get their own exception type.
- Names may contain only `[A-Za-z0-9_-]`.
- Topic names are constants in the `protocol` module.

## Issues (from ISSUES.md)

**#5** — Jackson's default handling of unknown fields was in force without
anyone choosing it. It is now set explicitly, explained in a comment, and held
in place by a test.
