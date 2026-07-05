# kafka-goose-game

A multiplayer **Game of the Goose** (Gioco dell'Oca) built on plain **Java 21**
and **Apache Kafka** — no frameworks. The point of the project is to learn
Kafka's core mechanics (topics, partitions, replication, consumer groups,
replay) and to exercise modern Java (records, sealed interfaces, pattern
matching, virtual threads) on something you can actually play.

Everything is **event-sourced and server-authoritative**: clients only send
*intents*, a single server turns them into *facts*, and every piece of state —
server and client alike — is a fold over the fact log. Kill any process and
restart it: it rebuilds itself by replaying the topic.

## Architecture

```
 ┌────────────┐  Command (JoinGame,           ┌────────────────┐
 │ client-tui │  StartGame, RollDice)         │  GooseServer   │
 │  (alice)   │──────────────┐                │                │
 └────────────┘              ▼                │ 1. replay      │
 ┌────────────┐   ╔══════════════════╗        │    game.events │
 │ client-tui │──▶║  game.commands   ║───────▶│    → state     │
 │   (bob)    │   ║ 3 part. / RF=3   ║        │ 2. per command:│
 └────────────┘   ╚══════════════════╝        │    GameEngine  │
       ▲                                      │    .decide()   │
       │          ╔══════════════════╗        │    → events    │
       └──────────║   game.events    ║◀───────│    (SecureRandom
    Event (PlayerJoined, GameStarted,║        │     dice)      │
    DiceRolled, PlayerMoved, ...)    ║        └────────────────┘
                  ║ 3 part. / RF=3   ║
                  ╚══════════════════╝
         both topics keyed by gameId, min.insync.replicas=2
```

| Module        | Depends on   | What it is |
|---------------|--------------|------------|
| `protocol`    | —            | The wire contract: sealed `Command`/`Event` records, JSON `JsonSerde` (closed `@JsonSubTypes` polymorphism, 10 KiB cap), topic names |
| `engine`      | protocol     | Pure game rules, zero Kafka imports: `GameEngine.decide(state, command, dice) -> List<Event>`, `GameState.apply(event)` |
| `server`      | engine       | The authoritative host: replays `game.events`, then folds `game.commands` → `decide` → produce events (at-least-once, `acks=all`, idempotent producer) |
| `client-core` | protocol     | UI-agnostic client library: sends commands, tails events on a virtual thread, folds them into a `GameView` for any UI |
| `client-tui`  | client-core  | ANSI terminal UI: serpentine 63-square board, stdin command loop |

