# 09 — Patterns Applied & Anti-Patterns Avoided: the Catalog

[← Testing](08-testing.md) · [Implementation plan →](10-implementation-plan.md)

The consolidated reference. Each entry names where the pattern lives and
which chapter explains the reasoning. The through-line: **most entries are
Java 21 language features or Kafka primitives used as intended** — the
project's bet is that modern language design has absorbed many patterns that
once needed hand-rolling.

## Architectural patterns

| Pattern | Where | Chapter |
|---|---|---|
| **Event Sourcing** | `game.events` as sole source of truth; all state a fold; replay everywhere | [01](01-architecture.md) |
| **CQRS (miniature)** | Commands and events as separate topics *and* separate sealed types; `GameView` as the read-side projection | [01](01-architecture.md), [05](05-client-core.md) |
| **Single Writer** | Only the server writes `game.events`; per-game single writer via gameId keying | [01](01-architecture.md) |
| **Functional core, imperative shell** | Pure `engine` inside the Kafka-facing `server`; pure `BoardRenderer` inside stdio `Main` | [03](03-engine.md), [04](04-server.md), [06](06-client-tui.md) |
| **Ports & adapters** | `GameListener` as the UI port; `client-core` knows no UI | [05](05-client-core.md) |
| **State machine** | `Phase` enum + exhaustive switches; invalid transitions are rejections | [03](03-engine.md) |
| **Outbox-style ordering** | Produce-confirm *then* offset-commit — never acknowledge input before output is durable | [04](04-server.md) |

## Design & language patterns (Java 21 as the pattern language)

| Pattern | Realization | Chapter |
|---|---|---|
| **Algebraic data types** | `sealed interface` + `record` per case for `Command`/`Event`; exhaustive `switch` with **no `default`** so protocol growth breaks every fold/renderer at compile time | [02](02-protocol.md) |
| **Parse, don't validate** | All validation in compact constructors; an invalid message cannot exist as an object, whatever its origin | [02](02-protocol.md) |
| **Make illegal states unrepresentable** | `Optional` components for absence; `Phase` instead of boolean flags; deep-immutable records (`List.copyOf`/`Map.copyOf` in compact constructors) | [02](02-protocol.md), [03](03-engine.md) |
| **Strategy via functional interface** | `DiceRoller` — the single seam that replaces a mocking framework; `Clock` likewise | [03](03-engine.md), [08](08-testing.md) |
| **Static factory** | `GameClient.connect()` — prevents this-escape, names the operation | [05](05-client-core.md) |
| **Withers for derived records** | Explicit `withPosition`/`withStuck`/… copy methods (Java has no `with` expressions through 25) | [03](03-engine.md) |
| **Value-based equality as a test tool** | Whole-event `assertEquals` in the E2E works because messages are records | [08](08-testing.md) |

## Concurrency patterns

| Pattern | Where | Chapter |
|---|---|---|
| **Thread confinement** | Server: one thread owns consumers, producer, and the state map — zero locks; client event loop likewise | [04](04-server.md), [05](05-client-core.md) |
| **Signal-don't-touch shutdown** | `volatile` flag + `consumer.wakeup()` (the one thread-safe consumer method) + bounded latch/join; the opener closes its own resources | [04](04-server.md), [05](05-client-core.md) |
| **Virtual threads for I/O loops** | Client event loop; server-on-virtual-thread in the E2E | [05](05-client-core.md), [08](08-testing.md) |
| **Immutable snapshot publication** | `volatile GameView view` — safe unsynchronized reads because the value is immutable | [05](05-client-core.md) |

## Kafka patterns

| Pattern | Where | Chapter |
|---|---|---|
| **Keyed partitioning for per-entity order** | Everything keyed by gameId | [01](01-architecture.md) |
| **At-least-once with explicit commit ordering** | flush + confirm futures before `commitSync`; auto-commit off | [04](04-server.md) |
| **Idempotent producer + `acks=all` + min-ISR 2** | Server producer / topic config | [04](04-server.md), [07](07-infrastructure-and-build.md) |
| **Replay via manual assign** | No group for the server's startup replay; throwaway groups + `earliest` for clients | [04](04-server.md), [05](05-client-core.md) |
| **Poison-pill seek-past** | Catch `RecordDeserializationException`, log, `seek(offset+1)` | [04](04-server.md) |
| **Tombstone pass-through** | Serde's null-in/null-out; explicit null handling downstream | [02](02-protocol.md) |

## Anti-patterns avoided — and where the temptation was real

Security & robustness:

- **Jackson polymorphic default typing** (gadget-chain RCE vector) → closed
  `@JsonSubTypes` on sealed types; the wire can name 11 record types, ever.
- **Unbounded/unvalidated input** → 10 KiB payload cap before parsing;
  `[A-Za-z0-9_-]` charsets killing log/ANSI/shell injection at the boundary.
- **Client-trusted randomness or state** → dice only server-side, from
  `SecureRandom`.
- **Crash-on-bad-record consumer loops** → poison pills skipped, never fatal.

Correctness:

- **Dual writes** → local state fed only by the exact events produced.
- **Silent delivery semantics** → the at-least-once window is documented at
  the code that creates it; auto-commit (which would silently give
  at-most-once here) explicitly disabled.
- **Duplicated business logic** → rules live once in `decide`; the fold
  trusts the log. The one *deliberate* duplication (client fold) is analyzed,
  bounded by shared protocol tests, and documented — the judgment call
  between harmful duplication and false sharing, made explicitly.
- **Unbounded rule loops** → goose-chain termination proven; zero-step rolls
  rejected at the `Board.resolve` boundary.
- **Exception-driven control flow** → rejection is data (empty list), not a
  throw.

Concurrency:

- **Shared mutable state under optimistic assumptions** → confinement, not
  locks-added-later.
- **this-escape from constructors** → `connect()` factory starts the thread
  after construction.
- **Swallowed `InterruptedException`** → always re-interrupt, then propagate.
- **`volatile` read-modify-write** → volatile only for flags/snapshot refs.

Hygiene:

- **Null-returning APIs** → `Optional`/empty collections; the two tombstone
  nulls are the single commented contract exception.
- **Lost exception causes** → every wrap passes the cause; poison-pill
  messages use `toString()` because `getMessage()` can be null.
- **Locale-dependent machine output** → `Locale.ROOT` pinned in the renderer.
- **Raw control bytes in source** → ANSI escapes only as `\u001B` literals
  (a hazard the project hit twice — once in generated Java, once in these
  very docs, both times introduced by tooling and caught by inspection).
- **Version drift** → every library and plugin pinned via the parent POM.
- **Sleep-based synchronization in tests and orchestration** →
  event-reactive test driver; health-checked compose startup; and after
  issue #8, fault injection as precondition instead of timing.

## Where to read the histories

- [`DECISIONS.md`](../DECISIONS.md) — every non-obvious call, grouped by
  step, with reasoning and revisit-conditions.
- [`ISSUES.md`](../ISSUES.md) — all 8 issues: symptom, failed attempts, the
  actual fix, and the transferable lesson.
