# kafka-goose-game — Implementation Documentation

This is the full, top-down description of how the project is built and *why* it
is built that way. It combines the system description, the technical choices and
their motivations, the design patterns applied, the anti-patterns deliberately
avoided, and the decisions and issues encountered along the way.

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
   *primary* design tools, not as decoration.

The result is a playable game: a 3-broker Kafka cluster in Docker, one
authoritative server process, and any number of terminal clients that join,
roll dice, and watch an ANSI board update in real time.

## The one idea everything follows from

> **The Kafka log is the only state. Everything else is a cache.**

The system is **event-sourced** and **server-authoritative**:

- Clients never change state. They publish *intents* (`Command`s) to the
  `game.commands` topic.
- One server is the single authority. It turns commands into *facts*
  (`Event`s) via a pure rules engine and appends them to `game.events`.
- Every piece of in-memory state — the server's game map, every client's board
  view — is a **fold** (a left-reduce) over the event log. Kill any process,
  restart it, and it rebuilds itself by replaying the topic from the beginning.

Every design decision in the following chapters is a consequence of taking
this idea seriously.

## The layers, top-down

```
┌───────────────────────────────────────────────────────────────────┐
│  client-tui       Terminal UI: ANSI board renderer + stdin loop   │  ch. 06
├───────────────────────────────────────────────────────────────────┤
│  client-core      UI-agnostic client: send commands, tail events, │  ch. 05
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

Dependencies point strictly downward, and deliberately *skip* layers where
that keeps coupling low: `client-core` depends on `protocol` only — **not** on
`engine` — so a client never carries game rules on its classpath (the
motivation is examined in [chapter 5](05-client-core.md)).

## Reading guide

| Chapter | Contents |
|---|---|
| [01 — Architecture](01-architecture.md) | Event sourcing over Kafka: topics, keying, ordering, delivery semantics, the trust model, and the system-level patterns |
| [02 — Protocol](02-protocol.md) | The message hierarchy, validation-at-the-boundary, the hand-written JSON Serde, and its security posture |
| [03 — Engine](03-engine.md) | The board rules, the decide/apply split, the goose-chain termination argument, and the deadlock rule amendment |
| [04 — Server](04-server.md) | Replay, the command loop, single-threaded ownership, at-least-once delivery, poison pills, graceful shutdown |
| [05 — client-core](05-client-core.md) | The client library: virtual-thread event loop, replay-on-start, the deliberately duplicated fold |
| [06 — client-tui](06-client-tui.md) | The terminal UI: pure rendering, the serpentine board, I/O at the edge |
| [07 — Infrastructure & build](07-infrastructure-and-build.md) | The KRaft cluster, topic configuration, and the Maven multi-module setup |
| [08 — Testing](08-testing.md) | The test strategy per layer, the deterministic E2E, and what live smoke-testing caught that unit tests could not |
| [09 — Patterns & anti-patterns](09-patterns-and-antipatterns.md) | The consolidated catalog: every pattern applied and every anti-pattern designed out, with pointers to where |

Each chapter closes with a **Decisions** and an **Issues** section, the
in-context version of the two project logs:

- [`DECISIONS.md`](../DECISIONS.md) — every non-obvious choice, with reasoning
- [`ISSUES.md`](../ISSUES.md) — every problem hit, what was tried, what fixed it

For a hands-on introduction (quickstart, TUI commands, Kafka experiments to
run against the live cluster), see the top-level [README](../README.md). The
step-by-step build order the project followed is in [`PLAN.md`](../PLAN.md).

## Fixed constraints the project was built under

These were decided up front (see `PLAN.md`) and never revisited:

| Constraint | Value | Motivation |
|---|---|---|
| Language / stack | Plain Java 21 + `kafka-clients` 4.3.0 | Learning goal: see Kafka without a framework in the way |
| Serialization | JSON via Jackson, hand-written `Serde` | Human-readable log (a learning feature in itself); no Schema Registry to operate |
| Cluster | 3 KRaft brokers, topics RF=3, `min.insync.replicas=2` | Enough replication to *demonstrate* broker-loss survival, small enough for a laptop |
| Architecture | Event-sourced, server-authoritative, topics keyed by `gameId` | The pedagogical core of the project |
| UI | Terminal first, `client-core` kept UI-agnostic | A future web/desktop UI must be able to reuse the client layer unchanged |
| Tests | JUnit 5 everywhere; Testcontainers for the E2E | `mvn test` must never require Docker; `mvn verify` proves the real stack |
