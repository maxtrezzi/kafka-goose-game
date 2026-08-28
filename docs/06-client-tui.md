# 06 — client-tui: the Terminal UI

[← client-core](05-client-core.md) · [Infrastructure & build →](07-infrastructure-and-build.md)

`client-tui` is the smallest module, and it is the proof that the boundary
drawn by `client-core` works: a complete user interface in two classes,
depending on `client-core` and nothing else.

| Class | Role |
|---|---|
| `BoardRenderer` | **Pure**: `GameView` → ANSI string. No I/O of any kind. |
| `Main` | All the I/O: args, stdin loop, printing, process lifecycle. |

## BoardRenderer: rendering as a pure function

`render(GameView)` returns the whole screen as a single string: the 63-square
board, a status block with the players, their positions, whose turn it is, any
traps and the winner, then the list of recent events and a legend. Keeping this
function pure has one reason and one large benefit:

- **The reason:** rendering is arithmetic about positions, which is exactly the
  kind of code where off-by-one mistakes hide. That arithmetic is very easy to
  unit-test *as long as* no console is involved.
- **The benefit:** the tests remove the ANSI colour codes with a regular
  expression and then check plain text, such as `"55 "`, `"19I"`,
  `"12 ABCDEF"` and `"*** alice WINS! ***"`. Nine tests cover the snaking
  layout, the markers on special squares, where pieces are drawn, the status
  block, the event lines, and the handling of the ANSI codes themselves —
  without driving a terminal at all.

### The snaking board

The traditional board winds back and forth. The grid is 7 rows by 9 columns,
with square 1 at the bottom left. Counting rows from the bottom starting at
zero, even rows run left to right and odd rows right to left, so square 63 ends
up in the top-left area:

```
55  56  57  58X 59* 60  61  62  63
54* 53  52P 51  50* 49  48  47  46
37  38  39  40  41* 42< 43  44  45*
36* 35  34  33  32* 31W 30  29  28
19I 20  21  22  23* 24  25  26  27*
18* 17  16  15  14* 13  12  11  10
 1   2   3   4   5*  6>  7   8   9
```

Each cell is 8 columns wide: two for the number, one for the marker, then the
players' initials and padding. The markers show what the square does — `*`
goose, `>` bridge, `<` maze, `X` death, `I` inn, `W` well, `P` prison — and are
coloured by kind: traps red, jumps cyan, geese green. Each player gets one ANSI
colour, chosen by the order they joined, and their piece is the first letter of
their name in bold capitals.

One edge case found in review is worth describing. Six players on the same
square produce 9 visible characters inside a cell only 8 wide. The original
padding calculation then called `" ".repeat(-1)`, which throws
`IllegalArgumentException`: the renderer crashed on a game state that was
perfectly *valid*. The fix is `Math.max(0, CELL_WIDTH - visible)`, with a
comment explaining the choice — lose one column of alignment rather than throw
— and a test that keeps it that way (`sixPlayersOnOneSquareStillRender`).
Rendering code must produce a result for every state it is willing to
accept.

### Event narration

`eventLine(Event)` turns each event into one sentence a person can read, such
as "alice rolled 3+4 = 7" or "bob moved 6 -> 12 (bridge)". It is another switch
that must cover every case, so a new event type breaks this build as well. All
the formatting fixes `Locale.ROOT`, both for the `%2d` cells and for the
lower-cased `MoveReason`. The board output is read by the tests as text, and
formatting that changes with the machine's language settings is a classic
hidden bug — in Turkish, for example, the lower-case form of "I" is not "i",
so a name or a keyword can quietly stop matching.

## Main: where all the side effects live

`Main` owns everything impure, and nothing else:

- **Arguments** `[bootstrap [gameId [player]]]`, with the defaults
  `localhost:9092 game-1 <os-user>`. The operating-system user name is cleaned
  up to match the protocol's `[A-Za-z0-9_-]{1,20}` rule: remove the characters
  that are not allowed, cut it to length, and fall back to `"player"` if
  nothing usable is left. The default has to be valid by construction, or the
  very first command the program suggests would throw.
