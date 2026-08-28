# 03 — Engine: the Pure Rules Core

[← Protocol](02-protocol.md) · [Server →](04-server.md)

The `engine` module is the [functional
core](11-glossary.md#functional-core-imperative-shell) of the system: the
complete rules of the Game of the Goose, with **no Kafka imports at all** and
no input or output. It depends only on `protocol`. Everything in it is either a
pure function or a value that cannot change. That is what makes the rest of the
system easy to test, and what makes the end-to-end test give the same result
every time.

Three types and one [seam](11-glossary.md#seam):

| Type | Role |
|---|---|
| `Board` | Movement rules only: what one dice roll does to a token position |
| `GameState` | Immutable snapshot; `apply(Event)` folds one event into the next state |
| `GameEngine` | All the rules: `decide(state, command, dice)` returns the events a command causes |
| `DiceRoller` | An interface with one method — the seam where a test supplies a fixed sequence of rolls and the server supplies `SecureRandom` |

## The decide / apply split

The central idea of the engine is to keep the two halves of event sourcing
apart:

```
decide :  GameState × Command × DiceRoller  →  List<Event>     (may reject: empty list)
apply  :  GameState × Event                 →  GameState        (total: never rejects)
```

- **`decide` is where rules live.** It checks phase, turn ownership,
  player-count limits, duplicate names; it consults the board; it is the only
  code that creates events. It is pure: timestamps come from an injected
  `Clock`, dice from the injected `DiceRoller`, so the same inputs always
  produce the same events.
- **`apply` is where each event gets its meaning.** It says what an event does
  to the state, and it *trusts the log*: it does not check rules that span
  several events, because events can only come from `decide`, through a topic
  only the server writes. Checking them again in the fold would mean keeping
  every rule in two places, and it would make replaying old but valid logs
  break whenever a rule changed. The change to the deadlock rule, below, is
  exactly that situation.

One small detail carries a lot of weight: **`decide` folds its own output
through `apply` before working out whose turn is next**. Once it has built the
events for the roll, the moves and any trap, it runs
`after = state; for (e : events) after = after.apply(e);` and only then calls
`advanceTurn(after, …)`. So the turn logic always works on the state exactly as
the log will record it. The rules and the fold cannot disagree, because the
rules *use* the fold.

### Rejections produce no events

An invalid command returns an **empty list**: a roll out of turn, a join after
the game started, a name already taken, a start with only one player, a wrong
`gameId`. There is no `CommandRejected` event and no exception. The reasons:
the log stays a record of what actually *happened*, a rejection is not
something that ever needs replaying, and the server only has to write a line in
its own log. The cost is that a client gets no answer when its command is
refused. For a terminal UI that redraws on every accepted event this is
acceptable, and DECISIONS.md records it as the point to revisit if a future UI
needs to show refusals.

This is also why receiving a command twice is usually harmless: applying a
`JoinGame` or `StartGame` again, against the state it already produced, simply
returns nothing.

## Board: movement as data

`Board.resolve(from, roll)` returns the whole `List<Move>` that a roll causes.
Each `Move(from, to, reason)` is one step, and they are in order, so the caller
takes the final square from the last element. Movement is *described*, not
carried out: the board changes nothing and produces no events, and `GameEngine`
turns each step into one `PlayerMoved` event. This is why a single roll can
produce several `PlayerMoved` events, and why the terminal UI can describe every
hop separately.

The rules encoded: bridge 6→12, maze 42→39, death 58→1, geese
{5,9,14,18,23,27,32,36,41,45,50,54,59} repeat the movement, overshooting 63
bounces back by the excess, landing exactly on 63 wins. The inn (19), well
(31) and prison (52) deliberately do **not** appear in `resolve`: they don't
move the token — trapping is *turn* logic and lives in `GameEngine`. Each
layer owns one concern.

### Why goose chains always end

The obvious rule — "landing on a goose moves you forward by the roll again" —
never stops. From square 59 with a roll of 8 the piece goes past 63, bounces
back to 59, which is a goose, hops *forward* 8 again, and repeats for ever
(ISSUES.md #1, found while designing, before any code was written).

The rule that was implemented instead: a goose hop repeats the movement **in
the direction the piece is currently going**, and a bounce off 63 *reverses*
that direction. The `Cursor(pos, step)` record carries a step that can be
negative, and the bounce sets it to `-|step|`. With that rule it can be proved
that the chain always ends:

- while the piece moves forward, every hop increases its position, so the chain
  either leaves the goose squares or reaches the bounce — and the bounce can
  happen only once;
- after the bounce, every hop decreases the position, and a backward chain runs
  out of squares. On the standard board it stops by square 42, and an extra
  guard at 0 makes sure `resolve` can never return a square outside the board,
  even on a board laid out differently.

So every chain is finite: the position only ever moves one way, it changes
direction at most once, and the board has a limited number of squares, so the
chain must eventually land outside the goose squares.

### Validation at the boundary, again

`resolve` throws `IllegalArgumentException` if `from` is outside 0–63 or
`roll` is outside 1–12 (ISSUES.md #3, found in review). A roll of 0 on a goose
square would repeat a hop of length zero for ever. Before the fix, no caller in
`GameEngine` could actually reach that state — but only because the
`DiceRolled` constructor happened to check the value first. A public method has
to guard its own inputs: being safe only because of how today's callers happen
to behave is not the same as being safe.

## GameState: the immutable fold target

`GameState` is a record made of records: `gameId`, a `Phase`
(`LOBBY`, `RUNNING` or `FINISHED`), `players` in the order they joined (which
*is* the turn order), `positions`, `stuck` (which player is held on which
square), and two `Optional` fields, `currentPlayer` and `winner`. The
`Optional`s mean that "no current player" and "no winner yet" can be expressed
without using null at all. The compact constructor copies every collection with
`List.copyOf` and `Map.copyOf`, so a caller cannot change a state after handing
it over. The record cannot be modified at any depth, and "changing" it means
building the next one, through small private helper methods such as
`withPosition` and `withStuck`.

`apply` is a `switch` over the sealed `Event` types that covers every case and
has no `default`, so a new event type breaks the build here first. One event
changes nothing on purpose: `DiceRolled` only reports the roll — the
`PlayerMoved` events carry the actual change — so it returns the state
unchanged.

## GameEngine: turn flow and the traps

After a roll resolves, `decide` inspects the landing square:

1. **63** → `GameWon`; the game is over and there is no next turn.
2. **A trap** (inn, well or prison), *unless trapping would freeze the game* →
   `PlayerStuck`. If the trap is the well or the prison and someone is already
   held there, that player is released with `PlayerFreed`: these two traps
   **exchange prisoners**.
3. Otherwise the piece simply stays where it landed.

Then `advanceTurn` picks the next player, walking the rotation from the
current one:

- players held in the **well or prison** are skipped; they wait until another
  player takes their place;
- a player at the **inn** who is met on the first pass is released with
  `PlayerFreed`, but is still skipped this once, so the inn costs exactly one
  turn. This is done with two passes: the first releases inn players, the second
  finds the first player who can actually take a turn. In a two-player game the
  opponent therefore rolls twice in a row, which is what the board game rule
  says.

`GameStarted` carries `firstPlayer`, and that already says whose turn it is, so
no separate `TurnStarted` is written at the start of a game. Every fold treats
`GameStarted` as both "the phase changed" and "the first turn begins".

### The change to the deadlock rule

The traditional rules contain a real deadlock. With two players, if one is in
the well and the other then lands in the prison, each is waiting for the other
to free them, and neither ever can. During Step 4 this was written down as
faithful to the original game, if harsh, and accepted as unlikely. It then
**happened in the very first game played by hand** (ISSUES.md #7): alice fell
into the well, bob hopped from 45 to 52 into the prison, and the game stopped.

The fix, decided by the user and recorded in DECISIONS.md: **the last free
player is never trapped**. `freezesTheGame(state, lander, square)` cancels the
trap when all three of these are true: (a) the square is one that holds a
player until someone replaces them, (b) nobody is on it — if someone is, the
two players exchange places and one of them ends up free, and (c) every *other*
player is already held in the well or the prison. Players at the inn do not
count, because the turn order frees them by itself. When the trap is cancelled,
the player simply stays on the square, free.

Two details are worth noting as event-sourcing lessons:

- The new rule changes `decide` only. `apply` was not touched, so older logs —
  including the frozen game — still fold to exactly the same result.
  **You change the rules from now on; what is already in the log stays.**
- The branch in `advanceTurn` that handles "every player is trapped" by writing
  no turn at all can no longer be reached through `decide`. It is kept, with a
  comment, because logs written before the rule change still need it.

## Patterns applied

- **[Functional core](11-glossary.md#functional-core-imperative-shell)** — the
  decision function is pure; everything that would make it unpredictable, time
  and randomness, is passed in as `Clock` and `DiceRoller`.
- **State machine** — a `Phase` field plus switches that cover every case;
  moves that are not allowed are refused, not turned into exceptions.
- **Command to event** — the writing half of
  [CQRS](11-glossary.md#cqrs-and-the-read-model), with the fold as the reading
  half.
- **Make invalid states impossible to express** — `Optional` instead of null,
  records that cannot be changed at any depth, sealed interfaces.
- **Strategy through a functional interface** — `DiceRoller` is the only test
  seam the project needs, and there is no mocking framework anywhere in it.

## Anti-patterns avoided

- **Hidden calls to the outside world** — no `Instant.now()` and no
  `new Random()` inside the rules. The same inputs always give the same output
  because of how the code is built, not by luck.
- **The same business rule written twice** — the rules exist once, in `decide`,
  and the fold trusts them. The fold that *is* repeated, in client-core, is a
  different trade-off; see [chapter 5](05-client-core.md).
- **Loops that may never end** — the two possible infinite loops, the obvious
  goose rule and a step of zero, were removed: the first by proving the chain
  ends, the second by checking the arguments.
- **Using exceptions for normal control flow** — a refused command is an empty
  list, not a thrown exception. Exceptions are kept for programming mistakes,
  such as nulls and invalid arguments.

## Decisions (from DECISIONS.md)

- A refused command produces no events at all.
- A goose hop keeps the current direction, which is what makes chains end.
- The inn costs exactly one turn.
- The well and the prison exchange prisoners; since 2026-07-03 the last free
  player is never trapped.
- `GameStarted` already says whose turn is first.
- `decide` folds its own events before deciding who plays next.
- `apply` trusts the log and checks nothing across events.
- `Board.resolve` validates its own arguments.
- `Clock` and `DiceRoller` are passed in, never created inside the rules.

## Issues (from ISSUES.md)

**#1** — the obvious goose rule loops for ever; found while designing.
**#3** — `Board.resolve(x, 0)` could loop for ever; found in review.
**#7** — the well and prison deadlock happened in the first game played by
hand; the rule was changed and two tests now hold the new behaviour in place.
