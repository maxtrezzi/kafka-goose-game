# Decision Log

Non-obvious choices made during implementation, with the reasoning. PLAN.md fixes
the big architecture; this file records the calls made *inside* those boundaries.
Newest entries at the bottom of each section.

## Protocol (Step 3)

- **Unknown JSON fields are tolerated** (`FAIL_ON_UNKNOWN_PROPERTIES=false`), pinned
  by a test. Events live in the log indefinitely, so a newer producer must be able to
  add fields without poisoning older consumers. Safe because every required field is
  enforced by the record compact constructors — leniency can't smuggle in an
  incomplete message. Flip side: a *misspelled* field is silently ignored.
- **`JsonSerde` returns null for null** on both paths — deliberate deviation from the
  "never return null" default, required by Kafka's tombstone contract (commented in code).
- **One class implements `Serde` + `Serializer` + `Deserializer`** so the same
  instance serves plain clients and (later) Kafka Streams / `TopologyTestDriver`.
- **Inline `Instant` (de)serializer** (ISO-8601 strings) instead of the
  `jackson-datatype-jsr310` dependency — two tiny classes vs. a whole module.
- **Closed polymorphism**: explicit `@JsonSubTypes` on sealed interfaces, default
  typing never enabled — the wire format cannot name arbitrary classes (Jackson
  gadget-chain vector designed out).
- **10 KiB payload cap checked before parsing**; larger is garbage or abuse.
- **Poison pills** → `DeserializationException`; consumers log and seek past, never crash.
- **Names/gameIds restricted to `[A-Za-z0-9_-]`** (20/64 chars) in compact
  constructors — kills injection concerns (logs, shells, paths) at the boundary.

## Engine (Step 4)

- **Rejected commands produce no events** (no `CommandRejected` event) — the plan's
  "keep it simple" option; the server just logs. Revisit if client UX needs rejection
  feedback.
- **Goose hops repeat the last movement in its current direction**; a bounce off 63
  reverses direction, so a goose met while bouncing sends the token *backwards*.
  This is what makes every chain provably finite — the naive "goose always moves
  forward" rule loops forever from square 59 with a roll of 8 (59→67→bounce 59→…).
- **Inn (19) costs exactly one rotation**: when the turn passes over the trapped
  player they're freed (`PlayerFreed`) but skipped once; the rotation reaches them
  normally next time. In a 2-player game the opponent therefore rolls twice in a row.
- **Well (31) / prison (52) swap occupants**: lander becomes stuck, previous occupant
  freed. Faithful classic rule; corner case: if *every* remaining player is trapped
  at once, the game deadlocks (no `TurnStarted`) — documented, accepted.
- **`GameStarted` implies the first turn** (it carries `firstPlayer`); no redundant
  `TurnStarted` at game start.
- **`decide` folds its own events through `GameState.apply` before computing the next
  turn** — turn logic and state logic share one source of truth and cannot drift.
- **`GameState.apply` trusts the event log** (server-authoritative); cross-event
  invariants are guaranteed by the engine at decision time, not re-checked in the
  fold — validating both places would duplicate the rules.
- **`Board.resolve` validates `from` (0–63) and `roll` (1–12) at the boundary** —
  a roll of 0 on a goose square would otherwise loop forever (found in review).
- **Timestamps via injected `Clock`, dice via injected `DiceRoller`** — `decide` is a
  pure function; tests fix both, the server injects `SecureRandom`.

## Server (Step 5)

- **Single-threaded by design**: the `run()` thread owns every Kafka client and the
  state map — zero synchronization. `close()` (any thread) only *signals*: a
  `volatile` flag + `consumer.wakeup()` (the one thread-safe consumer method), then
  awaits a latch. Resources are closed by the thread that opened them.
- **At-least-once, not exactly-once**: `acks=all` + idempotent producer; offsets
  committed only after every produced event's future is confirmed (`flush()` +
  `get()` before `commitSync()`). A crash between produce and commit replays the
  command — mostly rejected by the engine, but a redelivered `RollDice` rolls again.
  Exactly-once would need Kafka transactions; deliberately out of scope.
- **Replay uses manual `assign` + `seekToBeginning`** (no consumer group, no
  commits): replay must always read everything, and group semantics would fight that.
- **Local state is throwaway**: rebuilt from the log on every start, so a crash can
  never leave state and log disagreeing.
- **Single-instance assumption**: commands keyed by gameId give each game one
  partition owner, so multiple instances would work per-game, but each instance's
  state for the *other* instances' games goes stale after replay. Harmless today;
  revisit before scaling past one instance.
- **E2E is deterministic by construction**: scripted dice injected through the same
  `DiceRoller` seam tests use; the driver reacts to each `GameStarted`/`TurnStarted`
  with exactly one `RollDice` — no sleeps, no state guessing. Server runs on a
  virtual thread, per plan.

## Client core (Step 6)

- **`GameView` re-implements the event fold** instead of reusing the engine's
  `GameState`: PLAN.md fixes `client-core → protocol` only (a client needs no game
  rules on its classpath). Deliberate duplication of fold semantics; the wire
  protocol — not a shared class — is the contract keeping the two folds in
  agreement, and the shared protocol tests are what guard it.
- **Fresh consumer group + `earliest` on every client start**, offsets never
  committed: a client (re)started mid-game rebuilds its whole view by replay. The
  Kafka log is the source of truth; the client keeps nothing.
- **Listener callbacks run on the client's event-loop virtual thread**, in log
  order; a listener exception is logged and skipped — a UI bug must not stop the
  event stream. UIs needing their own thread hand off themselves.
- **`GameClient.connect(...)` static factory** rather than a public constructor:
  the event-loop thread is created unstarted in the constructor and started only
  after construction completes — no `this`-escape from a constructor.
- **`flush()` after every command**: human-scale traffic, prompt delivery beats
  batching.
- **`GameView.recentEvents` caps at 10** — display log for UIs, oldest first.
- **Topic names live in `protocol.Topics`** (`Topics.COMMANDS`/`Topics.EVENTS`) —
  they are wire contract, so server and clients share one definition instead of
  hardcoding strings. Caveat: `docker-compose.yml`'s `init-topics` service still
  spells them as YAML — renaming a topic means changing `Topics.java` *and* the
  compose file together.

## Build / workflow

- **Failsafe activated only in `server`** — `*IT` tests (need Docker) run at
  `verify`, unit tests stay in `test`; `mvn test` never requires Docker.
- **Generate → skill review → fix-all → re-review → approve → commit** loop per
  PLAN.md step, with the `java-best-practices-modern` skill active for both
  generation and review (saved to memory 2026-07-03).
