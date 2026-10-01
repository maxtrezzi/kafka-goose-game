# 07 — Infrastructure & Build

[← client-tui](06-client-tui.md) · [Testing →](08-testing.md)

## The Kafka cluster

`docker-compose.yml` in the root of the repository defines the whole running
environment: three brokers plus one short-lived service that creates the
topics. Each choice, with its reason:

### KRaft, combined mode, three nodes

- **[KRaft](11-glossary.md#kraft), so no ZooKeeper** — this is how Kafka 4.x
  manages itself, and ZooKeeper is on its way out. All three nodes run as
  **both controller and
  broker** (`KAFKA_PROCESS_ROLES: broker,controller`), and all three take part
  in the controller vote. That is fine for development and saves running a
  second group of three containers. A production cluster would keep the two
  roles apart, and seeing them combined here is what makes that difference
  visible.
- **Three brokers** is the smallest number that makes replication *interesting*.
  Three copies with `min.insync.replicas=2` keeps working when one broker is
  lost, and makes writes fail visibly when a second one goes. Both were shown
  on a running cluster ([chapter 8](08-testing.md)).
- **No volumes, on purpose** — `docker compose down` leaves a completely fresh
  cluster. Being able to throw the state away is worth more here than keeping
  it: every experiment starts from the same known point, so a result means
  something.

### Listeners: two networks, two addresses

Each broker offers two listeners for data, because a Kafka running in Docker is
reached from two different networks under two different addresses:

- `PLAINTEXT://kafka-N:29092` — for traffic *inside* the compose network
  (inter-broker, the init job, and the server and clients when they run in
  containers);
- `PLAINTEXT_HOST://localhost:9092/9094/9096` — advertised to *host*
  processes (the server and clients started from Maven).

Getting `advertised.listeners` right is the most common problem people hit
when running Kafka in Docker. The compose file lists both addresses in the
comment at the top.

### How the topics are created

- `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`. The game topics are created
  explicitly by the short-lived `init-topics` service, with 3 partitions, 3
  copies and `min.insync.replicas=2`. A misspelled topic name should be an
  error, not a topic quietly created with one copy and default settings.
- Kafka's own internal topics, such as `__consumer_offsets`, are also set to 3
  copies and a minimum of 2 in sync. Losing a broker must not take out the
  place where *offsets* are stored either. Forgetting this is a common gap in
  development compose files.
- `init-topics` waits for all three brokers to report healthy, using a
  `kafka-broker-api-versions.sh` check. It then creates both topics with
  `--if-not-exists`, so starting the cluster again changes nothing, prints
  their description into the log, and exits with status 0. The ordering comes
  from Compose's `depends_on: condition: service_healthy`, with no sleep loops
  anywhere.

One known weak point (DECISIONS.md): the topic names appear both in
`Topics.java`, where they are part of the contract, and in this YAML file, so
renaming a topic means changing both.

### The application in containers

A multi-stage `Dockerfile` builds an image for each runnable module. The build
stage runs Maven once, packaging `server` and `client-tui` together with their
runtime dependencies (`dependency:copy-dependencies`), with a cache mount for
the local Maven repository. The two run stages are a plain JRE with a jar and a
`lib/` folder on the classpath: no fat jar and no extra packaging plugin.

The compose file runs them behind **profiles**:

- `server` is in the `game` profile, so `docker compose up -d` still starts
  the cluster alone, and `docker compose --profile game up -d --build` adds
  the server. It waits for `init-topics` to finish
  (`service_completed_successfully`) and has `restart: unless-stopped`, because
  a server whose writes fail stops on purpose ([chapter 4](04-server.md)) and
  something has to start it again.
- `tui` is in its own profile and is never started by `up`. It is meant for
  `docker compose run --rm tui <gameId> <player>`, which gives the container
  the current terminal.

The profile on the server matters for correctness, not only for convenience:
a server in Docker and another started from Maven would join the same
consumer group and split the games between them, which the single-server
design does not support.

The CI runs this setup for real on every push: it starts the cluster and the
server, lets two players join and start a game through the `tui` image, and
checks the event log for `GameStarted`.

## The Maven build

A parent POM and five modules: `protocol` ← `engine` ← `server`, and
`protocol` ← `client-core` ← `client-tui`, where each arrow means "is used by".
The parent does three jobs:

1. **Fixing every version, in one place.** All library versions live in
   `<dependencyManagement>`: kafka-clients 4.3.1, jackson-databind 2.22.3,
   slf4j 2.0.17, the JUnit BOM 6.1.3 and the Testcontainers BOM 2.0.5. All of
   them are stable releases from Maven Central, deliberately avoiding alpha and
   milestone builds (slf4j stays on 2.0.x because 2.1 is still an alpha). The
   modules then declare dependencies without versions, so there is exactly one
   place where a version can be wrong.
2. **Java 21 through `maven.compiler.release`** — `release` rather than
   `source` and `target`, so the compiler also checks that the code only uses
   APIs that exist in JDK 21.
3. **Plugin management**: compiler 3.14.0, surefire/failsafe 3.5.3, exec
   3.6.4, dependency 3.11.0 — pinned so builds don't drift with Maven
   defaults. Surefire and failsafe are held at 3.5.3 on purpose: failsafe
   3.6.0 runs the integration tests even with `-DskipTests` (ISSUES.md #9).

Module-level choices:

- **Unit tests and integration tests are separated by plugin.** Surefire runs
  the `*Test` classes during the `test` phase in every module. **Failsafe is
  switched on only in `server` and `client-core`**, the two modules with `*IT`
  classes, and runs them during `verify`. The result: `mvn test` never needs
  Docker, while `mvn verify` runs the
  [Testcontainers](11-glossary.md#testcontainers) integration tests. (Until the
  move to Testcontainers 2.0.5, failsafe also had to set an `api.version`
  system property to work with Docker 29 — ISSUES.md #4.)
- **`slf4j-simple` is declared per logging module** (server, client-core,
  client-tui): kafka-clients logs through the slf4j API but does not expose
  it at compile scope (ISSUES.md #6).
- **exec-maven-plugin** is configured with a `mainClass` in `client-tui` (so
  `mvn -pl client-tui exec:java` just works); the server is launched with an
  explicit `-Dexec.mainClass=com.goosegame.server.GooseServer`.

### The multi-module trap, worth remembering

`mvn -pl engine test` fails on a fresh clone. Maven looks for the `protocol`
SNAPSHOT in the local repository, where it has never been installed. Two
commands that do work (ISSUES.md #2):

- `mvn -pl <module> -am test` — `-am` ("also make") builds the in-reactor
  dependencies too;
- for `exec:java` runs, `mvn -q -DskipTests install` once, so sibling
  SNAPSHOTs exist in the local repository.

## Patterns applied

- **Infrastructure as code, and reviewed like code** — the compose file went
  through the same review process as the Java sources.
- **Environments that fail immediately** — startup order driven by health
  checks, no topics created automatically, and state that can be thrown away.
- **One place that defines every version** — `dependencyManagement` plus
  BOMs.

## Anti-patterns avoided

- **Depending on SNAPSHOT or "latest" versions** — every version is fixed,
  plugins included.
- **Letting a replicated cluster create topics by itself** — switched off.
- **Internal topics with too few copies** — offsets and transaction state are
  also kept in three copies.
- **Ordering containers with sleep commands** — health checks and `depends_on`
  conditions instead.

## Decisions (from DECISIONS.md)

- Failsafe, and therefore the Docker-based tests, runs only in `server` and
  `client-core`.
- The server and the terminal client have Docker images, behind compose
  profiles, and the CI plays the start of a game with them.
- Topic names are written twice, in Java and in YAML; this is a known weak
  point.
- The build and workflow choices above are recorded there as well.

## Issues (from ISSUES.md)

**#2** — building one module without `-am` fails on a fresh clone.

**#4** — the API version problem between Testcontainers and Docker 29: the
environment variable was ignored, overriding the dependency had no effect
because the client is repackaged inside Testcontainers, and the fix was the
`api.version` system property — until the upgrade to Testcontainers 2.0.5
made it unnecessary.

**#9** — failsafe 3.6.0 ignores `-DskipTests`, so the plugins stay on 3.5.3.

**#6** — slf4j is not passed on at compile time, so each module that logs has
to declare it.
