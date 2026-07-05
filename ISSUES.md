# Issue Log

Problems hit during implementation, what was tried, and what actually fixed them.
Newest at the bottom.

## 1. Latent infinite loop in the naive goose rule (Step 4, caught at design time)

**Symptom (would have been):** a token on square 59 rolling 8 with "goose always
moves forward" bounces 59→67→59, lands on the goose again, hops forward again —
forever.

**Fix:** goose hops repeat the movement *in its current direction*; a bounce off 63
flips the direction, so post-bounce goose squares push the token backwards. Forward
hops strictly climb until at most one reflection, backward hops strictly descend —
every chain terminates. See DECISIONS.md.

## 2. `mvn -pl engine test` fails: `Could not find artifact com.goosegame:protocol`

**Symptom:** building a single module fails to resolve sibling SNAPSHOT modules that
were never `mvn install`ed.

**Fix:** build with the reactor: `mvn -pl engine -am test` (`-am` = also make
dependencies). Applies to every module: use `-pl server -am verify`, etc.

## 3. `Board.resolve(x, 0)` would spin forever (Step 4, found by skill review)

**Symptom:** `resolve` is public; a roll of 0 landing on a goose square repeats a
0-step hop infinitely (OOM building the moves list). Unreachable through
`GameEngine` — but only by accident of statement ordering (`DiceRolled`'s compact
constructor validated the dice first).

**Fix:** validate at the boundary: `from` must be 0–63, `roll` 1–12, else
`IllegalArgumentException`. Pinned by `BoardTest.argumentsOutsideTheBoardAreRejected`.

## 4. Testcontainers cannot find a "valid Docker environment" (Step 5)

**Symptom:** `GooseServerIT` fails at startup:
`UnixSocketClientProviderStrategy: ... client version 1.32 is too old. Minimum
supported API version is 1.40` — while `docker info` works fine from the shell.

**Cause:** the local Docker daemon is 29.6.0, whose minimum API version (1.40)
rejects the API-1.32 requests made by the docker-java client that Testcontainers
1.21.3 **shades inside its own jar**. 1.21.3 is the latest Testcontainers release
(checked Maven Central 2026-07-03), so no upgrade available.

**Tried and failed:**
- `DOCKER_API_VERSION=1.44` env var → ignored by the shaded client's config path.
- Overriding `com.github.docker-java:*` to 3.5.1 in `dependencyManagement` → inert,
  because the classes are shaded (`org.testcontainers.shaded.com.github.dockerjava`),
  not resolved as a dependency. Reverted.

**Fix:** the shaded client honors the `api.version` **system property**. Baked into
the failsafe plugin config in `server/pom.xml`:
`<systemPropertyVariables><api.version>1.44</api.version></systemPropertyVariables>`.
Drop it once a Testcontainers release raises its default API version.

## 5. Jackson's unknown-field default was silently in force (Step 3, found by skill review)

**Symptom:** not a failure — a landmine. `FAIL_ON_UNKNOWN_PROPERTIES=true` (Jackson's
default) was active but chosen by nobody and tested by nothing: the first added
event field in a future version would have poisoned every not-yet-upgraded consumer.

**Fix:** made the policy explicit (`false`, forward-compatible) with a why-comment
and a pinning test. See DECISIONS.md for the reasoning.

## 6. `package org.slf4j does not exist` in client-core (Step 6)

**Symptom:** first compile of `GameClient` failed — slf4j is not on the classpath.
`kafka-clients` uses slf4j internally but does not expose it transitively at
compile scope.

**Fix:** each module that logs declares `slf4j-simple` explicitly (version managed
by the parent), matching what `server` already did. Added to `client-core/pom.xml`.

## 7. The documented well+prison deadlock happened in the FIRST live game (Step 7)

**Symptom:** smoke-testing the TUI against the real cluster (two scripted clients,
random server dice), alice fell into the well, then bob goose-hopped 45→52 into the
prison. Both players trapped, nobody left to free anyone: game frozen, exactly the
corner case Step 4 had documented as "faithful, if merciless" and accepted as
improbable. One game later it fired — with 2 players it is a live risk, not a
curiosity.

**Fix (user decision):** rule amendment — **the last free player never gets
trapped**. `GameEngine.freezesTheGame(...)` waives the trap when landing on an
unoccupied well/prison while every other player is held in one (inn players count
as recoverable). Pinned by `lastFreePlayerIsNeverTrapped` and
`trapStillAppliesWhileAnotherPlayerIsFree`. The all-trapped branch in
`advanceTurn` stays as a defensive path for logs predating the rule.

**Lesson:** "improbable" corner cases in a 2-player game with ~20% trap density are
not improbable. The smoke test earned its keep on day one.
