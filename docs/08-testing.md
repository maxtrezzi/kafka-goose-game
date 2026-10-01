# 08 — Testing Strategy

[← Infrastructure & build](07-infrastructure-and-build.md) · [Patterns catalog →](09-patterns-and-antipatterns.md)

The tests follow the shape of the architecture: the purer a layer is, the more
tests it gets and the cheaper those tests are. There are 126 tests in total —
123 unit tests and 3 integration tests — and no mocking framework anywhere. Every place a test needs to substitute something
— `DiceRoller`, `Clock`, `GameListener` — is a small interface, so the test can
implement it in one line. See [seam](11-glossary.md#seam).

| Module | Tests | Kind | What they prove |
|---|---|---|---|
| protocol | 49 | unit | Round-trip of every message type; rejection of malformed / unknown-type / oversized / non-string-timestamp payloads; unknown-*field* tolerance; tombstone nulls; validation rules incl. duplicates |
| engine | 47 | unit | Every board rule; every rejection; trap/free/turn flow, including the trap edge cases found by mutation testing; the deadlock amendment; full scripted games with fixed dice |
| server | 1 | E2E (Testcontainers) | The entire stack against a real broker, and the client's fold agreeing with the server's after every event |
| client-core | 10 + 2 | unit + integration (Testcontainers) | The view fold over scripted event sequences; `GameClient` against a real broker: replay on start, the filter by game, unreadable records skipped, commands keyed by game |
| client-tui | 17 | unit | Board layout and row width, markers and their colours, player colours, pieces, status block, winner line, the sentence written for each event, correct handling of the ANSI codes, the cell that holds too many players, and the hint shown before a likely rejection |

## Why most of the tests are unit tests

This is the usual [test pyramid](11-glossary.md#test-pyramid) shape, and here
is why it falls out that way:

- **Protocol and engine carry most of the weight** because they are pure. Their
  tests take milliseconds, need nothing running, and can go through every rule
  one by one. A full scripted game in `GameEngineTest` folds every event
  through `GameState` exactly as the server would, so most of what people would
  call "integration behaviour" is already proved below the integration layer.
- **The server gets one test, but that test is the whole system.** What the
  server adds is the Kafka setup, replay, the order of confirming writes before
  committing offsets, and shutdown. None of it means anything except against a
  real broker. Unit-testing it would mean replacing `KafkaConsumer` with a
  fake, and that tests the fake, not the agreement with Kafka.
- **The client gets two integration tests, and no server.** `GameClientIT`
  writes the events itself, standing in for the server, and reads the
  commands the client sends. That keeps the test about what `client-core`
  promises — replay, filtering, skipping bad records, sending — and not about
  the game rules, which are tested below.
- **`mvn test` never needs Docker.** The integration tests are `*IT` classes
  run by failsafe during `verify` ([chapter
  7](07-infrastructure-and-build.md)), so the logic gives feedback in seconds
  and the full proof is one command away.

## The deterministic E2E

`GooseServerIT` starts a temporary `apache/kafka:4.3.1` container with
[Testcontainers](11-glossary.md#testcontainers) and creates the topics through
the Admin API. It then runs the real `GooseServer.run()` on a [virtual
thread](11-glossary.md#virtual-thread) and plays a two-player game *entirely
from the outside*: it writes commands and reads events over the network, and
never touches anything inside the server.

Getting the same result every time is designed in, not hoped for:

- **The dice are scripted**, supplied through the same `DiceRoller` seam that
  production uses. It is a queue read with `queue::remove`, which throws when it
  runs out, so if the script and the logic ever disagree the test fails clearly
  instead of continuing with meaningless rolls. The script is chosen so that the
  game includes a goose hop, the bridge, and an exact landing on 63.
- **The test reacts to events; it does not wait for a fixed time.** For each
  `GameStarted` or `TurnStarted` event it sends exactly one `RollDice` for the
  player named in that event, until `GameWon` arrives. There are no sleeps and
  no offset arithmetic: the stream of events is what keeps the two sides in
  step. There is a 90-second deadline, but only to stop a hung test, and its
  failure message prints the events collected so far.

The checks cover four things:

1. **The opening sequence.** After the joins and the start, whole events are
   compared for equality.
2. **The landmarks the script was built around**: bob's jump over the bridge,
   alice's goose hop, and alice's exact win.
3. **The point of event sourcing itself.** Folding the observed history gives
   the expected final state: `{alice=63, bob=21}`, phase FINISHED, winner
   alice.
4. **The two folds agree.** The same history is folded with the engine's
   `GameState` and with the client's `GameView`, and after *every* event the
   phase, players, positions, trapped players, current player and winner must
   be equal. The two folds are written twice on purpose
   ([chapter 5](05-client-core.md)); the compiler makes sure both handle every
   event type, and this check makes sure they handle it the same way. For it,
   `server` has `client-core` as a *test* dependency only.

Last, the test checks a clean shutdown. `close()` returns, and the server
thread really does stop.

## What the automated tests missed and running the real thing found

The most useful testing lesson in this project is issue #7. There were 39
engine tests and a passing end-to-end test, and then the **first game played
against the real cluster** froze the system. Two terminal clients, fed from a
pipe and rolling once a second — safe, because a roll out of turn is refused
and does nothing — reached the well and prison deadlock within a single game.
Unit tests check the rules you thought of. Letting the system run freely
explores the states you never thought to write a rule about. The fix arrived
with two new unit tests, `lastFreePlayerIsNeverTrapped` and
`trapStillAppliesWhileAnotherPlayerIsFree`: playing for real finds the problem,
unit tests then hold the answer in place.

The same setup of blindly rolling clients became the **final check** at the end
of the project (implementation plan, Step 8): a clean `mvn verify`, a fresh
cluster, then complete games through the real compose cluster. One of those
games was played from the first move to the win with a broker stopped the whole
time. Afterwards the copies were confirmed to be in sync again, and a newly
started client rebuilt the finished board from the log alone.

That last run taught its own lesson (ISSUES.md #8). The first version of the
broker-stopping script waited for `GameStarted`, slept, and then stopped a
broker "in the middle of the game". But these games finish in about 90 seconds,
so the game had already been *won* before the broker was stopped, and the
observation that "moves kept flowing" was true only because there was nothing
left to do. The fix was to make the fault a **starting condition** instead of an
interruption: stop the broker first, then play the whole game. A fault injected
on a timer, against a workload that finishes quickly, can end up testing
nothing at all — without saying so.

## Mutation testing: are the tests checking anything?

Line coverage says which code the tests *ran*; it does not say whether they
would notice if that code were wrong. [Mutation
testing](11-glossary.md#mutation-testing) answers that second question. PIT
makes many small changes to the compiled code — a `<` becomes `<=`, a condition
becomes always true, a return value becomes empty — and runs the tests against
each changed version, called a *mutant*. A mutant that makes a test fail is
*killed*; one that passes every test has *survived*, and points at behaviour no
test pins down.

It runs on demand, not in CI, through a Maven profile:

```bash
mvn -Pmutation test     # reports in <module>/target/pit-reports/index.html
```

It covers the four modules with unit tests: `protocol`, `engine`, `client-core`
and `client-tui`, with PIT's `STRONGER` set of mutators. Code that only the
integration tests reach is left out, because PIT would run a Docker-based test
for every mutant: the whole `server` module, `GameClient`, and the console
input and output in the TUI's `Main` (its one pure function,
`likelyRejection`, is included). The whole run takes about a minute.

| Module | Mutants | Killed at first run | Killed now |
|---|---|---|---|
| protocol | 52 | 46 (88%) | 52 (100%) |
| engine | 137 | 119 (87%) | 128 (93%) |
| client-core | 16 | 16 (100%) | 16 (100%) |
| client-tui | 64 | 55 (86%) | 63 (98%) |

("Killed" includes the mutants that made a test run until it timed out: a
mutant that turns a loop into an endless one has been detected too.)

What the first run found was more useful than the percentages:

- **Two tests passed for the wrong reason.** The test that an empty player list
  is refused also passed without the emptiness check, because an empty list
  fails the "first player is among the players" check as well. The test for
  the 10 KiB size limit sent 10 KiB of blanks, which the JSON parser refuses
  anyway. Both now check the reason, and the size test sends *valid* JSON that
  only the size limit can refuse.
- **Rules in DECISIONS.md that no test pinned down.** A player at the inn does
  not count as held for the "last free player" rule; landing in the well does
  not free the player in the prison; the inn is not a swap; with two players,
  the swap in the well is not waived. Each now has a test.
- **The renderer's layout and colours.** No test noticed if a row got a tenth
  cell, if a token made its cell wider, or if every colour disappeared.

The 10 mutants that still survive were each looked at, and none can be killed
by a test of reachable behaviour:

- 5 in `GameEngine.advanceTurn`, on the path for a game where every player is
  trapped. Since the last free player is never trapped, `decide` cannot reach
  it; it stays as a defence for logs written before that rule.
- 2 in `Board.move`, on the clamp at square 0, which no roll can reach on the
  classic board.
- 3 *equivalent* mutants, which change the code but not what it does: the
  phase check in `rollDice` (outside a running game there is no current player,
  so the next check refuses the roll anyway), the "not the roller" condition
  when a swap frees the occupant (the roller is never trapped), and the
  renderer's check for an empty event list (without it, it writes an invisible
  pair of colour codes).

## Testing patterns applied

- **Seams instead of mocks** — `DiceRoller` and `Clock` are passed in; a queue
  and a fixed clock do the work a mocking framework would.
- **Hold each choice in place with a test.** Some behaviours are decisions, not
  facts: ignoring unknown fields, passing null tombstones through, the fallback
  for an overfull cell, cancelling the trap for the last free player. Each of
  them has a test whose only job is to fail if someone changes the decision
  without noticing.
- **Test from the outside** — the end-to-end test uses the topics, not the
  server's methods, and the renderer tests read the produced string, not the
  renderer's internals.
- **Repeatable by construction** — react to events instead of sleeping, and
  supply fixed inputs instead of a seeded random generator.
- **Remove the ANSI codes before comparing** — the layout tests match plain
  text, so changing a colour does not break them, and one separate test checks
  that the colours are there and are removed cleanly.

## Testing anti-patterns avoided

- **Using sleeps to keep two sides in step** — there are none in the suite. The
  single deadline exists to stop a hung test and to print what it saw, not to
  wait for something.
- **Faking the very infrastructure under test** — the broker is real wherever
  the broker's behaviour is the thing being checked.
- **Checking details that are not the agreement** — whole records are compared
  where the record is the contract, and rendered text is searched with
  `contains` where the layout is the contract.
- **Trusting a green test run too much** — the layer of games played for real
  exists precisely because unit tests can only check rules that someone already
  thought of, and issue #7 proved it.

## Issues (from ISSUES.md)

**#4** — Testcontainers against Docker daemon 29, first fixed with the
`api.version` system property in failsafe, later by upgrading Testcontainers.

**#9** — failsafe 3.6.0 ran the integration tests even with `-DskipTests`;
found by running the README's build command after the upgrade.

**#7** — the deadlock, found by playing the game for real.

**#8** — the broker-stopping test that raced the game and was fixed by turning
the fault into a starting condition.

Two mistakes in the tests themselves are also worth remembering. One renderer
test claimed there was a goose marker on square 55, which is not a goose
square: the *test* was wrong and the renderer was right. And a script for the
live games once ran `$(date +%s)` separately for each client, which gave the
two players different game ids and put them in different games.
