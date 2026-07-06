# 03 — Engine: the Pure Rules Core

[← Protocol](02-protocol.md) · [Server →](04-server.md)

The `engine` module is the functional core of the system: the complete rules
of the Game of the Goose with **zero Kafka imports** and zero I/O. It depends
only on `protocol`. Everything in it is a pure function or an immutable value,
which is what makes the rest of the system testable and the E2E test
deterministic.

Three types, one seam:

| Type | Role |
|---|---|
| `Board` | Movement rules only: what one dice roll does to a token position |
| `GameState` | Immutable snapshot; `apply(Event)` folds one event into the next state |
| `GameEngine` | The rule book: `decide(state, command, dice)` → the events the command causes |
| `DiceRoller` | One-method interface — the seam through which tests inject scripts and the server injects `SecureRandom` |

## The decide / apply split

The engine's central design is the separation of the two halves of event
sourcing:

```
decide :  GameState × Command × DiceRoller  →  List<Event>     (may reject: empty list)
apply  :  GameState × Event                 →  GameState        (total: never rejects)
```

- **`decide` is where rules live.** It checks phase, turn ownership,
  player-count limits, duplicate names; it consults the board; it is the only
  code that creates events. It is pure: timestamps come from an injected
  `Clock`, dice from the injected `DiceRoller`, so the same inputs always
  produce the same events.
- **`apply` is where meaning lives.** It gives each event its effect on state
  and *trusts the log*: it does not re-check cross-event invariants, because
  events only ever come from `decide` via the server-authoritative topic.
  Re-validating in the fold would mean maintaining every rule twice — the
  duplicated-business-logic trap — and would make replay of historically
  valid logs fragile as rules evolve (see the deadlock amendment below for
  exactly that scenario).

A subtle but load-bearing detail: **`decide` folds its own output through
`apply` before computing whose turn is next**. After building the
roll/move/trap events it does `after = state; for (e : events) after =
after.apply(e);` and only then runs `advanceTurn(after, …)`. Turn logic
therefore always operates on the state *as the log will record it* — decision
logic and fold logic cannot drift apart, because the decision logic *uses*
the fold.

### Rejections produce no events

An invalid command (out-of-turn roll, join after start, duplicate name, start
with one player, wrong gameId…) returns an **empty list**. No
`CommandRejected` event, no exception. Motivations: the log stays a record of
things that *happened*, rejection needs no replay semantics, and the server
just logs it. The trade-off — clients get no feedback for rejected commands —
is acceptable for a TUI that redraws on every accepted event, and is recorded
in DECISIONS.md as the place to revisit if a future UI needs rejection
feedback.

This is also what makes at-least-once redelivery mostly harmless: replaying
an already-applied `JoinGame` or `StartGame` against the updated state simply
produces nothing.

## Board: movement as data

`Board.resolve(from, roll)` returns the full `List<Move>` a roll triggers —
each `Move(from, to, reason)` is one segment, in order — and the caller reads
the final resting square from the last element. Movement is *described*, not
performed: the board mutates nothing and emits no events; `GameEngine`
translates the segments 1:1 into `PlayerMoved` events. This is why one roll
can produce several `PlayerMoved`s on the wire and why the TUI can animate or
narrate every hop.

The rules encoded: bridge 6→12, maze 42→39, death 58→1, geese
{5,9,14,18,23,27,32,36,41,45,50,54,59} repeat the movement, overshooting 63
bounces back by the excess, landing exactly on 63 wins. The inn (19), well
(31) and prison (52) deliberately do **not** appear in `resolve`: they don't
move the token — trapping is *turn* logic and lives in `GameEngine`. Each
layer owns one concern.

### The goose-chain termination argument

The naive rule "landing on a goose moves you forward by the roll again" does
not terminate: from 59 with a roll of 8, the token overshoots, bounces back
to 59 — a goose — hops *forward* 8 again, forever (ISSUES.md #1, caught at
design time before a line of code).

The implemented rule: a goose hop repeats the movement **in the token's
current direction**, and a bounce off 63 *reverses* the direction (the
`Cursor(pos, step)` record carries a signed step; reflection sets it to
`-|step|`). Termination is then provable:

- while moving forward, every hop strictly increases the position, so the
  chain either exits the goose set or reaches the reflection — at most once;
- after reflecting, every hop strictly decreases the position, and backward
  chains bottom out (on the classic layout they stop by square 42; a
  belt-and-braces clamp at 0 guarantees `resolve` can never emit an invalid
  square even on a hypothetical board).

Every chain is finite because a strictly monotonic sequence over a finite
board with at most one direction flip must leave the goose set.

### Validation at the boundary, again

