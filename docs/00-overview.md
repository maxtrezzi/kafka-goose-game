# kafka-goose-game — Implementation Documentation

This is the full, top-down description of how the project is built and *why* it
is built that way. It describes the system layer by layer. For each layer it
gives the technical choices and the reason behind them, the design patterns
used and the ones deliberately avoided, and the problems met along the way.

Terms that are standard in Kafka, Java or software design but not obvious on
first reading are collected in [chapter 11](11-glossary.md), each with a short
explanation and a link to a source.

## What this project is

A multiplayer **Game of the Goose** (Gioco dell'Oca) implemented in **plain
Java 21** with the raw **`kafka-clients`** library — no Spring, no Kafka
Streams, no framework of any kind. It exists to learn two things at once:

1. **Kafka's core mechanics**, hands-on: topics, partitions, replication,
   consumer groups, offset management, replay, delivery semantics, failure
   modes — by using the low-level client APIs directly instead of behind a
   framework's abstraction.
2. **Modern Java (21)**: records, sealed interfaces, exhaustive pattern
   matching, virtual threads, text blocks, immutable collections — used as the
   *primary* design tools, not as extras added at the end.

The result is a playable game: a 3-broker Kafka cluster in Docker, one
authoritative server process, and any number of terminal clients that join,
roll dice, and watch an ANSI board update in real time.

## The one idea everything follows from

> **The Kafka log is the only state. Everything else is a cache.**

The system is **[event-sourced](11-glossary.md#event-sourcing)** and
**[server-authoritative](11-glossary.md#server-authoritative)**:

- Clients never change state. They publish *intents* (`Command`s) to the
  `game.commands` topic.
- One server is the single authority. It turns commands into *facts*
  (`Event`s) via a pure rules engine and appends them to `game.events`.
- Every piece of state held in memory is a **[fold](11-glossary.md#fold)** over
  the event log: the events are applied one by one, in order, and the result is
  the current state. This is true of the server's game map and of every client's
  view of the board. Kill any process and restart it: it rebuilds itself by
  reading the topic again from the beginning.

Every design decision in the following chapters is a consequence of taking
this idea seriously.

## The layers, top-down

```
┌───────────────────────────────────────────────────────────────────┐
│  client-tui       Terminal UI: ANSI board renderer + stdin loop   │  ch. 06
├───────────────────────────────────────────────────────────────────┤
│  client-core      UI-agnostic client: send commands, read events, │  ch. 05
│                   fold them into a displayable GameView           │
├───────────────────────────────────────────────────────────────────┤
│  server           Authoritative host: replay, then                │  ch. 04
│                   command → decide → produce events → fold        │
├───────────────────────────────────────────────────────────────────┤
│  engine           Pure game rules, zero Kafka imports:            │  ch. 03
│                   decide(state, command, dice) → events           │
│                   state.apply(event) → state                      │
├───────────────────────────────────────────────────────────────────┤
│  protocol         The wire contract: sealed Command/Event records,│  ch. 02
│                   validation, JSON Serde, topic names             │
├───────────────────────────────────────────────────────────────────┤
│  infrastructure   3-broker KRaft cluster (Docker Compose),        │  ch. 07
│                   topics RF=3 min.insync.replicas=2; Maven build  │
└───────────────────────────────────────────────────────────────────┘
```

Dependencies point strictly downward, and they deliberately *skip* layers
where that keeps the coupling low. `client-core` depends on `protocol` only,
and **not** on `engine`, so a client never has the game rules available to it.
[Chapter 5](05-client-core.md) explains why.

## Reading guide

| Chapter | Contents |
|---|---|
| [01 — Architecture](01-architecture.md) | Event sourcing over Kafka: topics, keys, ordering, delivery guarantees, who is allowed to decide what, and the patterns that follow |
| [02 — Protocol](02-protocol.md) | The message types, why they are checked at the boundary only, the hand-written JSON Serde, and how it is protected against bad input |
| [03 — Engine](03-engine.md) | The board rules, the split between deciding and applying, why goose chains always end, and the change to the deadlock rule |
| [04 — Server](04-server.md) | Replay at startup, the command loop, one thread that owns the state, at-least-once delivery, unreadable messages, clean shutdown |
| [05 — client-core](05-client-core.md) | The client library: the event loop on a virtual thread, replay on start, and why the fold is written twice on purpose |
| [06 — client-tui](06-client-tui.md) | The terminal UI: rendering as a pure function, the snaking board layout, and input and output kept in one class |
| [07 — Infrastructure & build](07-infrastructure-and-build.md) | The KRaft cluster, the topic settings, and the Maven multi-module build |
| [08 — Testing](08-testing.md) | What is tested at each layer, the repeatable end-to-end test, and what running the real system found that unit tests missed |
| [09 — Patterns & anti-patterns](09-patterns-and-antipatterns.md) | The full list in one place: every pattern used and every anti-pattern avoided, with pointers to where |
| [10 — Implementation plan](10-implementation-plan.md) | The 8-step build order the project was actually executed in, one self-contained step per session |
| [11 — Glossary](11-glossary.md) | The Kafka, design, Java and testing terms used in these chapters, each explained in a few lines with a link to a source |

Each chapter closes with a **Decisions** and an **Issues** section, the
in-context version of the two project logs:

- [`DECISIONS.md`](../DECISIONS.md) — every non-obvious choice, with reasoning
- [`ISSUES.md`](../ISSUES.md) — every problem hit, what was tried, what fixed it

For a hands-on introduction (quickstart, TUI commands, Kafka experiments to
run against the live cluster), see the top-level [README](../README.md). The
step-by-step build order the project followed is in
[chapter 10](10-implementation-plan.md).

## Fixed constraints the project was built under

These were decided up front (see [chapter 10](10-implementation-plan.md)) and
never revisited:

| Constraint | Value | Motivation |
|---|---|---|
| Language / stack | Plain Java 21 + `kafka-clients` 4.3.0 | Learning goal: see Kafka without a framework in the way |
| Serialization | JSON via Jackson, hand-written `Serde` | Human-readable log (a learning feature in itself); no Schema Registry to operate |
| Cluster | 3 KRaft brokers, topics RF=3, `min.insync.replicas=2` | Enough replication to *demonstrate* broker-loss survival, small enough for a laptop |
| Architecture | Event-sourced, server-authoritative, topics keyed by `gameId` | This is the main thing the project sets out to teach |
| UI | Terminal first, `client-core` kept UI-agnostic | A future web/desktop UI must be able to reuse the client layer unchanged |
| Tests | JUnit 5 everywhere; Testcontainers for the E2E | `mvn test` must never require Docker; `mvn verify` proves the real stack |
