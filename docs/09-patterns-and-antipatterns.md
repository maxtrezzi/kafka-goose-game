# 09 — Patterns Applied and Anti-Patterns Avoided: the Full List

[← Testing](08-testing.md) · [Implementation plan →](10-implementation-plan.md)

Everything in one place. Each entry says where the pattern lives in the code
and which chapter explains the reasoning. One idea runs through the whole list:
**most entries are simply Java 21 language features or Kafka features used the
way they were meant to be used**. The project is built on the belief that
modern languages have absorbed many patterns that once had to be written by
hand.

## Architectural patterns

| Pattern | Where | Chapter |
|---|---|---|
| **Event Sourcing** | `game.events` as sole source of truth; all state a fold; replay everywhere | [01](01-architecture.md) |
| **[CQRS](11-glossary.md#cqrs-and-the-read-model), on a small scale** | Commands and events on separate topics *and* as separate sealed types; `GameView` is the read model | [01](01-architecture.md), [05](05-client-core.md) |
| **One writer only** | Only the server writes `game.events`, and keying by `gameId` means one writer per game | [01](01-architecture.md) |
| **[Functional core, imperative shell](11-glossary.md#functional-core-imperative-shell)** | The pure `engine` inside the `server` that talks to Kafka; the pure `BoardRenderer` inside `Main`, which does the printing | [03](03-engine.md), [04](04-server.md), [06](06-client-tui.md) |
| **[Ports and adapters](11-glossary.md#ports-and-adapters-hexagonal-architecture)** | `GameListener` is the port for a UI; `client-core` knows of no UI at all | [05](05-client-core.md) |
| **State machine** | A `Phase` enum plus switches covering every case; a move that is not allowed is refused | [03](03-engine.md) |
| **Outbox-style ordering** | Confirm the write *first*, commit the offset second — never accept the input before the output is safely stored | [04](04-server.md) |

## Design and language patterns: Java 21 doing the work

| Pattern | How it is done | Chapter |
|---|---|---|
| **Algebraic data types** | A [`sealed interface`](11-glossary.md#sealed-interface) with one [`record`](11-glossary.md#record) per case for `Command` and `Event`, and switches with **no `default`**, so adding a message type breaks every fold and every renderer at compile time | [02](02-protocol.md) |
| **[Parse, don't validate](11-glossary.md#parse-dont-validate)** | All checks happen in the [compact constructors](11-glossary.md#compact-constructor), so an invalid message cannot exist as an object, no matter where it came from | [02](02-protocol.md) |
| **Make invalid states impossible to express** | `Optional` fields for "not there"; a `Phase` enum instead of boolean flags; records that cannot be changed at any depth, using `List.copyOf` and `Map.copyOf` | [02](02-protocol.md), [03](03-engine.md) |
| **Strategy through a functional interface** | `DiceRoller` is the one [seam](11-glossary.md#seam) that replaces a mocking framework, and `Clock` does the same for time | [03](03-engine.md), [08](08-testing.md) |
| **Static factory method** | `GameClient.connect()` stops `this` escaping during construction and gives the operation a name | [05](05-client-core.md) |
| **Copy methods for building the next record** | Explicit `withPosition`, `withStuck` and similar, because Java still has no `with` expression up to version 25 | [03](03-engine.md) |
| **Comparing by value as a test tool** | The end-to-end test compares whole events with `assertEquals`, which works because the messages are records | [08](08-testing.md) |

## Concurrency patterns

| Pattern | Where | Chapter |
|---|---|---|
| **All state inside one thread** | In the server, one thread owns the consumers, the producer and the state map, so there are no locks at all; the client event loop works the same way | [04](04-server.md), [05](05-client-core.md) |
| **Shutdown that signals rather than touches** | A `volatile` flag, then `consumer.wakeup()` — the one consumer method that may be called from another thread — then a wait with a time limit; whoever opened a resource closes it | [04](04-server.md), [05](05-client-core.md) |
| **[Virtual threads](11-glossary.md#virtual-thread) for I/O loops** | The client event loop, and the server itself in the end-to-end test | [05](05-client-core.md), [08](08-testing.md) |
| **Publishing an unchangeable value** | `volatile GameView view`, which can be read without locking because the value itself never changes | [05](05-client-core.md) |

## Kafka patterns

| Pattern | Where | Chapter |
|---|---|---|
| **[Keys](11-glossary.md#topic-partition-key) to keep one game in order** | Every record is keyed by `gameId` | [01](01-architecture.md) |
| **[At-least-once](11-glossary.md#delivery-semantics-at-most-once-at-least-once-exactly-once) with a deliberate order of operations** | Flush and confirm every write before `commitSync`, with auto-commit off | [04](04-server.md) |
| **[Idempotent producer](11-glossary.md#idempotent-producer), `acks=all`, and at least 2 copies in sync** | Producer settings in the server and topic settings in the cluster | [04](04-server.md), [07](07-infrastructure-and-build.md) |
| **Replay by assigning partitions by hand** | No [consumer group](11-glossary.md#consumer-group) for the server's startup replay; clients use a new group each run plus `earliest` | [04](04-server.md), [05](05-client-core.md) |
| **Stepping past a [poison pill](11-glossary.md#poison-pill)** | Catch `RecordDeserializationException`, log it, then `seek(offset+1)` | [04](04-server.md) |
| **Letting [tombstones](11-glossary.md#tombstone) through** | The Serde returns null for null, and the code that receives it handles null openly | [02](02-protocol.md) |

## Anti-patterns avoided — and where the temptation was real

Security & robustness:

- **Jackson default typing**, which can end in remote code execution → an
  explicit `@JsonSubTypes` list on sealed types, so a message can name 11 record
  types and nothing else, ever.
- **Input with no size limit and no checks** → a 10 KiB limit before parsing,
  and names limited to `[A-Za-z0-9_-]`, which removes injection into logs,
  terminals and shells at the boundary.
- **Trusting a client with randomness or state** → the dice are rolled only on
  the server, with `SecureRandom`.
- **Consumer loops that die on a bad record** → unreadable messages are skipped
  and are never fatal.

Correctness:

- **Writing the same change to two places** → the local state is built only
  from exactly the events that were produced.
- **Delivery guarantees nobody states** → the at-least-once gap is documented
  in the code that creates it, and auto-commit, which would quietly turn this
  into at-most-once, is switched off on purpose.
- **The same business rule in two places** → the rules live once, in `decide`,
  and the fold trusts the log. The one repetition that *is* deliberate, the
  client's fold, is explained, kept in check by the shared protocol tests, and
  written down: the choice between harmful repetition and a shared abstraction
  that does not fit was made openly. See [coincidental
  duplication](11-glossary.md#coincidental-duplication).
- **Rules that can loop for ever** → goose chains are proved to end, and a roll
  of zero is refused by `Board.resolve`.
- **Using exceptions for normal outcomes** → a refusal is data, an empty list,
  not a thrown exception.

Concurrency:

- **Sharing changeable state and hoping it works out** → the state stays in one
  thread, instead of adding locks later.
- **Letting `this` escape from a constructor** → the `connect()` factory starts
  the thread after the object is built.
- **Ignoring `InterruptedException`** → always set the interrupt flag again,
  then pass the exception on.
- **Using `volatile` for read-then-write** → `volatile` is used only for flags
  and for references to values that never change.

Good housekeeping:

- **Methods that return null** → `Optional` or empty collections instead. The
  two tombstone nulls are the only exception, and they are commented.
- **Losing the cause of an exception** → every wrapper passes the cause on, and
  messages about unreadable records use `toString()`, because `getMessage()`
  can be null.
- **Output that depends on the machine's language** → the renderer fixes
  `Locale.ROOT`.
- **Raw control bytes in source files** → ANSI escapes are written only as
  `\u001B`. The project hit this twice, once in generated Java and once in
  these documents; both times a tool introduced them and a person spotted them.
- **Versions drifting over time** → every library and plugin has its version
  fixed in the parent POM.
- **Using sleeps to keep things in step, in tests and in scripts** → the test
  driver reacts to events, compose waits on health checks, and since issue #8
  a fault is a starting condition rather than something injected on a timer.

## Where to read the histories

- [`DECISIONS.md`](../DECISIONS.md) — every choice that was not obvious,
  grouped by build step, with the reasoning and the conditions under which it
  should be reconsidered.
- [`ISSUES.md`](../ISSUES.md) — all 8 problems: what was seen, what was tried
  and failed, what actually fixed it, and the lesson that carries over to other
  projects.
- [Chapter 11](11-glossary.md) — the terms used above, each explained in a few
  lines with a link to a source.