The board: 63 squares, geese (`*`) double your move, bridge 6→12, inn 19
(miss a turn), well 31 and prison 52 (stuck until someone relieves you — but
the **last free player never gets trapped**, so a game can't freeze), maze
42→39, death 58→1, land exactly on 63 to win (overshoot bounces back).

## Quickstart

Prerequisites: Java 21, Maven, Docker with the compose plugin.

```bash
# 1. Start the 3-broker KRaft cluster (creates the topics, then init-topics exits)
docker compose up -d

# 2. Build everything once (installs the sibling modules for exec:java)
mvn -q -DskipTests install

# 3. Start the server (terminal 1)
mvn -pl server exec:java -Dexec.mainClass=com.goosegame.server.GooseServer

# 4. Start two players (terminals 2 and 3)
mvn -pl client-tui exec:java -Dexec.args="localhost:9092 game-1 alice"
mvn -pl client-tui exec:java -Dexec.args="localhost:9092 game-1 bob"
```

Then, in the clients: both type `join`, one types `start`, and take turns
typing `roll` until someone lands on 63. `quit` and relaunch a client mid-game:
it repaints the exact board state by replaying `game.events` from the beginning.

### TUI commands

| Command       | Effect |
|---------------|--------|
| `join [name]` | join the game (defaults to your player name; also switches your identity) |
| `start`       | start the game (2–6 players, from the lobby) |
| `roll`        | roll the dice on your turn (out-of-turn rolls are ignored by the server) |
| `board`       | reprint the board |
| `help`        | command list |
| `quit`        | leave — the game goes on; rejoin to catch up by replay |

Client args: `[bootstrap [gameId [player]]]`, defaulting to
`localhost:9092 game-1 <os-user>`. Run several games at once by picking
different `gameId`s.

## Build & test

```bash
mvn test        # unit tests only — no Docker needed
mvn verify      # + the Testcontainers E2E (spins up a throwaway Kafka container)
```

Building a single module needs the reactor flag: `mvn -pl engine -am test`
(`-am` also builds the sibling modules it depends on).

Test map: `protocol` round-trips every message and rejects malformed/oversized/
unknown-type payloads; `engine` covers every board rule plus full scripted
games; `server` has a deterministic end-to-end test (scripted dice, real Kafka
in a container); `client-core` tests the view fold; `client-tui` tests the
renderer on ANSI-stripped output.

## Kafka experiments

The cluster is sized for exactly these. All commands run from the repo root.

### 1. Kill a broker mid-game (replication saves you)

Both topics are RF=3 with `min.insync.replicas=2` — any single broker can die
without losing a message or stopping the game.

```bash
docker stop goose-kafka-2      # mid-game, while people are rolling
# ... keep playing: joins, rolls, moves all still work ...
docker start goose-kafka-2     # it catches back up and rejoins the ISR
```

Watch the ISR shrink and recover:

```bash
docker exec goose-kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:29092 --describe --topic game.events
```

While the broker is down, partitions it led get a new leader and `Isr:` drops
to two entries; after restart it returns to three. Stopping a *second* broker
breaks the `min.insync.replicas=2` guarantee: the idempotent producer's
`acks=all` writes start failing — that's the durability contract doing its job.

### 2. Watch the event log live

The whole game is readable JSON on one topic:

```bash
docker exec goose-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:29092 --topic game.events \
  --from-beginning --property print.key=true
```

Play a few turns and watch `DiceRolled` / `PlayerMoved` / `PlayerStuck` facts
appear, keyed by gameId. This is also the fastest way to debug a rule dispute.

### 3. Inspect consumer groups and offsets

```bash
docker exec goose-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --list

docker exec goose-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --describe --group goose-server
```

You'll see one durable `goose-server` group (its `CURRENT-OFFSET` on
`game.commands` advances only after the resulting events are safely produced —
that's the at-least-once handoff) and one throwaway `goose-client-<uuid>` group
per running client, which never commits at all: clients always replay from
`earliest` and keep nothing.

### 4. Replay a finished game

State is disposable everywhere; the log is the truth.

- Restart the **server**: it logs how many events it replayed and can continue
  any unfinished game — including ones that finished, whose `GameWon` it will
  faithfully refold.
- Restart a **client** into an old gameId: the full board, winner banner
  included, reappears from replay alone.
- Or fold it by eye with the console consumer from experiment 2 — every
  `GameView` any client ever showed is derivable from that stream.

### 5. Optional: SASL/SCRAM + ACLs (left as an exercise)

The cluster is intentionally PLAINTEXT for learnability. A good hardening
exercise: add a `SASL_PLAINTEXT` listener with SCRAM-SHA-256, create
`goose-server` and `goose-client` users with `kafka-configs.sh`, then use
`kafka-acls.sh` to let clients *write* only `game.commands` and *read* only
`game.events`, while the server gets the inverse — encoding
"clients propose, the server decides" at the broker level.

## Project log

- [PLAN.md](PLAN.md) — the 8-step implementation plan this was built from
- [DECISIONS.md](DECISIONS.md) — every non-obvious design call, with reasoning
- [ISSUES.md](ISSUES.md) — every problem hit along the way and its actual fix

## Future ideas

Web UI (Javalin + SSE) on top of `client-core`; Avro + Schema Registry
migration; multiple server instances (commands are already partitioned by
gameId — see the single-instance caveat in DECISIONS.md).
