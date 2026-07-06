# 04 — Server: the Authoritative Host

[← Engine](03-engine.md) · [client-core →](05-client-core.md)

`GooseServer` is the imperative shell around the engine: the single process
allowed to write `game.events`. Its whole life is two phases inside one
`run()` call:

```
run():
  1. replayEvents()      — rebuild Map<gameId, GameState> from game.events
  2. processCommands()   — loop: poll game.commands
                                 → engine.decide()
                                 → produce events to game.events (keyed by gameId)
                                 → fold events into local state
                                 → commit offsets (only after produce confirmed)
```

It depends on `engine` (and transitively `protocol`) and is ~280 lines
including configuration — the point of the pure core is that the shell stays
small.

## Startup replay: state is throwaway

On every start the server rebuilds *all* state by reading `game.events` from
the beginning. There is no snapshot, no database, no local file. Consequences:

- a crash can never leave state and log disagreeing — the log *is* the state;
- deploying a rules change is just a restart (the fold reinterprets nothing;
  `apply` semantics are stable — see the deadlock amendment in
  [chapter 3](03-engine.md#the-deadlock-amendment));
- startup cost grows with log size, which is fine at game scale and is the
  textbook trade-off event sourcing makes (snapshots would be the next step,
  deliberately out of scope).

Replay uses a consumer with **no group**: manual `assign()` over all
partitions + `seekToBeginning()`, auto-commit off, positions never committed.
Group semantics — rebalancing, resuming from committed offsets — are features
for *sharing progress*, and replay must do the opposite: always read
everything, alone. Completion is detected by capturing `endOffsets()` first
and polling until every partition's `position()` reaches it.

Before assigning, the server checks `partitionsFor(Topics.EVENTS)` and fails
fast with a clear `IllegalStateException` ("is the cluster up and init-topics
done?") if the topic is missing — auto-topic-creation is disabled clusterwide
([chapter 7](07-infrastructure-and-build.md)), so a missing topic means a
half-started environment and the operator should know immediately, not after
a silent empty replay.

## The command loop: at-least-once, in the right order

Per poll batch:

1. Every command goes through `handleCommand`: get-or-create the game's state
   (`computeIfAbsent` — atomic get-or-insert instead of check-then-act),
   `engine.decide(state, command, dice)`.
2. Rejected commands (empty event list) are logged and dropped.
3. Accepted: every event is `producer.send(...)`, keyed by `event.gameId()` —
   the futures are collected. State is folded (`applyEvent`) immediately
   after; the local map is only ever fed by the exact events sent to the log.
4. After the batch: `producer.flush()`, then `Future.get()` on **every**
   pending send — surfacing any produce failure as an exception that kills the
   batch *before* step 5.
5. Only then `consumer.commitSync()`.

The ordering of 4 and 5 is the entire delivery-semantics story: offsets are
never committed for commands whose events might not have reached the log. A
crash anywhere before step 5 redelivers the commands; the engine's rejection
logic absorbs most duplicates, a redelivered `RollDice` rolls fresh dice —
the documented at-least-once window
([chapter 1](01-architecture.md#delivery-semantics-at-least-once-eyes-open)).

Producer config: `acks=all` + `enable.idempotence=true` — an acknowledged
write is on ≥2 replicas (`min.insync.replicas=2`) and internal retries cannot
duplicate it. Consumer config: durable group `goose-server`, auto-commit
**off** (auto-commit would commit on a timer, i.e. potentially *before* the
produce — the exact bug the manual ordering exists to prevent),
`auto.offset.reset=earliest` so a brand-new group starts from the first
command.

## Threading: single-threaded by design

The thread that calls `run()` owns *everything*: both consumers (replay, then
commands), the producer, and the `Map<String, GameState>`. There is no lock
in the file because there is no sharing — **thread confinement** as the
concurrency strategy, chosen deliberately over synchronization.

The one concession to the outside world is shutdown, and it follows Kafka's
own rules:

- `KafkaConsumer` is *not* thread-safe; its single thread-safe method is
  `wakeup()`.
- `close()` (callable from any thread — the JVM shutdown hook, the E2E test)
  therefore only **signals**: it sets `volatile boolean running = false`,
  calls `wakeup()` on whichever consumer is active (an `AtomicReference`
  updated as `run()` moves between phases), and awaits a `CountDownLatch`
  with a 10-second bound.
- The run thread notices (`WakeupException` from `poll`, or the flag),
  exits its loop, and **closes its own resources** on the way out —
  try-with-resources in the same thread that opened them. A `started` flag
  keeps `close()` from waiting on a server that never ran; the latch counts
  down in `run()`'s `finally`, so `close()` is bounded even if the loop died
  of an exception.

`volatile` is used exactly for what it is safe for — single-variable
visibility — never read-modify-write.

## Dice: `SecureRandom`, server-side only

`main()` wires `DiceRoller` to a `SecureRandom` (behind the `RandomGenerator`
interface). Clients cannot roll, and the server's rolls are not predictable
from prior observations the way a seeded `java.util.Random` would be. For a
game this is arguably ceremony — but the point of the project includes
security-by-default habits, and the cost is one line. The E2E test injects a
scripted roller through the same seam; `main` is the only place randomness
exists.

## Poison pills

Both consumers poll through a wrapper that catches Kafka's
`RecordDeserializationException`, logs partition/offset/cause, and
`seek(partition, offset + 1)` — skipping exactly the bad record. Without the
seek, the next `poll()` re-reads the same record and the loop degenerates
into a hot crash-retry cycle on one hostile byte array. A tombstone (null
command/event) is likewise handled explicitly as "nothing to do". The server
must outlive anything a client can put on `game.commands`.

## The single-instance assumption

Commands are keyed by gameId, so a consumer group of N servers would shard
games cleanly — each game processed by exactly one instance, no coordination.
But each instance folds *only its own* partitions' events after startup
replay, so its state for other instances' games would go stale (harmless —
it never acts on them — but wasteful and confusing). Scaling out properly
would mean replaying only assigned partitions and handling rebalances.
Recorded in DECISIONS.md as an explicit, documented limit rather than an
accidental one: today, run one server.

## Patterns applied

- **Imperative shell** around the pure core — all Kafka, all threading, all
  logging lives here; zero game rules.
- **Thread confinement** as the concurrency model; signal-and-wait shutdown
  via the one thread-safe channel (`wakeup`).
- **Transactional outbox discipline in miniature** — produce-confirm before
  offset-commit is the same "don't acknowledge input until output is durable"
  ordering, achieved with Kafka primitives.
- **Fail fast on environment errors** (missing topic) vs. **contain data
  errors** (poison pills) — opposite strategies, each matched to its failure
  class.

## Anti-patterns avoided

- **Auto-commit with side effects** — the default `enable.auto.commit=true`
  silently gives at-most-once for this workload; switched off and replaced
  with explicit ordering.
- **Fire-and-forget producing** — every `send` future is confirmed; a produce
  failure stops the batch instead of vanishing.
- **Shared mutable state across threads** — no state map behind a lock;
  confinement instead.
- **Crash-on-bad-input consumer loops** — the poison-pill seek.
- **Swallowed `InterruptedException`** — re-interrupt then propagate, in both
  `awaitAll` and `close`.
- **Resource ownership ambiguity** — the opener closes; `close()` from
  another thread never touches a Kafka client beyond `wakeup()`.

## Decisions (from DECISIONS.md)

Single-threaded ownership; at-least-once (not exactly-once) with the reasoning;
manual-assign replay; throwaway local state; single-instance assumption;
deterministic E2E via the DiceRoller seam.

## Issues (from ISSUES.md)

**#4** — Testcontainers vs. Docker daemon 29: the shaded docker-java client
spoke API 1.32, rejected by the daemon; env var and dependency overrides were
dead ends (the client is *shaded*), fixed with the `api.version=1.44` system
property baked into failsafe. **#6** — `kafka-clients` does not expose slf4j
at compile scope; every logging module declares `slf4j-simple` itself.
