# 05 — client-core: the UI-Agnostic Client Library

[← Server](04-server.md) · [client-tui →](06-client-tui.md)

`client-core` is the seam that keeps UIs cheap: everything a client needs to
participate in a game — sending commands, following events, folding them into
displayable state — with **no console I/O and no UI assumptions**. A web or
desktop UI would depend on this module exactly as the TUI does, implementing
one interface.

Three types:

| Type | Role |
|---|---|
| `GameClient` | Connection: command producer + event-loop consumer on a virtual thread |
| `GameView` | Immutable displayable state: the client-side fold, plus a recent-events log |
| `GameListener` | The UI contract: `onEvent(Event)`, `onViewUpdated(GameView)` |

Dependency: **`protocol` only.** Not `engine` — deliberately.

## The deliberately duplicated fold

`GameView.apply(Event)` re-implements the event fold instead of reusing the
engine's `GameState`. This is the most debatable decision in the codebase, so
the reasoning is spelled out:

**Why duplicate?**

- The implementation plan fixes the dependency graph: a client needs *no game
  rules* on its classpath. `GameState` lives in `engine`, and pulling `engine`
  in for its fold would drag the rule book (Board, GameEngine) into every
  future UI — clients that must never make rule decisions would have the means
  to.
- The two folds serve different masters and were already diverging:
  `GameView` additionally maintains `recentEvents` (a bounded display log)
  and will grow UI-facing conveniences that have no place in the engine.
- **The wire protocol — not a shared class — is the contract.** Server and
  client agree because they consume the same sealed `Event` hierarchy, and
  both folds are exhaustive switches over it: a new event type breaks *both*
  compilations until both folds handle it. The shared protocol tests are the
  guard.

**What it costs:** the fold semantics exist twice (~100 lines), and a
behavioral divergence between them would show as a client rendering a
different board than the server believes. Accepted with eyes open and
recorded in DECISIONS.md — this is the *coincidental-vs-shared* duplication
judgment call: the two folds are intentionally allowed to evolve apart,
which is exactly when extraction is the wrong move.

`GameView` itself follows the same value discipline as `GameState`: a record,
deep-immutable via `List.copyOf`/`Map.copyOf` in the compact constructor,
`Optional` for absence, exhaustive switch, private withers.
`recentEvents` is capped at 10 (`RECENT_EVENTS_LIMIT`), oldest evicted first
— a display log, not a history (the history is the topic).

## Replay-on-start: the client keeps nothing

Every `GameClient` start creates a consumer with a **fresh group id**
(`goose-client-<uuid>`), `auto.offset.reset=earliest`, auto-commit off,
offsets never committed. So a client (re)started mid-game rebuilds its whole
view by replaying `game.events` from the beginning — the "kill a client and
watch the board reappear" demo is not a feature bolted on, it *is* the read
model. The throwaway group exists only because `subscribe()` requires one;
nothing is ever stored under it.

Events are filtered client-side by `gameId` (the topic is shared by all
games) and tombstones are skipped.

## Threading model

- **The event loop owns the consumer and the fold.** It runs on a **virtual
  thread** (`Thread.ofVirtual()`) — the poll loop is I/O-bound waiting, the
  canonical virtual-thread workload; a platform thread per client would be
  waste. The `view` field is `volatile` so any thread can read the latest
  snapshot via `view()` (safe because `GameView` is immutable — publication
  is the only concern).
- **Listener callbacks run on the event-loop thread, in log order.** The
  contract is documented on `GameListener`: a UI that needs its own thread
  hands off itself. A listener exception is caught, logged and skipped — a UI
  rendering bug must not stop the event stream (`notifyListener`).
- **Command senders and `close()` may be any thread.** `KafkaProducer` is
  thread-safe by contract (the one Kafka client that is); shutdown follows
  the same signal-don't-touch protocol as the server: `volatile running`
  flag, `consumer.wakeup()` via `AtomicReference`, then `eventLoop.join(
  CLOSE_TIMEOUT)` — Java 21's `join(Duration)` — logging a warning if the
  loop fails to stop in 10 s. The producer is closed by `close()` because
  `close()`'s thread owns it (it was created in the constructor, used via
  thread-safe API).

### The `connect()` static factory

`GameClient`'s constructor is private and does **not** start the event loop;
it creates the virtual thread *unstarted*. The public entry is:

```java
public static GameClient connect(String bootstrap, String gameId, GameListener listener) {
    var client = new GameClient(bootstrap, gameId, listener);
    client.eventLoop.start();
    return client;
}
```

Motivation: starting a thread inside a constructor publishes `this` before
construction completes (the classic *this-escape* — the thread could observe
final fields half-initialized). The factory starts the thread only after the
constructor has returned. This was a skill-review finding, fixed by
restructuring rather than by suppression.

## Sending commands

`send()` attaches a **completion callback** that logs any failure — the
producer future must never be silently dropped, because for a fire-and-forget
client that log line is the *only* signal a command was lost (review finding:
the original code discarded the future). After each send the producer is
`flush()`ed: at human-scale traffic, prompt delivery beats batching, and a
player's `roll` should be on the wire before their finger leaves the key.

Client-side validation comes for free: `client.join("bad name!")` throws
`IllegalArgumentException` from the `Command.JoinGame` compact constructor
*before* anything touches Kafka — the protocol's parse-don't-validate
design doing local duty.

Poison pills on the event stream are seek-past-skipped exactly as in the
server.

## Patterns applied

- **Ports and adapters** — `GameListener` is the port; the TUI (or any future
  UI) is an adapter; `client-core` knows none of them.
- **Read model / projection** (the query half of CQRS) — `GameView` projects
  the event stream into display shape.
- **Static factory over constructor** to prevent this-escape and give the
  operation a name (`connect`).
- **Virtual thread per long-lived I/O loop** — Java 21's intended use.
- **Immutable snapshot publication** — `volatile` reference to an immutable
  value; readers get consistency without locks.

## Anti-patterns avoided

- **this-escape from a constructor** (thread started before construction
  completed) — restructured via the factory.
- **Dropped async results** — the producer callback.
- **UI exceptions killing the data pipeline** — listener isolation.
- **Client-side offset state** — nothing to migrate, nothing to corrupt;
  restart = replay.
- **Sharing a consumer across threads** — confinement + `wakeup()`, same as
  the server.

## Decisions (from DECISIONS.md)

GameView fold duplication rationale; fresh-group replay-on-start; listener
threading contract; `connect()` factory; per-command flush; recentEvents cap.

## Issues (from ISSUES.md)

**#6** — first compile failed: `kafka-clients` uses slf4j internally but does
not expose it at compile scope; `slf4j-simple` declared explicitly (version
managed by the parent POM).
