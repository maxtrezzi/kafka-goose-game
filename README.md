# kafka-goose-game

A multiplayer **Game of the Goose** (Gioco dell'Oca) built on plain **Java 21**
and **Apache Kafka**, with no frameworks. It is a test bed for what is current
in both: **Kafka 4.x** without ZooKeeper, driven through the raw client APIs
rather than a framework, and **Java 21** used for what it now offers —
records, sealed interfaces, exhaustive pattern matching, virtual threads. The
point is to see how those features behave when they carry a complete system,
not a snippet: a real cluster, a real failure mode, a game you can sit down and
play.

Everything is **[event-sourced](docs/11-glossary.md#event-sourcing) and
[server-authoritative](docs/11-glossary.md#server-authoritative)**. Clients only
send *requests*, and a single server turns them into *facts*. Every piece of
state, on the server and in each client alike, is a
[fold](docs/11-glossary.md#fold) over the log of those facts: the events applied
one by one, in order. Kill any process and restart it, and it rebuilds itself by
reading the topic again.

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
| `protocol`    | —            | The [wire contract](docs/11-glossary.md#wire-contract): sealed `Command` and `Event` records, the JSON `JsonSerde` with a fixed list of message types and a 10 KiB limit, and the topic names |
| `engine`      | protocol     | The game rules as pure functions, with no Kafka imports: `GameEngine.decide(state, command, dice) -> List<Event>` and `GameState.apply(event)` |
| `server`      | engine       | The only process that decides: it replays `game.events`, then for each command runs `decide`, writes the resulting events, and folds them into its own state (at-least-once, `acks=all`, idempotent producer) |
| `client-core` | protocol     | A client library that knows nothing about any UI: it sends commands, follows events on a virtual thread, and folds them into a `GameView` any UI can draw |
| `client-tui`  | client-core  | The terminal UI in ANSI colour: the 63-square board drawn as a snaking grid, plus a command loop reading from standard input |

The board has 63 squares:

- a goose (`*`) repeats your move;
- the bridge sends you from 6 to 12, the maze back from 42 to 39, and death at
  58 back to 1;
- the inn at 19 costs you one turn;
- the well at 31 and the prison at 52 hold you until another player lands
  there — except that the **last free player is never trapped**, so a game can
  never freeze;
- you win by landing exactly on 63, and going past it bounces you back.

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
| `roll`        | roll the dice on your turn (a roll out of turn is simply ignored by the server) |
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

Building one module on its own needs an extra flag: `mvn -pl engine -am test`.
Without `-am`, Maven looks for the other modules in the local repository, where
a fresh clone has never installed them.

What each module's tests cover:

- `protocol` — writes and reads back every message type, and rejects payloads
  that are malformed, too large, or of an unknown type.
- `engine` — every rule on the board, plus complete games played with a fixed
  list of dice rolls.
- `server` — one end-to-end test that gives the same result every time, using
  scripted dice against a real Kafka running in a container.
- `client-core` — the fold that turns events into the view.
- `client-tui` — the renderer, checked on its output with the colour codes
  removed.

## Kafka experiments

The cluster is sized for exactly these. All commands run from the repo root.

### 1. Stop a broker in the middle of a game

Both topics keep three copies of every partition, with
[`min.insync.replicas=2`](docs/11-glossary.md#replication-factor-isr-and-minimum-in-sync-replicas).
Any single broker can stop without losing a message and without stopping the
game.

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

While the broker is down, the partitions it was leading get a new leader and
`Isr:` drops to two entries. After the restart it goes back to three. Stopping a
*second* broker breaks the `min.insync.replicas=2` promise, and the producer's
`acks=all` writes start to fail. That is not a defect: it is the durability
guarantee doing exactly what it says.

### 2. Watch the event log live

The whole game is readable JSON on one topic:

```bash
docker exec goose-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:29092 --topic game.events \
  --from-beginning --property print.key=true
```

Play a few turns and watch the `DiceRolled`, `PlayerMoved` and `PlayerStuck`
facts appear, each keyed by its `gameId`. This is also the quickest way to
settle an argument about what the rules did.

### 3. Inspect consumer groups and offsets

```bash
docker exec goose-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --list

docker exec goose-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --describe --group goose-server
```

You will see one lasting group, `goose-server`. Its `CURRENT-OFFSET` on
`game.commands` moves forward only after the events that command produced have
been safely written — that ordering is what makes the delivery
[at-least-once](docs/11-glossary.md#delivery-semantics-at-most-once-at-least-once-exactly-once).

You will also see one `goose-client-<uuid>` group per running client. Those
never commit anything at all: a client always reads from `earliest` and keeps
nothing of its own.

### 4. Replay a finished game

State is disposable everywhere; the log is the truth.

- Restart the **server**. It logs how many events it read back and can carry on
  with any unfinished game. Games that already ended are rebuilt too, `GameWon`
  included.
- Restart a **client** with an old `gameId`. The whole board comes back from the
  log alone, winner line and all.
- Or work through the log yourself, with the console consumer from experiment 2.
  Every board any client ever displayed can be derived from that stream.

### 5. Not done here: SASL/SCRAM and ACLs

The cluster runs on PLAINTEXT on purpose, so that every experiment above works
without setting up credentials first. Closing that gap is the obvious next
step, and it is a small one. Add a `SASL_PLAINTEXT` listener using
SCRAM-SHA-256, create the users
`goose-server` and `goose-client` with `kafka-configs.sh`, then use
`kafka-acls.sh` to give clients permission to *write* only to `game.commands`
and to *read* only from `game.events`, with the opposite rights for the server.
That puts the rule "clients ask, the server decides" into the brokers
themselves, instead of trusting the code to respect it.

## Documentation

The full description of how the project is built, and why, lives in
[`docs/`](docs/00-overview.md): the architecture, one chapter per layer, the
infrastructure, the testing strategy, and a complete list of the patterns used
and the anti-patterns avoided, each with the reasoning behind it.

Terms that are standard in Kafka, Java or software design but not obvious on
first reading are explained in the [glossary](docs/11-glossary.md), each in a
few lines and with a link to a source. The same chapters are also available as
one [PDF](docs/kafka-goose-game-implementation.pdf), which can be rebuilt with
`./docs/build-pdf.sh` (it needs pandoc and WeasyPrint).

Project logs:

- [Implementation plan](docs/10-implementation-plan.md) — the 8-step plan this
  project was built from
- [DECISIONS.md](DECISIONS.md) — every non-obvious design call, with reasoning
- [ISSUES.md](ISSUES.md) — every problem hit along the way and its actual fix

## Future ideas

- A web UI, using Javalin with server-sent events, built on `client-core`.
- A move from JSON to Avro with a Schema Registry.
- Running several server instances at once. Keying by `gameId` already allows
  it; DECISIONS.md explains why there is only one today.

## License

[MIT](LICENSE) — © 2026 maxtrezzi.
