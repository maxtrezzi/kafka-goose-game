# 07 — Infrastructure & Build

[← client-tui](06-client-tui.md) · [Testing →](08-testing.md)

## The Kafka cluster

`docker-compose.yml` at the repo root defines the whole runtime environment:
three brokers plus a one-shot topic initializer. Design choices, each with
its reason:

### KRaft, combined mode, three nodes

- **KRaft (no ZooKeeper)** — Kafka 4.x's native consensus; learning ZooKeeper
  operations in 2026 would be learning the past. All three nodes run in
  **combined controller+broker mode** (`KAFKA_PROCESS_ROLES:
  broker,controller`) with a controller quorum of all three — fine for dev,
  and one less container triple to operate. A production cluster would
  separate the roles; that distinction is exactly the kind of thing the setup
  makes visible.
- **Three brokers** is the minimum that makes replication *interesting*:
  RF=3 with `min.insync.replicas=2` survives one broker loss with full
  availability and fails writes loudly on the second loss — both behaviors
  were demonstrated live ([chapter 8](08-testing.md)).
- **No volumes, on purpose** — `docker compose down` gives a factory-fresh
  cluster. For a learning project, disposability beats durability: every
  experiment starts from a known state.

### Listeners: the dual-network reality

Each broker exposes two data listeners, because a Docker-hosted Kafka is
reachable from two networks with two different addresses:

- `PLAINTEXT://kafka-N:29092` — for traffic *inside* the compose network
  (inter-broker, the init job);
- `PLAINTEXT_HOST://localhost:9092/9094/9096` — advertised to *host*
  processes (the server and clients run on the host).

Getting `advertised.listeners` right is the single most common Kafka-in-
Docker stumbling block; the compose file documents both addresses in its
header comment.

### Topic hygiene

- `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"` — game topics are created
  explicitly by the `init-topics` one-shot service (3 partitions, RF=3,
  `min.insync.replicas=2`); a typo'd topic name should be an error, not a
  silently created 1-replica topic with defaults.
- The internal topics (`__consumer_offsets`, transaction state) are also
  pinned to RF=3 / min-ISR 2 — a broker loss must not take out *offset
  storage* either; forgetting this is a classic dev-compose gap.
- `init-topics` waits on all three brokers' **healthchecks** (a
  `kafka-broker-api-versions.sh` probe), creates both topics with
  `--if-not-exists` (idempotent re-up), describes them for the log, and
  exits 0 — Compose's `depends_on: condition: service_healthy` as the
  ordering mechanism, no sleep loops.

Known caveat (DECISIONS.md): topic names appear both in `Topics.java` (the
code contract) and in this YAML — a rename touches both.

## The Maven build

Parent POM + five modules (`protocol` ← `engine` ← `server`;
`protocol` ← `client-core` ← `client-tui`). The parent does three jobs:

1. **Version pinning, once.** All library versions live in
   `<dependencyManagement>` (kafka-clients 4.3.0, jackson-databind 2.19.0,
   slf4j 2.0.17, JUnit BOM 5.12.2, Testcontainers BOM 1.21.3 — all verified
   current-stable on Maven Central at project start, deliberately skipping
   alpha/milestone lines). Modules declare dependencies without versions;
   there is exactly one place a version can be wrong.
2. **Java 21 via `maven.compiler.release`** — release, not source/target,
   so the compiler also checks API usage against the JDK 21 platform.
3. **Plugin management**: compiler 3.14.0, surefire/failsafe 3.5.3, exec
   3.5.0 — pinned so builds don't drift with Maven defaults.

Module-level choices:

- **Unit tests vs. integration tests are split by plugin**: surefire runs
  `*Test` at the `test` phase everywhere; **failsafe is activated only in
  `server`** and runs `*IT` at `verify`. Consequence: `mvn test` never needs
  Docker; `mvn verify` runs the Testcontainers E2E. The failsafe config also
  carries the `api.version=1.44` system property — the workaround for the
  shaded docker-java client vs. Docker daemon ≥29 (ISSUES.md #4), commented
  with its removal condition.
- **`slf4j-simple` is declared per logging module** (server, client-core,
  client-tui): kafka-clients logs through the slf4j API but does not expose
  it at compile scope (ISSUES.md #6).
- **exec-maven-plugin** is configured with a `mainClass` in `client-tui` (so
  `mvn -pl client-tui exec:java` just works); the server is launched with an
  explicit `-Dexec.mainClass=com.goosegame.server.GooseServer`.

### The reactor gotcha (worth knowing cold)

`mvn -pl engine test` fails on a fresh checkout: Maven tries to resolve the
`protocol` SNAPSHOT from the repository, where it has never been installed.
Two correct invocations (ISSUES.md #2):

- `mvn -pl <module> -am test` — `-am` ("also make") builds the in-reactor
  dependencies too;
- for `exec:java` runs, `mvn -q -DskipTests install` once, so sibling
  SNAPSHOTs exist in the local repository.

## Patterns applied

- **Infrastructure as (reviewed) code** — the compose file went through the
  same review workflow as Java.
- **Fail-fast environments** — health-checked startup ordering, no
  auto-created topics, disposable state.
- **Single source of truth for versions** — dependencyManagement + BOMs.

## Anti-patterns avoided

- **SNAPSHOT/latest dependencies** — every version pinned, including plugins.
- **Auto-topic-creation in a replicated cluster** — off.
- **Under-replicated internal topics** — offsets/transactions at RF=3.
- **Sleep-based container orchestration** — healthchecks + `depends_on`
  conditions.

## Decisions (from DECISIONS.md)

Failsafe only in `server`; topic names duplicated in YAML (caveat); the
build/workflow entries.

## Issues (from ISSUES.md)

**#2** — the `-am` reactor gotcha. **#4** — the Testcontainers/Docker-29
API-version saga (env var ignored, dependency override inert because the
client is shaded, fixed via the `api.version` system property). **#6** —
slf4j not transitively visible at compile scope.