`resolve` rejects `from` outside 0–63 and `roll` outside 1–12 with
`IllegalArgumentException` (ISSUES.md #3, found by review): a roll of 0 on a
goose square would repeat a zero-length hop forever. Before the fix this was
unreachable through `GameEngine` — but only by accident of the `DiceRolled`
constructor validating first. Public methods get their own guards; safety by
call-site coincidence is not safety.

## GameState: the immutable fold target

`GameState` is a record of records: `gameId`, `Phase`
(`LOBBY`/`RUNNING`/`FINISHED`), `players` in join order (which *is* the turn
order), `positions`, `stuck` (player → trapping square), and two `Optional`s
(`currentPlayer`, `winner`) that make "no current player" and "no winner yet"
unrepresentable as nulls. The compact constructor defensively copies every
collection with `List.copyOf`/`Map.copyOf`, so no caller can mutate a state
after the fact — the record is deeply immutable, and "modification" means
building the next state (small private withers: `withPosition`, `withStuck`,
…).

`apply` is an exhaustive pattern switch over the sealed `Event` hierarchy with
no `default`: an added event type fails compilation here first. Two events are
deliberate no-ops in terms of structure: `DiceRolled` (informational — the
`PlayerMoved`s carry the change) folds to `this`.

## GameEngine: turn flow and the traps

After a roll resolves, `decide` inspects the landing square:

1. **63** → `GameWon`, game over, no next turn.
2. **A trap** (inn/well/prison), *unless it would freeze the game* → 
   `PlayerStuck`; if the trap is the well or prison and it already holds
   someone, that occupant is `PlayerFreed` — the traps **swap occupants**.
3. Otherwise the token just rests.

Then `advanceTurn` picks the next player, walking the rotation from the
current one:

- players held in the **well/prison** are skipped (they wait for relief);
- a player at the **inn** encountered on the first pass is freed
  (`PlayerFreed`) but skipped once — the inn costs exactly one missed turn,
  implemented as a two-pass scan (pass 0 frees inn players, pass 1 finds the
  first eligible player). In a 2-player game the opponent naturally rolls
  twice in a row — faithful to the tabletop rule.

`GameStarted` carries `firstPlayer` and *implies* the first turn — no
redundant `TurnStarted` at game start, so folds treat `GameStarted` as both
"phase change" and "first turn assignment".

### The deadlock amendment

The classic rules contain a genuine deadlock: with two players, one in the
well and the other landing in the prison, both are waiting for relief that
can never come. This was documented during Step 4 as "faithful, if merciless"
and accepted as improbable — and then it **happened in the very first live
smoke game** (ISSUES.md #7): alice fell into the well, bob goose-hopped
45→52 into the prison, game frozen.

The fix (a user decision recorded in DECISIONS.md): **the last free player
never gets trapped**. `freezesTheGame(state, lander, square)` waives the trap
when (a) the square holds-until-replaced, (b) it is unoccupied (an occupied
trap swaps, so someone stays free), and (c) every *other* player is already
held in a well/prison — inn players don't count, because the rotation frees
them by itself. When waived, the lander simply rests on the square unharmed.

Two details are worth noting as event-sourcing lessons:

- The rule changes `decide` only. `apply` is untouched — old logs (including
  the frozen game) still fold identically. **You fix rules going forward; the
  log is immutable.**
- The all-players-trapped branch in `advanceTurn` (emit no turn at all) is now
  unreachable through `decide`, but is kept and commented as the defensive
  path for replaying logs that predate the rule.

## Patterns applied

- **Functional core** — pure decision function; effects (time, randomness)
  injected as `Clock` and `DiceRoller`.
- **State machine** — `Phase` + exhaustive switches; illegal transitions are
  rejections, not exceptions.
- **Command → Event** (the write half of CQRS/ES) with the fold as the read
  half.
- **Make illegal states unrepresentable** — `Optional` over null,
  deep-immutable records, sealed hierarchies.
- **Strategy via functional interface** — `DiceRoller` is the whole test seam;
  no mocking framework exists in the project.

## Anti-patterns avoided

- **Hidden global effects** — no `Instant.now()`, no `new Random()` inside
  logic; determinism is structural, not accidental.
- **Duplicated business rules** — rules exist once in `decide`; the fold
  trusts them (the *deliberate* fold duplication in client-core is a different
  trade-off, see [chapter 5](05-client-core.md)).
- **Unbounded loops on data-driven rules** — both potential infinite loops
  (naive goose, zero step) were eliminated by proof and boundary validation
  respectively.
- **Exception-driven control flow** — rejection is an empty list, not a thrown
  exception; exceptions are reserved for programming errors (nulls, invalid
  arguments).

## Decisions (from DECISIONS.md)

No-event rejections; direction-preserving goose hops with the termination
argument; inn costs one rotation; well/prison swap occupants + the 2026-07-03
last-free-player amendment; `GameStarted` implies the first turn; decide folds
its own events; apply trusts the log; `Board.resolve` boundary validation;
injected `Clock`/`DiceRoller`.

## Issues (from ISSUES.md)

**#1** — naive goose rule loops forever (design-time). **#3** —
`Board.resolve(x, 0)` latent infinite loop (review). **#7** — the well+prison
deadlock fired in the first live game; rule amendment + two pinning tests.
