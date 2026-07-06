# 06 — client-tui: the Terminal UI

[← client-core](05-client-core.md) · [Infrastructure & build →](07-infrastructure-and-build.md)

`client-tui` is the smallest module and the proof that `client-core`'s seam
works: a complete UI in two classes, depending on `client-core` only.

| Class | Role |
|---|---|
| `BoardRenderer` | **Pure**: `GameView` → ANSI string. No I/O of any kind. |
| `Main` | All the I/O: args, stdin loop, printing, process lifecycle. |

## BoardRenderer: rendering as a pure function

`render(GameView)` returns the full screen as one string: the 63-square
board, a status block (players, positions, whose turn, traps, winner banner),
the recent-event log, and a legend. Keeping it pure has one motivation and
one big payoff:

- **Motivation:** rendering logic is layout arithmetic — exactly the kind of
  code that harbors off-by-one bugs — and layout arithmetic is trivially
  unit-testable *if* no console is involved.
- **Payoff:** the tests strip ANSI codes with a regex and assert on plain
  text (`"55 "`, `"19I"`, `"12 ABCDEF"`, `"*** alice WINS! ***"`). Nine tests
  cover the serpentine layout, special-square markers, token placement,
  status, event lines, and the ANSI hygiene itself — with zero terminal
  automation.

### The serpentine board

The classic board snakes. The grid is 7 rows × 9 columns, square 1 at the
bottom-left, even rows (0-based, from the bottom) running left→right and odd
rows right→left, so 63 ends in the top-left region:

```
55  56  57  58X 59* 60  61  62  63
54* 53  52P 51  50* 49  48  47  46
37  38  39  40  41* 42< 43  44  45*
36* 35  34  33  32* 31W 30  29  28
19I 20  21  22  23* 24  25  26  27*
18* 17  16  15  14* 13  12  11  10
 1   2   3   4   5*  6>  7   8   9
```

Each cell is 8 columns: number (2) + marker (1) + player initials + padding.
Markers encode the special squares (`*` goose, `>` bridge, `<` maze, `X`
death, `I` inn, `W` well, `P` prison), colored by class (traps red, jumps
cyan, geese green); players get one ANSI color each by join position, their
token being the bold uppercase initial of their name.

One found-in-review edge case is instructive: six players on one square emit
9 visible characters into the 8-wide cell, and the original padding
computation called `" ".repeat(-1)` → `IllegalArgumentException` — a renderer
crash on a *valid* game state. The fix is `Math.max(0, CELL_WIDTH - visible)`
with a comment explaining the choice (give up one alignment column rather
than throw) and a pinning test (`sixPlayersOnOneSquareStillRender`). Render
code must be total over the state space it accepts.

### Event narration

`eventLine(Event)` renders each event as one human sentence ("alice rolled
3+4 = 7", "bob moved 6 -> 12 (bridge)") — an exhaustive switch over the
sealed hierarchy, so a new event type breaks this compile too. String
formatting pins `Locale.ROOT` (both the `%2d` cells and the lowercased
`MoveReason`): board output is machine-readable by the tests, and locale-
dependent formatting in machine-read output is a classic latent bug (the
Turkish-i problem).

## Main: the imperative rim

`Main` owns everything impure, and nothing else:

- **Args** `[bootstrap [gameId [player]]]` with defaults
  `localhost:9092 game-1 <os-user>`; the OS username is sanitized to the
  protocol's `[A-Za-z0-9_-]{1,20}` shape (strip + truncate, fallback
  `"player"`) — the default must be *valid by construction* or the first
  suggested command would throw.
- **Stdin loop** on the main thread: `join [name]` / `start` / `roll` /
  `board` / `help` / `quit`. UTF-8 is pinned on the reader. A caught
  `IllegalArgumentException` (an invalid name typed at the prompt) prints
  `invalid: …` and continues — user errors are prompts, not stack traces.
- **Rendering trigger**: the `GameListener` callback (on the client's event
  loop thread) clears the screen and reprints on every view update. Both
  threads write to `System.out`; an occasionally interleaved prompt is the
  accepted cost of a plain-stdio TUI, documented in the class javadoc rather
  than "fixed" with a terminal library the project doesn't need.
- **Identity is a convention, not a session**: `join bob` switches which
  player subsequent `start`/`roll` act as (an `AtomicReference<String>`,
  since the listener thread reads it for rendering context). There is no
  authentication anywhere — a deliberate scope line: identity/authn is
  orthogonal to what the project teaches and is listed as future work with
  SASL in the README.

Two environmental details with reasons:

- `org.slf4j.simpleLogger.defaultLogLevel=warn` is set first thing in
  `main()`: kafka-clients logs INFO chattily, and INFO chatter scribbles over
  an ANSI-painted board.
- ANSI escapes appear in source **only as `\u001B` unicode escapes**, never
  as raw ESC bytes. Mid-project, generated sources briefly contained
  invisible 0x1B control characters — they survive copy-paste, break diffs
  subtly, and are a maintenance hazard; they were purged with `sed` and the
  convention was recorded in DECISIONS.md.

## Blind rolls: an accidental design win

Because the server rejects out-of-turn `RollDice` with *no events and no
error*, a client can be driven by the dumbest possible script — pipe `roll`
every second — and the game still plays out correctly. This is what made the
automated smoke games possible (two piped clients playing full games through
the real cluster, [chapter 8](08-testing.md)), and it fell out of the
"rejections produce no events" decision rather than being designed for. It
also promptly paid for itself by triggering the well+prison deadlock in the
first live game.

## Patterns applied

- **Functional core / imperative shell, in miniature** — the same split as
  engine/server, one layer up: `BoardRenderer` pure, `Main` impure.
- **Humble Object** — `Main` is deliberately too thin to need tests; all the
  logic that *could* be wrong lives in the testable renderer.
- **Exhaustive rendering** — the sealed hierarchy forces the UI to keep up
  with the protocol at compile time.

## Anti-patterns avoided

- **Logic in the I/O layer** — no game or layout decisions in `Main`.
- **Partial functions in rendering** — the negative-repeat crash class,
  eliminated and pinned.
- **Locale-dependent machine output** — `Locale.ROOT` pinned.
- **Invisible control characters in source** — the `\u001B` convention.
- **Framework reflex** — no curses/JLine dependency for a problem plain
  stdio solves; the interleaved-prompt trade-off is documented instead.

## Decisions (from DECISIONS.md)

Pure renderer; serpentine convention; `\u001B` escapes; kafka log level;
blind-roll safety; `join` switches identity.

## Issues (from ISSUES.md)

**#7** was *found* through this module's smoke games (the deadlock — fixed in
the engine). The overfull-cell crash and a test asserting a goose on a
non-goose square (55) were review-cycle findings fixed before commit.
