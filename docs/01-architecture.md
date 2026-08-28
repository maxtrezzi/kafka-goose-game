# 01 — Architecture: Event Sourcing over Kafka

[← Overview](00-overview.md) · [Protocol →](02-protocol.md)

## The shape of the system

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
```

Two topics, one direction each:

| Topic | Written by | Read by | Meaning |
|---|---|---|---|
| `game.commands` | clients | the server | **Intents** — requests that may be rejected |
| `game.events` | the server only | the server (replay) and every client | **Facts** — things that irrevocably happened |

This split between commands and events *is* the architecture. The rest of this
chapter is about keeping each half strict: commands that can be refused, events
that can be trusted.

## Why event sourcing, and why here

[Event sourcing](11-glossary.md#event-sourcing) is more than most projects
need. It was chosen here because no other architecture uses so many Kafka
concepts at the same time, on a subject that stays simple enough to follow:

- **The log as source of truth** — Kafka's actual data model, not a queue
  metaphor. State is derived, disposable, and rebuildable (`replay`).
- **Ordering** — a game is only correct if its events are handled in order,
  and Kafka orders messages inside one partition only. That forces you to
  understand [keys](11-glossary.md#topic-partition-key).
- **[Delivery guarantees](11-glossary.md#delivery-semantics-at-most-once-at-least-once-exactly-once)**
  — "was my event written exactly once?" stops being a question in a manual and
  becomes a bug you can cause yourself.
- **Several independent readers** — the server and any number of clients read
  the same `game.events`, in different [consumer
  groups](11-glossary.md#consumer-group) and for different reasons.

A board game is an ideal domain for it: turns are inherently sequential
events, state is small, and rules are pure logic — so the *infrastructure*
concepts stay in focus.

## Server-authoritative: the trust model

Only the server writes `game.events`, and only the server rolls dice
(`SecureRandom`, [chapter 4](04-server.md)). The design assumes clients cannot
be trusted, and does not rely on them behaving well:

- A client cannot move a token — there is no command for it. It can only
  *ask* to join, start, or roll.
- An out-of-turn or otherwise invalid command produces **no events**; the
  server logs the rejection and moves on ([chapter 3](03-engine.md)).
- A malicious or buggy client publishing garbage to `game.commands` is
  contained at two boundaries: the deserializer (payload cap, closed type set,
  field validation — [chapter 2](02-protocol.md)) and the engine (phase/turn
  checks).

There is one thing the demo cluster does *not* check: the brokers accept a
write from anyone, so any process could write to `game.events` directly. This
is a deliberate limit on the scope of the project. The cluster stays on
PLAINTEXT so that every command-line experiment works without setting up
credentials first. The README describes the missing piece — SASL/SCRAM with
access rules, letting clients only write commands and only read events — as an
exercise to do next.

## Keying, partitions, and ordering

Both topics have **3 partitions** and every record is **keyed by `gameId`**.
Consequences, in order of importance:

1. **Per-game total order.** All events of one game land in one partition, so
   every consumer sees that game's history in exactly the order the server
   decided it. Cross-game order is not guaranteed — and doesn't matter,
   because games are independent.
2. **Per-game single writer at scale.** Commands for one game also hash to one
   partition, so if the server were ever scaled to multiple instances in one
   consumer group, each game would still be processed by exactly one instance
   — no distributed locking needed. (The current server is deliberately
   single-instance; the caveat is recorded in
   [chapter 4](04-server.md#the-single-instance-assumption).)
3. **Room to grow.** Three partitions are enough to show points 1 and 2, and
   they match the three brokers. Nothing more was needed.

The fold (`GameState.apply`, `GameView.apply`) filters or groups by `gameId`,
so consumers reading the whole topic stay correct even though partitions
interleave different games.

## State is a fold — everywhere, identically

There are three [folds](11-glossary.md#fold) in the system, and the design
depends on all three producing the same result:

| Fold | Where | Purpose |
|---|---|---|
| `GameState.apply(Event)` | engine, used by server | Authoritative state for rule decisions |
| `GameState.apply(Event)` | inside `GameEngine.decide` | The engine folds its *own* freshly-decided events before computing the next turn — decision logic and state logic share one source of truth |
| `GameView.apply(Event)` | client-core | Displayable state for UIs (adds a recent-event log) |

`GameView` writes the fold again instead of reusing `GameState`, on purpose.
The reasons — keeping dependencies clean rather than avoiding repetition, and
why the message format is the real contract — are set out in
[chapter 5](05-client-core.md#the-deliberately-duplicated-fold).

The fold **trusts the log**. `apply` does not check rules that span several
events — for example, that a `TurnStarted` names a player who actually joined.
The engine already guaranteed that when it decided the event, and only the
server ever writes events. Checking it again here would mean keeping the same
rules in two places, which is exactly the kind of repeated business logic the
project's code style rules forbid. What `apply` *does* check is each event on
its own: every record validates its own fields in its [compact
constructor](11-glossary.md#compact-constructor), and `apply` refuses events
that belong to a different `gameId`.

## Delivery guarantees: at-least-once, with the limits stated

The server processes commands
**[at-least-once](11-glossary.md#delivery-semantics-at-most-once-at-least-once-exactly-once)**.
The weakness that comes with that choice is written down rather than hidden:

- Producer: `acks=all` plus
  [`enable.idempotence=true`](11-glossary.md#idempotent-producer). Once the
  broker confirms an event, that event is on at least 2 of the 3 copies, and a
  network retry cannot have written it twice.
- The command consumer's offsets are committed **only after** every produced
  event is confirmed (`flush()` then `Future.get()` before `commitSync()`).

So if the server crashes after writing events but before committing offsets,
it reads those commands again on restart. For most commands this changes
nothing: the engine simply rejects them a second time, and a repeated
`JoinGame` for a player who already joined produces no events. A repeated
`RollDice` is different — it **rolls again**, because the dice are random and
the same state can give a different result.

Real exactly-once processing would need Kafka transactions, so that producing
events and committing offsets happen as one atomic step. That was left out on
purpose: it is the wrong kind of complexity for a learning project, and the
trade-off is recorded in `DECISIONS.md`. The point worth learning is that this
gap is *known and accepted*, not discovered by accident.

Clients need no delivery guarantees at all: they never commit offsets, they
replay from the beginning on every start, and folding is idempotent from a
fixed starting state.

## Replay: three consumers, three offset strategies

The same topic is read three different ways, which together cover most of
Kafka's consumer-offset design space:

| Consumer | Group | Offsets | Why |
|---|---|---|---|
| Server replay (startup) | none — manual `assign` + `seekToBeginning` | never committed | Replay must *always* read everything, and a consumer group works against that: it would remember a position and reassign partitions |
| Server command loop | durable group `goose-server` | committed manually after produce-confirm | The one consumer whose position *is* meaningful state |
| Every client | a new group per run, `goose-client-<uuid>`, with `auto.offset.reset=earliest` | never committed | Every client start reads the whole history; the group exists only because `subscribe()` requires one |

## Failure containment

- **[Poison pills](11-glossary.md#poison-pill)** — records that cannot be
  deserialized — are logged and skipped, by moving the consumer past that
  offset. Both the server and the clients do this. A consumer loop must never
  die because of one bad record, because on restart it would read the same
  record and die again.
- **Broker loss** — with three copies of each partition and
  [`min.insync.replicas=2`](11-glossary.md#replication-factor-isr-and-minimum-in-sync-replicas),
  any one broker can stop without losing data and without stopping the game.
  This was checked by playing a full game with one broker down
  ([chapter 8](08-testing.md)). If a second broker is lost, `acks=all` writes
  start failing, and they fail visibly: that is the durability promise doing
  its job, not a defect.
- **Listener/UI failures** on the client are caught and logged so a rendering
  bug cannot stop the event stream ([chapter 5](05-client-core.md)).

## Patterns applied at this level

- **Event Sourcing** — state as a fold over an append-only log.
- **[CQRS](11-glossary.md#cqrs-and-the-read-model) on a small scale** —
  commands (requests to change something) and events (records of what happened)
  travel on separate topics with separate types. There is no shared "message"
  parent class.
- **Single Writer** — one authority per game's history (the server; per
  partition via keying).
- **[Functional core, imperative
  shell](11-glossary.md#functional-core-imperative-shell)** — `decide` and
  `apply` in the engine are pure functions; all input and output happens in the
  server and the clients (chapters 3–5).

## Anti-patterns avoided at this level

- **Dual writes** — writing the same change to two places, which can then
  disagree. The server never stores state *and* events as two separate
  operations: its local map is always built from exactly the events it
  produced.
- **Database-as-message-bus / shared mutable state** — processes communicate
  only through the two topics; no process reads another's memory.
- **Trusting the client** — no client-computed moves, no client-side dice.
- **Hidden delivery guarantees** — the at-least-once gap is documented in the
  code that creates it, instead of claiming exactly-once.

## Decisions in this chapter

From [`DECISIONS.md`](../DECISIONS.md):

- A rejected command produces no events at all.
- At-least-once was chosen over exactly-once.
- Replay assigns partitions manually instead of joining a consumer group.
- In-memory state can always be thrown away and rebuilt.
- The server assumes it is the only instance running.
- Topic names live in `protocol` as shared constants.

## Issues hit at this level

From [`ISSUES.md`](../ISSUES.md), issue #7: two players could trap each other
in the well and the prison, and the game could never continue. It looked like a
bug in the engine, but the real lesson was about architecture. With a log you
can only add to, you cannot go back and "correct the data"; you can only change
the rules from now on, and the events already written must still fold correctly
under the new rules (see
[chapter 3](03-engine.md#the-change-to-the-deadlock-rule)).
