# 04 — Server: the Authoritative Host

[← Engine](03-engine.md) · [client-core →](05-client-core.md)

`GooseServer` is the [imperative
shell](11-glossary.md#functional-core-imperative-shell) around the engine, and
the only process allowed to write to `game.events`. Its entire life is two
phases inside a single `run()` call:

```
run():
  1. replayEvents()      — rebuild Map<gameId, GameState> from game.events
  2. processCommands()   — loop: poll game.commands
                                 → engine.decide()
                                 → produce events to game.events (keyed by gameId)
                                 → fold events into local state
                                 → commit offsets (only after produce confirmed)
```

It depends on `engine`, and through it on `protocol`, and is about 280 lines
including configuration. Keeping the rules pure is what allows this outer layer
to stay this small.

## Startup replay: state is throwaway

On every start the server rebuilds *all* state by reading `game.events` from
the beginning. There is no snapshot, no database, no local file. Consequences:

- a crash can never leave the stored state and the log disagreeing, because
  the log *is* the state;
- releasing a change to the rules is just a restart: the fold does not
  reinterpret anything, and what `apply` means stays the same — see the change
  to the deadlock rule in
  [chapter 3](03-engine.md#the-change-to-the-deadlock-rule);
- startup takes longer as the log grows. At the size of a board game that does
  not matter, and it is the standard trade-off event sourcing makes. Snapshots
  would be the next step, and are deliberately out of scope.

Replay uses a consumer with **no group**: it calls `assign()` on all partitions
itself, then `seekToBeginning()`, with auto-commit off and positions never
committed. A [consumer group](11-glossary.md#consumer-group) exists to *share
progress* between readers, and replay needs the opposite: read everything,
alone, every time. The server knows it has finished by reading `endOffsets()`
first and polling until every partition's `position()` has reached it.

Before assigning anything, the server calls `partitionsFor(Topics.EVENTS)`. If
the topic is missing it stops at once with a clear `IllegalStateException`
asking whether the cluster is up and `init-topics` has run. Kafka is configured
never to create topics automatically
([chapter 7](07-infrastructure-and-build.md)), so a missing topic means the
environment is only half started. Whoever is running it should be told
immediately, instead of watching a replay that silently finds nothing.

## The command loop: at-least-once, in the right order

Per poll batch:

1. Every command goes through `handleCommand`, which fetches the game's state
   or creates it. It uses `computeIfAbsent`, which looks up and inserts in one
   step, instead of checking first and then inserting. Then it calls
   `engine.decide(state, command, dice)`.
2. Rejected commands (empty event list) are logged and dropped.
3. If the command is accepted, each event is sent with `producer.send(...)`,
   using `event.gameId()` as the key, and the returned futures are kept. The
   state is folded straight afterwards with `applyEvent`, so the local map is
   only ever built from exactly the events that were sent to the log.
4. When the batch is done: `producer.flush()`, then `Future.get()` on **every**
   pending send. Any failure to write then appears as an exception that stops
   the batch *before* step 5.
5. Only then `consumer.commitSync()`.

The order of steps 4 and 5 is the whole delivery story. Offsets are never
committed for commands whose events might not have reached the log. A crash
anywhere before step 5 means those commands are delivered again. The engine
refuses most of the repeats, but a repeated `RollDice` rolls new dice: that is
the known at-least-once gap
([chapter 1](01-architecture.md#delivery-guarantees-at-least-once-with-the-limits-stated)).

Producer settings: `acks=all` and
[`enable.idempotence=true`](11-glossary.md#idempotent-producer). A confirmed
write is on at least 2 copies, because `min.insync.replicas=2`, and Kafka's own
retries cannot write it twice. Consumer settings: the lasting group
`goose-server`, auto-commit **off**, and `auto.offset.reset=earliest` so that a
brand-new group starts at the first command. Auto-commit is off because it
commits on a timer, which could commit *before* the events were written — which
is precisely the bug the manual ordering above exists to prevent.

## Threading: single-threaded by design

The thread that calls `run()` owns *everything*: both consumers, first for
replay and then for commands, the producer, and the `Map<String, GameState>`.
There is no lock anywhere in the file because nothing is shared. Keeping all
the data inside one thread is the concurrency strategy here, chosen on purpose
instead of locking.

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

`volatile` is used only for what it is safe for: making one variable's new
value visible to another thread. It is never used for read-then-write
sequences, which it does not protect.

## Dice: `SecureRandom`, server-side only

`main()` connects `DiceRoller` to a `SecureRandom`, behind the
`RandomGenerator` interface. Clients cannot roll at all, and the server's rolls
cannot be predicted from earlier ones, which is possible with a seeded
`java.util.Random`. For a board game this is more care than strictly needed,
but building secure habits by default is part of the point of the project, and
here it costs one line. The end-to-end test supplies a fixed list of rolls
through the same seam. `main` is the only place in the system where randomness
exists at all.

## Messages that cannot be read

Both consumers poll through a small wrapper. It catches Kafka's
`RecordDeserializationException`, logs the partition, the offset and the cause,
and then calls `seek(partition, offset + 1)` to step over exactly that one
record. Without the `seek`, the next `poll()` returns the same record again and
the loop turns into an endless crash-and-retry cycle caused by a single hostile
message — a [poison pill](11-glossary.md#poison-pill). A
[tombstone](11-glossary.md#tombstone), meaning a null command or event, is
handled just as openly as "nothing to do". The server has to survive anything a
client can put on `game.commands`.

## The single-instance assumption

Commands are keyed by `gameId`, so several servers in one consumer group would
divide the games neatly between them: each game handled by exactly one server,
with no coordination needed. The problem is elsewhere. After the startup
replay, each server folds only the events of the partitions it was given, so
its copy of the other servers' games would slowly fall out of date. That is
harmless, because it never acts on those games, but it wastes memory and
confuses anyone reading the state. Doing this properly would mean replaying
only the assigned partitions and handling the moment when Kafka reassigns them.
DECISIONS.md records this as a limit that was chosen and written down, rather
than one nobody noticed: for now, run a single server.

## Patterns applied

- **Imperative shell** around a pure core — all the Kafka code, all the
  threading and all the logging live here, and none of the game rules do.
- **All state inside one thread**, with a shutdown that only signals and then
  waits, through `wakeup()`, the one method that may be called from another
  thread.
- **Do not acknowledge the input until the output is safely stored** — waiting
  for the events to be confirmed before committing offsets is the same
  ordering as the transactional outbox pattern, done with plain Kafka features.
- **Stop immediately on a broken environment** (a missing topic) but **contain
  a bad message** (a poison pill). Opposite responses, each matched to the kind
  of failure.

## Anti-patterns avoided

- **Auto-commit while doing real work** — the default
  `enable.auto.commit=true` quietly turns this workload into at-most-once. It
  is switched off and replaced with an explicit order of operations.
- **Sending and not checking** — every `send` is confirmed, so a failed write
  stops the batch instead of disappearing.
- **State shared between threads** — there is no state map behind a lock; the
  state stays in one thread instead.
- **Consumer loops that die on bad input** — handled by stepping past the bad
  record.
- **Ignoring `InterruptedException`** — the interrupt flag is set again and the
  exception is passed on, in both `awaitAll` and `close`.
- **Unclear ownership of resources** — whoever opened a resource closes it, and
  `close()` called from another thread never touches a Kafka client except
  through `wakeup()`.

## Decisions (from DECISIONS.md)

- One thread owns all the state.
- At-least-once was chosen over exactly-once, with the reasoning written down.
- Replay assigns partitions by hand instead of joining a group.
- Local state can always be thrown away and rebuilt from the log.
- The server assumes it is the only instance running.
- The end-to-end test gives the same result every time, through the
  `DiceRoller` seam.

## Issues (from ISSUES.md)

**#4** — Testcontainers against Docker daemon 29. The docker-java client
inside Testcontainers asked for API version 1.32, which the daemon refused.
Setting an environment variable did not help, and neither did overriding the
dependency, because the client is *shaded*: it is repackaged inside
Testcontainers under different class names, so a normal dependency override
never reaches it. The fix was the system property `api.version=1.44`, set
permanently in the failsafe configuration.

**#6** — `kafka-clients` does not make slf4j available at compile time, so
every module that logs declares `slf4j-simple` for itself.
