# 08 — Testing Strategy

[← Infrastructure & build](07-infrastructure-and-build.md) · [Patterns catalog →](09-patterns-and-antipatterns.md)

The test suite is shaped like the architecture: the purer the layer, the more
tests it gets and the cheaper they are. 105 tests total, no mocking framework
anywhere — every seam that needs faking (`DiceRoller`, `Clock`,
`GameListener`) is a small interface a test can implement in one line.

| Module | Tests | Kind | What they prove |
|---|---|---|---|
| protocol | 46 | unit | Round-trip of every message type; rejection of malformed / unknown-type / oversized / non-string-timestamp payloads; unknown-*field* tolerance; tombstone nulls; validation rules incl. duplicates |
| engine | 39 | unit | Every board rule; every rejection; trap/free/turn flow; the deadlock amendment; full scripted games with fixed dice |
| server | 1 | E2E (Testcontainers) | The entire stack against a real broker |
| client-core | 10 | unit | The view fold over scripted event sequences (replay logic) |
| client-tui | 9 | unit | Board layout, markers, tokens, status, winner banner, event narration, ANSI hygiene, the overfull-cell edge |

## Why the pyramid is this shape

- **Protocol and engine carry the weight** because they are pure: tests are
  milliseconds, need no infrastructure, and can enumerate rules exhaustively.
  A scripted full game in `GameEngineTest` folds every event through
  `GameState` exactly as the server would — so most "integration" behavior is
  already proven below the integration layer.
- **The server gets one test, but it is the whole system.** Everything the
  server adds — Kafka wiring, replay, produce-confirm-commit ordering,
  shutdown — is only meaningful against a real broker. Unit-testing it would
  mean mocking `KafkaConsumer`, which tests the mock, not the contract.
- **`mvn test` never needs Docker** — the E2E is an `*IT` under failsafe at
  `verify` ([chapter 7](07-infrastructure-and-build.md)). Fast feedback for
  logic, full proof on demand.

## The deterministic E2E

`GooseServerIT` starts a throwaway `apache/kafka:4.3.0` container
(Testcontainers), creates the topics via the Admin API, runs the real
`GooseServer.run()` on a **virtual thread**, and then plays a two-player game
*entirely from outside* — producing commands and consuming events over the
network, with no access to server internals.

Determinism is designed, not hoped for:

- **Scripted dice** injected through the same `DiceRoller` seam production
  uses (a queue: `queue::remove` — exhaustion throws, so a script/logic
  mismatch fails loudly instead of rolling garbage). The script is chosen so
  the game exercises a goose hop, the bridge, and an exact landing on 63.
- **The driver is reactive, not timed.** It answers each
  `GameStarted`/`TurnStarted` event with exactly one `RollDice` for the
  announced player, until `GameWon`. There are no sleeps and no offset
  arithmetic — the event stream itself is the synchronization. A 90-second
  deadline exists only as a failure backstop, and its failure message dumps
  the event history collected so far.

Assertions cover the three levels that matter: the opening sequence
(joins/start, record-equality on whole events), the scripted landmarks
(bob's bridge jump, alice's goose hop, alice's exact win), and — the
event-sourcing money shot — that **folding the observed history reproduces
the expected final state** (`{alice=63, bob=21}`, FINISHED, winner alice).
Finally the test asserts clean shutdown: `close()` returns and the server
thread actually dies.

## What automation missed and live testing caught

The project's most instructive testing lesson is issue #7: 39 engine tests,
a green E2E — and the **first live smoke game** froze the system. Two piped
TUI clients blindly rolling every second (safe because out-of-turn rolls are
rejected without effect) hit the well+prison mutual deadlock within one game.
Unit tests verify the rules you wrote; free-running play explores the state
space you didn't think to write rules about. The fix came with two new unit
tests (`lastFreePlayerIsNeverTrapped`, `trapStillAppliesWhileAnotherPlayerIsFree`)
— live testing finds, unit tests pin.

The same blind-roll harness became the **final verification** rig
(PLAN.md Step 8): clean `mvn verify`, fresh cluster, then complete games
through the real compose cluster — including one played start-to-win with a
broker stopped the whole time, ISR healing verified afterwards, and a fresh
client rebuilding the finished board by replay alone.

That last run produced its own lesson (ISSUES.md #8): the first broker-kill
orchestration waited for `GameStarted`, slept, then killed the broker
"mid-game" — but blind-roll games finish in ~90 seconds, and the game was
already *won* before the kill fired; the "moves kept flowing" observation was
vacuously true on a finished game. The fix: make the fault a **precondition**
instead of an interruption — stop the broker first, then play the entire
game. Timing-based fault injection against a fast workload silently tests
nothing.

## Testing patterns applied

- **Seams over mocks** — `DiceRoller`/`Clock` injection; a queue and a fixed
  clock replace any mocking framework.
- **Pin the policy** — behaviors that are *choices* (unknown-field tolerance,
  tombstone nulls, the overfull-cell fallback, the deadlock waiver) each have
  a test whose only job is to fail if the choice silently changes.
- **Test through the public surface** — the E2E drives topics, not methods;
  renderer tests read strings, not internals.
- **Deterministic by construction** — react to events rather than sleep;
  scripted inputs rather than seeded randomness.
- **ANSI-strip before assert** — layout tests match plain text, so color
  changes don't break them (and one test asserts colors exist and strip
  clean).

## Testing anti-patterns avoided

- **Sleep-based synchronization** — nowhere in the suite; the one deadline is
  a backstop with diagnostics, not a wait.
- **Mocking the infrastructure you're trying to learn** — the broker is real
  where the broker matters.
- **Asserting on incidental detail** — whole-record equality where the
  contract is the record; `contains` on rendered text where layout is the
  contract.
- **Green-suite complacency** — the live smoke layer exists precisely because
  the unit layer can only check known rules (and #7 proved the point).

## Issues (from ISSUES.md)

**#4** — Testcontainers vs. Docker daemon 29 (fixed via `api.version`
system property in failsafe). **#7** — the deadlock, found live. **#8** —
the raced broker-kill test, fixed by inverting fault injection into a
precondition. Also, review-cycle test bugs worth remembering: one renderer
test initially asserted a goose marker on square 55 (not a goose square) —
the *test* was wrong, the renderer right; a smoke-game script once computed
`$(date +%s)` per client, putting the two players in different games.
