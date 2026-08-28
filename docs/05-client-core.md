# 05 — client-core: the UI-Agnostic Client Library

[← Server](04-server.md) · [client-tui →](06-client-tui.md)

`client-core` is the layer that makes a new user interface cheap to write. It
holds everything a client needs in order to take part in a game — sending
commands, following events, folding them into state that can be displayed —
with **no console input or output and no assumptions about the UI**. A web or
desktop interface would depend on this module in exactly the same way the
terminal UI does, by implementing one interface.

Three types:

| Type | Role |
|---|---|
| `GameClient` | Connection: command producer + event-loop consumer on a virtual thread |
| `GameView` | Immutable displayable state: the client-side fold, plus a recent-events log |
| `GameListener` | The UI contract: `onEvent(Event)`, `onViewUpdated(GameView)` |

Dependency: **`protocol` only.** Not `engine` — deliberately.

## The deliberately duplicated fold

`GameView.apply(Event)` writes the event [fold](11-glossary.md#fold) again
instead of reusing the engine's `GameState`. This is the decision in the
codebase that is easiest to argue with, so the reasoning is set out in full.

**Why write it twice?**

- The implementation plan fixes which module may depend on which: a client must
  have *no game rules* available to it. `GameState` lives in `engine`, and
  depending on `engine` just to reuse the fold would bring `Board` and
  `GameEngine` along with it. Every future UI would then be able to decide
  game rules, which is exactly what clients must never do.
- The two folds are there for different reasons and had already started to
  differ. `GameView` also keeps `recentEvents`, a short list for display, and
  will grow more conveniences meant for the screen, which have no place in the
  engine.
- **The message format, not a shared class, is the contract.** Server and
  client agree because they use the same sealed `Event` types, and both folds
  are switches that must cover every case. A new event type breaks *both*
  builds until both folds handle it, and the shared protocol tests check the
  rest.

**What it costs:** the same fold logic exists twice, about 100 lines, and if
the two ever behaved differently, a client would draw a board the server does
not agree with. The cost was accepted knowingly and recorded in DECISIONS.md.
This is the judgement call about [coincidental
duplication](11-glossary.md#coincidental-duplication): the two folds are
*meant* to grow apart, and that is exactly when merging them into one shared
piece of code is the wrong move.

`GameView` is built with the same discipline as `GameState`: a record, unable
to be changed at any depth thanks to `List.copyOf` and `Map.copyOf` in the
compact constructor, `Optional` instead of null, a switch that covers every
case, and small private helpers to build the next value. `recentEvents` holds
at most 10 entries (`RECENT_EVENTS_LIMIT`) and drops the oldest first. It is a
list for the screen, not a history: the history is the topic.

## Replay-on-start: the client keeps nothing

Every `GameClient` start creates a consumer with a **fresh group id**
(`goose-client-<uuid>`), `auto.offset.reset=earliest`, auto-commit off,
and offsets that are never committed. So a client started, or restarted, in
the middle of a game rebuilds its whole view by reading `game.events` from the
beginning. Killing a client and watching the board come back is not a feature
added on top: it is simply what a [read
model](11-glossary.md#cqrs-and-the-read-model) does. The group id exists only
because `subscribe()` demands one, and nothing is ever stored under it.

Events are filtered by `gameId` in the client, because all games share the
topic, and [tombstones](11-glossary.md#tombstone) are skipped.

## Threading model

- **The event loop owns the consumer and the fold.** It runs on a [virtual
  thread](11-glossary.md#virtual-thread) (`Thread.ofVirtual()`). The poll loop
  spends nearly all its time waiting for the network, which is exactly what
  virtual threads are for; giving each client a full operating-system thread
  would waste one. The `view` field is `volatile` so that any thread can read
  the latest value through `view()`. That is safe because `GameView` cannot
  change: the only thing that has to work is making the new value visible.
- **Listener methods run on the event-loop thread, in log order.** The rule is
  written on `GameListener`: a UI that needs its own thread must move the work
  there itself. An exception thrown by a listener is caught, logged and passed
  over, because a drawing bug in a UI must not stop the flow of events
  (`notifyListener`).
- **Commands may be sent from any thread, and so may `close()`.**
  `KafkaProducer` is safe to use from several threads — the one Kafka client
  that is. Shutdown works like the server's: signal, do not touch. A `volatile
  running` flag, then `consumer.wakeup()` through an `AtomicReference`, then
  `eventLoop.join(CLOSE_TIMEOUT)` using Java 21's `join(Duration)`, with a
  warning in the log if the loop has not stopped after 10 seconds. The producer
  is closed by `close()` because that thread owns it: it was created in the
  constructor and only ever used through a thread-safe API.

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

The reason: starting a thread inside a constructor hands out `this` before the
object is fully built. This is the well-known *this-escape* problem — the new
thread can see final fields that are not set yet. The factory method starts the
thread only after the constructor has finished. This came out of a code review
and was fixed by changing the structure, not by silencing the warning.

## Sending commands

`send()` adds a **callback** that logs any failure. The result of a send must
never be thrown away in silence, because a client that does not wait for an
answer has nothing else to tell it that a command was lost — that log line is
the only signal. (A review found that the original code dropped the result.)
After each send the producer is flushed with `flush()`. At the speed a person
plays, sending immediately is better than collecting messages into batches: a
player's `roll` should be on its way before they lift their finger off the key.

Checking the input costs nothing extra: `client.join("bad name!")` throws
`IllegalArgumentException` from the `Command.JoinGame` compact constructor
*before* Kafka is involved at all. That is the protocol's [parse, don't
validate](11-glossary.md#parse-dont-validate) design working locally as well.

Events that cannot be read are skipped by stepping past them, exactly as in
the server.

## Patterns applied

- **[Ports and adapters](11-glossary.md#ports-and-adapters-hexagonal-architecture)**
  — `GameListener` is the port, the terminal UI (or any future UI) is an
  adapter, and `client-core` knows about none of them.
- **[Read model](11-glossary.md#cqrs-and-the-read-model)** — `GameView` turns
  the stream of events into a shape meant for display.
- **A static factory method instead of a public constructor**, to avoid the
  this-escape problem and to give the operation a name: `connect`.
- **One virtual thread per long-running I/O loop** — what Java 21 intends
  virtual threads for.
- **Publishing an unchangeable value** — a `volatile` reference to an immutable
  object, so readers get a consistent view without any locking.

## Anti-patterns avoided

- **Letting `this` escape from a constructor**, by starting a thread before the
  object was finished — solved with the factory method.
- **Throwing away the result of an asynchronous call** — solved with the
  producer callback.
- **A UI exception stopping the flow of data** — the listener is isolated.
- **Keeping offsets on the client** — there is nothing to move and nothing to
  corrupt: a restart simply reads everything again.
- **Using one consumer from several threads** — it stays in one thread and is
  stopped with `wakeup()`, as in the server.

## Decisions (from DECISIONS.md)

- `GameView` repeats the fold on purpose, for the reasons given above.
- Every client run uses a new group and reads the whole log from the start.
- Listener methods run on the event-loop thread, and this is documented.
- `connect()` is a factory method, so the constructor starts no thread.
- The producer is flushed after every command.
- `recentEvents` never holds more than ten events.

## Issues (from ISSUES.md)

**#6** — the first build failed. `kafka-clients` uses slf4j internally but
does not make it available at compile time, so `slf4j-simple` is now declared
explicitly, with its version managed by the parent POM.
