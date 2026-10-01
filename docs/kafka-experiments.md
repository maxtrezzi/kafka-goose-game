# Kafka experiments

[← README](../README.md)

Five things to try against the running cluster. It is sized for exactly
these: three brokers, three partitions per topic, three copies of each.

Start the game first, either way the [README quickstart](../README.md#quickstart)
describes, and play a few turns. All commands run from the repository root.

## 1. Stop a broker in the middle of a game

Both topics keep three copies of every partition, with
[`min.insync.replicas=2`](11-glossary.md#replication-factor-isr-and-minimum-in-sync-replicas).
Any single broker can stop without losing a message and without stopping the
game.

```bash
docker stop goose-kafka-2      # mid-game, while people are rolling
# ... keep playing: joins, rolls, moves all still work ...
docker start goose-kafka-2     # it catches back up and rejoins the ISR
```

Watch the ISR shrink and recover:

```bash
docker exec goose-kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:29092 --describe --topic game.events
```

While the broker is down, the partitions it was leading get a new leader and
`Isr:` drops to two entries. After the restart it goes back to three. Stopping a
*second* broker breaks the `min.insync.replicas=2` promise, and the producer's
`acks=all` writes start to fail. That is not a defect: it is the durability
guarantee doing exactly what it says.

## 2. Watch the event log live

The whole game is readable JSON on one topic:

```bash
docker exec goose-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:29092 --topic game.events \
  --from-beginning --property print.key=true
```

Play a few turns and watch the `DiceRolled`, `PlayerMoved` and `PlayerStuck`
facts appear, each keyed by its `gameId`. This is also the quickest way to
settle an argument about what the rules did.

## 3. Inspect consumer groups and offsets

```bash
docker exec goose-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --list

docker exec goose-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --describe --group goose-server
```

You will see one lasting group, `goose-server`. Its `CURRENT-OFFSET` on
`game.commands` moves forward only after the events that command produced have
been safely written — that ordering is what makes the delivery
[at-least-once](11-glossary.md#delivery-semantics-at-most-once-at-least-once-exactly-once).

The clients do not appear at all. They use no consumer group: each one assigns
itself every partition of `game.events` and reads it from the beginning, the
same way the server does its replay, so nothing is ever stored on the brokers
on a client's behalf.

## 4. Replay a finished game

State is disposable everywhere; the log is the truth.

- Restart the **server** (`docker compose restart server`, or stop and start
  it from Maven). It logs how many events it read back and can carry on with
  any unfinished game. Games that already ended are rebuilt too, `GameWon`
  included.
- Restart a **client** with an old `gameId`. The whole board comes back from the
  log alone, winner line and all.
- Or work through the log yourself, with the console consumer from experiment 2.
  Every board any client ever displayed can be derived from that stream.

## 5. Not done here: SASL/SCRAM and ACLs

The cluster runs on PLAINTEXT on purpose, so that every experiment above works
without setting up credentials first. Closing that gap is the obvious next
step, and it is a small one. Add a `SASL_PLAINTEXT` listener using
SCRAM-SHA-256, create the users
`goose-server` and `goose-client` with `kafka-configs.sh`, then use
`kafka-acls.sh` to give clients permission to *write* only to `game.commands`
and to *read* only from `game.events`, with the opposite rights for the server.
That puts the rule "clients ask, the server decides" into the brokers
themselves, instead of trusting the code to respect it.
