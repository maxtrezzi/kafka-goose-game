# Kafka-Goose-Game — Implementation Plan

Multiplayer Game of the Goose (Gioco dell'Oca) to learn Kafka and refresh modern Java.

**How to use this file (for any session, human or Claude):**
Steps are designed to be executed one per session if desired. Each step is self-contained,
ends in a verifiable state, and assumes only that the previous steps are done.
Find the first unchecked step below, do it, verify it, then **STOP: show the user every
command run and all code written, and wait for approval**. Only after approval: check the
step off and commit. Never start the next step without an explicit user go-ahead.

## Status

- [x] Step 1 — Project scaffolding
- [x] Step 2 — Kafka cluster (docker compose)
- [x] Step 3 — `protocol` module (messages + Ser/Des)
- [x] Step 4 — `engine` module (game rules)
- [x] Step 5 — `server` module + E2E test
- [x] Step 6 — `client-core` module
- [x] Step 7 — `client-tui` module (playable!)
- [ ] Step 8 — README + final verification

## Fixed decisions (do not revisit)

| Topic | Decision |
|---|---|
| Stack | Plain Java 21 + `kafka-clients`, no framework |
| Ser/Des | JSON via Jackson; hand-written `JsonSerde<T>` implementing Kafka's `Serializer`/`Deserializer`; explicit `"type"` field mapped to a closed sealed hierarchy (no polymorphic default typing) |
| Kafka | Docker Compose, 3 KRaft brokers, topics RF=3, `min.insync.replicas=2` |
| Build | Maven multi-module, Java 21 (Temurin 21.0.9 installed) |
| UI | TUI first; `client-core` is UI-agnostic so web/desktop UIs can be added later |
| Tests | JUnit 5 unit tests everywhere; Testcontainers for the automated E2E |
| Architecture | Event-sourced, server-authoritative. Topics `game.commands` (intents) and `game.events` (facts), keyed by `gameId`. Clients rebuild state by replaying events. |

Library versions checked on Maven Central 2026-07-02: kafka-clients 4.3.0,
jackson-databind 2.19.0, slf4j 2.0.x (stable, NOT 2.1.0-alpha), junit-jupiter 5.12.x
(stable, NOT 5.13 milestone), testcontainers 1.21.3.

**Java 21 idioms to use throughout:** records for all messages/state, sealed interfaces +
exhaustive pattern-matching `switch`, virtual threads for consumer loops, text blocks,
immutable collections, `Instant` timestamps, try-with-resources on every producer/consumer.

**Security throughout:** validation in record compact constructors; server validates every
command against game phase and turn ownership; dice only rolled server-side with
`SecureRandom`; deserializer rejects unknown types and caps payload size.

---

## Step 1 — Project scaffolding

Create the Maven multi-module skeleton and put it under git.

- `git init`; `.gitignore` (target/, .idea/, *.iml, .vscode/)
- Parent `pom.xml`: packaging `pom`, Java 21 via `maven.compiler.release`, `<dependencyManagement>` pinning all versions listed above, modules: `protocol`, `engine`, `server`, `client-core`, `client-tui`
- One minimal `pom.xml` per module with only its dependencies:
  - `protocol`: kafka-clients, jackson-databind
  - `engine`: → protocol
  - `server`: → engine; testcontainers (test)
  - `client-core`: → protocol
  - `client-tui`: → client-core
  - all: junit-jupiter (test), slf4j-simple
- Empty `src/main/java` / `src/test/java` trees, base package `com.goosegame`

**Verify:** `mvn validate` succeeds for all modules. Commit.

## Step 2 — Kafka cluster

`docker-compose.yml` at repo root:

- 3 services `kafka-1/2/3`, image `apache/kafka` (pin current tag), KRaft combined
  controller+broker mode, one shared `CLUSTER_ID`, controller quorum of all three,
  host ports 9092 / 9094 / 9096
- `init-topics` one-shot service (same image) that waits for the cluster and creates
  `game.commands` and `game.events`: 3 partitions, replication factor 3,
  `min.insync.replicas=2`, then exits 0

**Verify:** `docker compose up -d` → 3 healthy brokers; `kafka-topics.sh --describe` inside
a container shows both topics with 3 replicas each and full ISR. Commit.

## Step 3 — `protocol` module (messages + Ser/Des)

The shared language of the game.

- `Command` sealed interface + records: `JoinGame(gameId, player)`, `StartGame(gameId, player)`, `RollDice(gameId, player)`
- `Event` sealed interface + records: `PlayerJoined`, `GameStarted(players, firstPlayer)`, `TurnStarted(player)`, `DiceRolled(player, die1, die2)`, `PlayerMoved(player, from, to, reason)` (reason: NORMAL/GOOSE/BRIDGE/BOUNCE/MAZE/DEATH...), `PlayerStuck(player, squares)` (inn/well/prison), `PlayerFreed(player)`, `GameWon(player)` — all with `gameId` and `Instant timestamp`
- Validation in compact constructors (non-null, name 1–20 chars `[A-Za-z0-9_-]`)
- `JsonSerde<T>`: implements `Serializer<T>` and `Deserializer<T>` using one shared
  `ObjectMapper`; writes/reads the `"type"` property via `@JsonTypeInfo(use=NAME)` +
  `@JsonSubTypes` on the sealed interfaces (closed set — never activate default typing);
  registers `JavaTimeModule`-free Instant handling or ISO strings; rejects payloads > 10 KB
- Deserialization failure → a `DeserializationException` the server can log-and-skip

**Verify:** unit tests — round-trip every command/event; unknown `"type"` rejected;
malformed JSON rejected; oversized payload rejected. `mvn -pl protocol test` green. Commit.

## Step 4 — `engine` module (game rules)

Pure logic, zero Kafka imports. The heart of the Java refresh.

- `Board`: 63 squares; geese 5,9,14,18,23,27,32,36,41,45,50,54,59; bridge 6→12; inn 19
  (miss one turn); well 31 and prison 52 (stuck until another player lands there); maze
  42→39; death 58→back to 1; land exactly on 63 to win, overshoot bounces back
- `GameState` record (immutable): gameId, phase (LOBBY/RUNNING/FINISHED), players in join
  order, positions, stuck/skip info, current player index. `apply(Event)` returns new state
- `GameEngine.decide(GameState, Command, DiceRoller) -> List<Event>` — pure function;
  `DiceRoller` is an interface so tests inject fixed rolls and the server injects SecureRandom
- Rejections (out-of-turn roll, join after start, duplicate name, 2–6 players…) produce no
  events (or a `CommandRejected` event — decide during implementation, keep it simple)

**Verify:** unit tests for every rule listed above + full simulated game with scripted dice.
`mvn -pl engine test` green. Commit.

## Step 5 — `server` module + E2E test

The authoritative game host.

- `GooseServer.main`: on startup, replay `game.events` from beginning to rebuild a
  `Map<String, GameState>`; then consumer loop (group `goose-server`) on `game.commands`:
  for each command → `GameEngine.decide` → produce resulting events to `game.events`
  (keyed by gameId) → apply events to local state
- Producer configured with `acks=all`, `enable.idempotence=true`
- Dice: `RandomGenerator` backed by `SecureRandom`
- Graceful shutdown hook closing consumer/producer
- **E2E test (Testcontainers):** start throwaway Kafka container, create topics, run server
  loop on a virtual thread, produce scripted commands for a 2-player game, consume
  `game.events` and assert the expected sequence and winner

**Verify:** `mvn -pl server verify` green (includes E2E). Manual: `docker compose up -d`,
run server against localhost:9092, send a JoinGame with `kafka-console-producer`, see the
PlayerJoined event with `kafka-console-consumer`. Commit.

## Step 6 — `client-core` module

UI-agnostic client library — the seam that lets us add web/desktop UIs later.

- `GameClient` (Closeable): producer to send `Command`s; event consumer on a **virtual
  thread** with a unique consumer group, reading `game.events` from earliest (replay)
- `GameView`: client-side fold of events into displayable state (board positions, whose
  turn, log of last events, winner)
- `GameListener` interface: `onViewUpdated(GameView)`, `onEvent(Event)` — any UI implements this
- No console I/O in this module

**Verify:** unit tests — folding a scripted event sequence produces the expected view
(replay logic). `mvn -pl client-core test` green. Commit.

## Step 7 — `client-tui` module (the game becomes playable)

- `Main`: args = bootstrap servers, gameId, player name (with sensible defaults)
- Renders the 63-square board as an ANSI-colored serpentine grid (text block template),
  player tokens as colored letters, special squares marked; reprints on each view update
- Stdin loop: `join <name>`, `start`, `roll`, `help`, `quit`
- Runnable via `mvn -pl client-tui exec:java` (add exec plugin) or a small `run-client.sh`

**Verify:** with compose cluster + server running, two terminals play a full game to
victory; killing and restarting a client mid-game reconstructs the board from replay. Commit.

## Step 8 — README + final verification

- README: what it is, architecture diagram, quickstart (compose up → server → 2 clients),
  command reference, and **Kafka experiments**: kill a broker mid-game (RF=3 survives),
  inspect consumer groups/offsets, replay a finished game, console-consume `game.events`
  live; optional advanced exercise: enable SASL/SCRAM + ACLs
- Full pass: `mvn verify` green from clean; fresh `docker compose up -d`; complete manual
  game; broker-kill resilience check

**Verify:** everything above. Commit. Project done — future ideas: web UI (Javalin + SSE)
on top of client-core, Avro + Schema Registry migration, multiple concurrent games.
