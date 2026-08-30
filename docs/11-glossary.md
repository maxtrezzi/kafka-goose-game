# 11 — Glossary: the Terms This Documentation Uses

[← Implementation plan](10-implementation-plan.md) · [Overview](00-overview.md)

The chapters use the vocabulary of their fields without stopping to define it.
This is that reference: each term in a few lines, with a link to the source
that treats it properly.

It is a convenience, not a tutorial. The chapters assume you are reading for
the design rather than for an introduction to Kafka, so nothing here has to be
read first and nothing here is explained twice. It is simply quicker to settle
a word in two lines than to break off and go looking for it.

The terms themselves stay in the chapters unchanged. They are the words these
communities use, and paraphrasing them would only make the documentation
harder to match against the literature.

## Kafka terms

### Topic, partition, key

A **topic** is a named log of messages. Each topic is split into
**partitions**, and a partition is an ordered, append-only sequence. A message
carries a **key**; all messages with the same key go to the same partition, so
Kafka guarantees their relative order. This project keys both topics by
`gameId`, so one game is always one ordered story.

Source: [Kafka: introduction](https://kafka.apache.org/intro)

### Offset

The position of a message inside its partition, counted from zero. A consumer
stores the offset it has reached, so it can stop and continue later from the
same place.

Source: [Kafka documentation](https://kafka.apache.org/documentation/),
section "Consumer Position"

### Consumer group

A set of consumers that share the work of reading a topic: each partition goes
to exactly one member of the group. Two consumers in *different* groups both
receive every message, which is why every client in this project reads the full
event log while the server reads each command once.

Source: [Kafka documentation](https://kafka.apache.org/documentation/),
section "Consumers"

### Replay

Reading a topic again from the beginning instead of from the last stored
offset. Because the log keeps every event, a process that replays it rebuilds
its state exactly as it was. This is how the server and every client recover
after a restart.

### Replication factor, ISR, and minimum in-sync replicas

The **replication factor** is the number of brokers that hold a copy of each
partition (3 here). The **ISR**, or *in-sync replicas*, is the subset of those
copies that are currently up to date. `min.insync.replicas` (2 here) is the
number of in-sync copies a write needs before it is accepted, so the cluster
keeps working when one broker is lost but never accepts a write that only one
broker has seen.

Source: [Kafka documentation](https://kafka.apache.org/documentation/),
section "Replication"

### KRaft

The consensus protocol Kafka uses to manage its own metadata since it stopped
depending on ZooKeeper. A KRaft cluster needs no second system to run.

Source: [Kafka documentation](https://kafka.apache.org/documentation/),
section "KRaft"

### Delivery semantics: at-most-once, at-least-once, exactly-once

Three levels of guarantee. **At-most-once**: a message may be lost, never
handled twice. **At-least-once**: a message is never lost, but a crash at the
wrong moment can make it be handled twice. **Exactly-once**: neither, which
costs transactions and extra machinery. This project chooses at-least-once and
makes the repeated work harmless.

Source: [Kafka documentation](https://kafka.apache.org/documentation/),
section "Message Delivery Semantics"

### Idempotent producer

A producer setting (`enable.idempotence=true`) that lets Kafka recognise and
drop a message the producer sent twice after a network retry. It removes
duplicates caused by *retries*; it does not remove duplicates caused by the
application sending the same thing twice.

Source: [Kafka documentation](https://kafka.apache.org/documentation/),
section "Producer Configs"

### Tombstone

A message with a key and a `null` value. In a compacted topic it marks the key
as deleted. The word also describes any `null` used to mean "this field is
deliberately absent", which is how this project's `Serde` treats two optional
fields.

Source: [Kafka documentation](https://kafka.apache.org/documentation/),
section "Log Compaction"

### Poison pill

A message that the consumer cannot deserialize. Without a plan, it stops the
consumer forever: the consumer fails, restarts, reads the same message, and
fails again. The fix is to catch the failure, log it, and skip past that
offset.

Source: [Error handling patterns for Kafka
applications](https://www.confluent.io/blog/error-handling-patterns-in-kafka/)
(Confluent)

### Serde

Short for *serializer / deserializer*: the pair of functions that turn an
object into bytes for Kafka and back again.

## Design and architecture terms

### Event sourcing

Storing the history of *what happened* as an ordered list of events, and
deriving the current state from that history, instead of storing the current
state and overwriting it. The log is the truth; everything else can be thrown
away and rebuilt.

Source: [Event sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)
(Martin Fowler)

### Fold

Walking through a list and combining its items one at a time into a single
result, carrying the result forward at each step. Adding up a list of numbers
is a fold. In this project, `state.apply(event)` is applied to every event in
order, and the result is the current game state.

Source: [Fold (higher-order function)](<https://en.wikipedia.org/wiki/Fold_(higher-order_function)>)

### Server-authoritative

Only the server is allowed to decide what happens. Clients send *requests*, not
*results*, so a modified or hostile client cannot invent a dice roll or move a
piece. It can only ask.

### Wire contract

The exact shape of the messages that travel between processes: the field names,
the types, and the rules for reading them. It is a contract because both sides
must agree on it, and it can only be changed carefully once other programs
depend on it.

### CQRS and the read model

CQRS separates the code that *changes* state from the code that *reads* it. The
**read model** is a copy of the state shaped for display, rebuilt from the same
events. In this project `GameView` is a read model: it is only ever shown, never
used to decide anything.

Source: [CQRS](https://martinfowler.com/bliki/CQRS.html) (Martin Fowler)

### Ports and adapters (hexagonal architecture)

The core of the program defines the *ports* it needs as interfaces, and the
outside world is plugged in through *adapters* that implement them. The core
never knows which adapter it is talking to. Here `GameListener` is the port and
the terminal UI is one adapter.

Source: [Hexagonal
architecture](https://alistair.cockburn.us/hexagonal-architecture/)
(Alistair Cockburn)

### Functional core, imperative shell

A design where all the rules live in pure functions that only compute values,
and everything impure — reading input, writing output, talking to the network —
is pushed out to a thin outer layer. The core is easy to test because it does
nothing but return values.

Source: [Boundaries](https://www.destroyallsoftware.com/talks/boundaries)
(Gary Bernhardt, talk)

### Parse, don't validate

Instead of checking that data is valid and then passing the same loose type
around, convert it once into a type that *cannot* hold invalid data. After the
conversion, no further checking is needed anywhere. Here the record constructors
do the parsing.

Source: [Parse, don't
validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/)
(Alexis King)

### Coincidental duplication

Two pieces of code that look the same today but exist for different reasons and
will change for different reasons. Merging them creates a shared abstraction
that both sides then fight against. This is why the client rebuilds the game
state with its own fold instead of reusing the engine's.

Source: [The wrong
abstraction](https://sandimetz.com/blog/2016/1/20/the-wrong-abstraction)
(Sandi Metz)

### Seam

A place in a program where you can change what happens without editing the code
around it — typically by passing in a different implementation. Seams are what
make code testable without a mocking framework. `DiceRoller` is a seam: the test
passes a fixed sequence of rolls.

Source: [Legacy seam](https://martinfowler.com/bliki/LegacySeam.html)
(Martin Fowler)

## Java terms

### Record

A class whose whole job is to hold values. Java writes the constructor,
accessors, `equals`, `hashCode` and `toString` for you, and the fields cannot
change after construction.

Source: [JEP 395: Records](https://openjdk.org/jeps/395)

### Compact constructor

A short form of a record's constructor that only contains the checks and
adjustments, without listing or assigning the fields. It runs before the fields
are set, so it is the natural place to reject invalid values.

### Sealed interface

An interface that names exactly which types are allowed to implement it. The
compiler then knows the full list, which is what makes a `switch` over those
types provably complete.

Source: [JEP 409: Sealed classes](https://openjdk.org/jeps/409)

### Exhaustive pattern matching

A `switch` over a sealed type that must cover every case. If a new type is
added and some `switch` does not handle it, compilation fails — the compiler
finds the gap instead of the user.

Source: [JEP 441: Pattern matching for
switch](https://openjdk.org/jeps/441)

### Virtual thread

A thread managed by the JVM instead of the operating system. Virtual threads
are cheap enough to create one per task and let it block, instead of writing
callback-style code.

Source: [JEP 444: Virtual threads](https://openjdk.org/jeps/444)

## Testing terms

### Test pyramid

The rule of thumb that a project should have many fast unit tests, fewer
service-level tests, and very few slow end-to-end tests. The shape follows from
cost: the tests at the top are the slowest to run and the most annoying to
maintain.

Source: [The practical test
pyramid](https://martinfowler.com/articles/practical-test-pyramid.html)
(Martin Fowler)

### Testcontainers

A library that starts real services in Docker containers from inside a test and
shuts them down afterwards. It is how the end-to-end test here runs against a
real Kafka broker rather than a fake one.

Source: [Testcontainers](https://testcontainers.com/)

### Smoke test

A quick, manual run of the real system to see whether it works at all, as
opposed to an automated test that checks one specific rule. Several of the
problems in `ISSUES.md` were found this way and only afterwards pinned down by
a unit test.
