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

This command/event split is the architecture. Everything below is about
making each half honest.

## Why event sourcing, and why here

Event sourcing is often overkill. It was chosen here because it is the single
architecture that exercises the most Kafka concepts at once, on a domain small
enough to hold in your head:

- **The log as source of truth** — Kafka's actual data model, not a queue
  metaphor. State is derived, disposable, and rebuildable (`replay`).
- **Ordering** — a game's correctness depends on event order; Kafka only
  orders within a partition, which forces you to understand keying.
- **Delivery semantics** — "did my event get written exactly once?" stops
  being an abstract FAQ and becomes a bug you can actually cause.
- **Multiple independent consumers** — server and N clients all read the same
  `game.events` with different group semantics and different needs.

A board game is an ideal domain for it: turns are inherently sequential
events, state is small, and rules are pure logic — so the *infrastructure*
concepts stay in focus.

## Server-authoritative: the trust model

Only the server writes `game.events`, and only the server rolls dice
(`SecureRandom`, [chapter 4](04-server.md)). Clients are untrusted by
construction:

- A client cannot move a token — there is no command for it. It can only
  *ask* to join, start, or roll.
- An out-of-turn or otherwise invalid command produces **no events**; the
  server logs the rejection and moves on ([chapter 3](03-engine.md)).
- A malicious or buggy client publishing garbage to `game.commands` is
  contained at two boundaries: the deserializer (payload cap, closed type set,
  field validation — [chapter 2](02-protocol.md)) and the engine (phase/turn
  checks).

The one thing the demo cluster does *not* enforce is broker-side write
authorization — any process could write `game.events` directly. That is a
conscious scoping decision: the cluster stays PLAINTEXT for learnability, and
the README documents SASL/SCRAM + ACLs (clients write-only on commands,
read-only on events) as the follow-up exercise that would close the gap.

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
3. **Parallelism headroom.** 3 partitions is enough to demonstrate points 1–2
   and matches the 3 brokers; nothing more was needed.

The fold (`GameState.apply`, `GameView.apply`) filters or groups by `gameId`,
so consumers reading the whole topic stay correct even though partitions
interleave different games.

## State is a fold — everywhere, identically

Three folds exist in the system, and their equivalence is a design invariant:

| Fold | Where | Purpose |
|---|---|---|
| `GameState.apply(Event)` | engine, used by server | Authoritative state for rule decisions |
| `GameState.apply(Event)` | inside `GameEngine.decide` | The engine folds its *own* freshly-decided events before computing the next turn — decision logic and state logic share one source of truth |
| `GameView.apply(Event)` | client-core | Displayable state for UIs (adds a recent-event log) |

`GameView` deliberately re-implements the fold rather than reusing
`GameState` — the reasoning (dependency hygiene vs. code duplication, and why
the wire protocol is the real contract) is examined in
[chapter 5](05-client-core.md#the-deliberately-duplicated-fold).

The fold **trusts the log**. `apply` does not re-validate cross-event
invariants (e.g. that a `TurnStarted` names a player who joined): those are
guaranteed by the engine at decision time, and events are only ever produced
by the server. Validating in both places would mean maintaining the rules
twice — the exact duplicated-business-logic trap the code style rules warn
about. What `apply` *does* check is the per-event boundary: each record
validates its own fields in its compact constructor, and `apply` rejects
events addressed to a different `gameId`.

## Delivery semantics: at-least-once, eyes open

The server implements **at-least-once** processing, and the limitation is
documented rather than hidden:

- Producer: `acks=all` + `enable.idempotence=true` — an event acknowledged by
  the broker is on at least 2 of 3 replicas and was not duplicated by retries.
- The command consumer's offsets are committed **only after** every produced
  event is confirmed (`flush()` then `Future.get()` before `commitSync()`).

A crash between "events produced" and "offsets committed" therefore replays
the commands from the last commit. For most commands the engine's rejection
logic makes the replay a no-op (a second `JoinGame` for a joined player
produces nothing), but a redelivered `RollDice` **rolls again** — dice are
non-deterministic per state. True exactly-once would require Kafka
transactions (produce + offset-commit atomically); that was deliberately left
out of scope as the wrong complexity for a learning project, and the trade-off
is recorded in `DECISIONS.md`. The important part pedagogically: the failure
window is *understood and chosen*, not stumbled into.

Clients need no delivery guarantees at all: they never commit offsets, they
replay from the beginning on every start, and folding is idempotent from a
fixed starting state.

## Replay: three consumers, three offset strategies

The same topic is read three different ways, which together cover most of
Kafka's consumer-offset design space:

| Consumer | Group | Offsets | Why |
|---|---|---|---|
| Server replay (startup) | none — manual `assign` + `seekToBeginning` | never committed | Replay must *always* read everything; group semantics (rebalancing, committed positions) would actively fight that |
| Server command loop | durable group `goose-server` | committed manually after produce-confirm | The one consumer whose position *is* meaningful state |
| Every client | throwaway group `goose-client-<uuid>`, `auto.offset.reset=earliest` | never committed | Each client start is a full replay; the group exists only because the consumer API requires one for `subscribe()` |

## Failure containment

- **Poison pills** (records that fail deserialization) are logged and skipped
  by seeking past the offending offset — both server and clients. A consumer
  loop must never die because of one bad record it will re-read forever.
- **Broker loss**: RF=3 with `min.insync.replicas=2` means any single broker
  can die without data loss or downtime — verified live by playing a complete
  game with one broker stopped ([chapter 8](08-testing.md)). Losing a second
  broker makes `acks=all` writes fail — loudly, which is the durability
  contract working as intended.
- **Listener/UI failures** on the client are caught and logged so a rendering
  bug cannot stop the event stream ([chapter 5](05-client-core.md)).

## Patterns applied at this level

- **Event Sourcing** — state as a fold over an append-only log.
- **CQRS in miniature** — commands (write intents) and events (read facts) on
  separate channels with separate types; no shared "message" superclass.
- **Single Writer** — one authority per game's history (the server; per
  partition via keying).
- **Functional core, imperative shell** — pure `decide`/`apply` in the engine,
  all I/O pushed to the server and client edges (chapters 3–5).

## Anti-patterns avoided at this level

- **Dual writes** — the server never writes state *and* events as separate
  operations that could diverge; the local state map is always derived from
  exactly the events that were produced.
- **Database-as-message-bus / shared mutable state** — processes communicate
  only through the two topics; no process reads another's memory.
- **Trusting the client** — no client-computed moves, no client-side dice.
- **Hidden delivery semantics** — the at-least-once window is documented at
  the exact code site that creates it, instead of pretending to exactly-once.

## Decisions in this chapter

From [`DECISIONS.md`](../DECISIONS.md): rejected commands produce no events;
at-least-once over exactly-once; replay via manual assign; state is throwaway;
single-instance assumption; topic names as shared constants in `protocol`.

## Issues hit at this level

From [`ISSUES.md`](../ISSUES.md): #7 — the well+prison mutual-trap deadlock,
which looked like an engine bug but was really an *architectural* lesson: with
an append-only log you cannot "fix the data", only fix the rules going
forward, and old logs must still fold correctly (see
[chapter 3](03-engine.md#the-deadlock-amendment)).