- **The input loop** runs on the main thread and accepts `join [name]`,
  `start`, `roll`, `board`, `help` and `quit`. The reader is fixed to UTF-8.
  If an `IllegalArgumentException` arrives, because someone typed an invalid
  name, it prints `invalid: …` and carries on: a mistake by the user deserves a
  new prompt, not a stack trace.
- **What triggers a redraw**: the `GameListener` callback, running on the
  client's event-loop thread, clears the screen and prints it again on every
  update. Both threads write to `System.out`, so now and then the prompt
  appears in the middle of the board. That is the accepted price of a terminal
  UI built on plain standard output, and it is written down in the class
  javadoc instead of being "fixed" with a terminal library the project does not
  need.
- **Identity is an agreement, not a session**: `join bob` changes which player
  the following `start` and `roll` commands act as. It is kept in an
  `AtomicReference<String>` because the listener thread reads it while
  rendering. There is no authentication anywhere. That is a deliberate limit:
  proving who a player is has nothing to do with what this project teaches, and
  the README lists it as future work together with SASL.

Two environmental details with reasons:

- `org.slf4j.simpleLogger.defaultLogLevel=warn` is the first thing `main()`
  does. `kafka-clients` writes a great deal at INFO level, and those lines
  print straight over the board.
- ANSI escape characters appear in the source **only as `\u001B` unicode
  escapes**, never as raw ESC bytes. For a while the generated sources
  contained invisible 0x1B control characters. They survive copy and paste,
  they make diffs hard to read in ways that are easy to miss, and they are a
  problem for anyone maintaining the code. They were removed with `sed`, and
  the rule was written down in DECISIONS.md.

## Rolling blindly: an unplanned benefit

Because the server refuses an out-of-turn `RollDice` with *no events and no
error*, a client can be driven by the simplest possible script — send `roll`
once a second — and the game still plays correctly. This is what made the
automated test games possible: two clients fed from a pipe, playing complete
games through the real cluster ([chapter 8](08-testing.md)). Nobody designed
this; it followed from the decision that refused commands produce no events. It
proved its worth almost immediately, by causing the well and prison deadlock in
the first game played for real.

## Patterns applied

- **[Functional core, imperative
  shell](11-glossary.md#functional-core-imperative-shell), on a small scale** —
  the same split as engine and server, one layer higher: `BoardRenderer` is
  pure, `Main` is not.
- **Humble object** — `Main` is deliberately too thin to be worth testing, and
  all the logic that could actually be wrong sits in the renderer, which is
  easy to test.
- **Rendering that must cover every case** — the sealed interface forces the UI
  to keep pace with the protocol, and the compiler checks it.

## Anti-patterns avoided

- **Logic in the input/output layer** — `Main` makes no decisions about the
  game or the layout.
- **Rendering code that fails on some valid inputs** — the negative-repeat
  crash was removed and a test keeps it away.
- **Output that changes with the machine's language settings** — every format
  call fixes `Locale.ROOT`.
- **Invisible control characters in source files** — the `\u001B` rule.
- **Reaching for a framework by reflex** — no curses or JLine dependency for a
  problem that plain standard output solves; the occasional interleaved prompt
  is documented instead.

## Decisions (from DECISIONS.md)

- The renderer is a pure function.
- The board snakes, with the row directions described above.
- ANSI escapes are written only as `\u001B` in source.
- Kafka's log level is lowered before anything is printed.
- Rolling out of turn is safe, which makes scripted clients possible.
- `join` changes which player the next commands act as.

## Issues (from ISSUES.md)

**#7**, the deadlock, was *found* through the test games played with this
module and fixed in the engine. The crash on an overfull cell, and a test that
claimed square 55 was a goose square when it is not, were both found during
review and fixed before the commit.
