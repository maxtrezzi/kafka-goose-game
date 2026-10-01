package com.goosegame.engine;

import com.goosegame.protocol.Command;
import com.goosegame.protocol.Event;
import com.goosegame.protocol.MoveReason;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-02T12:00:00Z");
    private static final String GAME = "g";

    /** Fails the test if a rejected command ever touches the dice. */
    private static final DiceRoller NO_DICE = () -> {
        throw new AssertionError("a rejected command must not roll the dice");
    };

    private final GameEngine engine = new GameEngine(Clock.fixed(NOW, ZoneOffset.UTC));

    private static DiceRoller dice(int... rolls) {
        var queue = new ArrayDeque<Integer>();
        Arrays.stream(rolls).forEach(queue::add);
        return queue::remove;
    }

    /** A running game; players join in the given order, first player starts. */
    private GameState running(String... players) {
        var state = GameState.newGame(GAME);
        for (String player : players) {
            state = state.apply(new Event.PlayerJoined(GAME, NOW, player));
        }
        return state.apply(new Event.GameStarted(GAME, NOW, List.of(players), players[0]));
    }

    private static GameState at(GameState state, String player, int square) {
        return state.apply(new Event.PlayerMoved(GAME, NOW, player, 0, square, MoveReason.NORMAL));
    }

    // --- JoinGame ---

    @Test
    void joinInTheLobbyIsAccepted() {
        var state = GameState.newGame(GAME);
        assertEquals(List.of(new Event.PlayerJoined(GAME, NOW, "alice")),
                engine.decide(state, new Command.JoinGame(GAME, "alice"), NO_DICE));
    }

    @Test
    void duplicateNameIsRejected() {
        var state = GameState.newGame(GAME).apply(new Event.PlayerJoined(GAME, NOW, "alice"));
        assertEquals(List.of(),
                engine.decide(state, new Command.JoinGame(GAME, "alice"), NO_DICE));
    }

    @Test
    void joinAfterStartIsRejected() {
        assertEquals(List.of(),
                engine.decide(running("alice", "bob"), new Command.JoinGame(GAME, "carol"), NO_DICE));
    }

    @Test
    void seventhPlayerIsRejected() {
        var state = GameState.newGame(GAME);
        for (int i = 1; i <= GameEngine.MAX_PLAYERS; i++) {
            state = state.apply(new Event.PlayerJoined(GAME, NOW, "p" + i));
        }
        assertEquals(List.of(),
                engine.decide(state, new Command.JoinGame(GAME, "p7"), NO_DICE));
    }

    @Test
    void commandForAnotherGameIsRejected() {
        assertEquals(List.of(),
                engine.decide(GameState.newGame(GAME), new Command.JoinGame("other", "alice"), NO_DICE));
    }

    // --- StartGame ---

    @Test
    void startWithTwoPlayersEmitsGameStartedInJoinOrder() {
        var state = GameState.newGame(GAME)
                .apply(new Event.PlayerJoined(GAME, NOW, "alice"))
                .apply(new Event.PlayerJoined(GAME, NOW, "bob"));
        assertEquals(
                List.of(new Event.GameStarted(GAME, NOW, List.of("alice", "bob"), "alice")),
                engine.decide(state, new Command.StartGame(GAME, "bob"), NO_DICE));
    }

    @Test
    void startWithOnePlayerIsRejected() {
        var state = GameState.newGame(GAME).apply(new Event.PlayerJoined(GAME, NOW, "alice"));
        assertEquals(List.of(),
                engine.decide(state, new Command.StartGame(GAME, "alice"), NO_DICE));
    }

    @Test
    void startByANonMemberIsRejected() {
        var state = GameState.newGame(GAME)
                .apply(new Event.PlayerJoined(GAME, NOW, "alice"))
                .apply(new Event.PlayerJoined(GAME, NOW, "bob"));
        assertEquals(List.of(),
                engine.decide(state, new Command.StartGame(GAME, "mallory"), NO_DICE));
    }

    @Test
    void startWhenAlreadyRunningIsRejected() {
        assertEquals(List.of(),
                engine.decide(running("alice", "bob"), new Command.StartGame(GAME, "alice"), NO_DICE));
    }

    // --- RollDice ---

    @Test
    void rollOutOfTurnIsRejected() {
        assertEquals(List.of(),
                engine.decide(running("alice", "bob"), new Command.RollDice(GAME, "bob"), NO_DICE));
    }

    @Test
    void rollInTheLobbyIsRejected() {
        var state = GameState.newGame(GAME).apply(new Event.PlayerJoined(GAME, NOW, "alice"));
        assertEquals(List.of(),
                engine.decide(state, new Command.RollDice(GAME, "alice"), NO_DICE));
    }

    @Test
    void plainRollMovesAndHandsTheTurnOver() {
        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "alice", 1, 2),
                new Event.PlayerMoved(GAME, NOW, "alice", 0, 3, MoveReason.NORMAL),
                new Event.TurnStarted(GAME, NOW, "bob")),
                engine.decide(running("alice", "bob"), new Command.RollDice(GAME, "alice"), dice(1, 2)));
    }

    @Test
    void gooseChainIsPlayedOutInOneRoll() {
        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "alice", 2, 3),
                new Event.PlayerMoved(GAME, NOW, "alice", 0, 5, MoveReason.NORMAL),
                new Event.PlayerMoved(GAME, NOW, "alice", 5, 10, MoveReason.GOOSE),
                new Event.TurnStarted(GAME, NOW, "bob")),
                engine.decide(running("alice", "bob"), new Command.RollDice(GAME, "alice"), dice(2, 3)));
    }

    @Test
    void exactLandingOnSixtyThreeWinsAndEndsTheGame() {
        var state = at(running("alice", "bob"), "alice", 60);
        var events = engine.decide(state, new Command.RollDice(GAME, "alice"), dice(1, 2));
        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "alice", 1, 2),
                new Event.PlayerMoved(GAME, NOW, "alice", 60, 63, MoveReason.NORMAL),
                new Event.GameWon(GAME, NOW, "alice")),
                events);

        var finished = fold(state, events);
        assertEquals(GameState.Phase.FINISHED, finished.phase());
        assertEquals(Optional.of("alice"), finished.winner());
        assertEquals(List.of(),
                engine.decide(finished, new Command.RollDice(GAME, "bob"), NO_DICE));
    }

    @Test
    void overshootBouncesAndTheGameGoesOn() {
        var state = at(running("alice", "bob"), "alice", 60);
        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "alice", 2, 3),
                new Event.PlayerMoved(GAME, NOW, "alice", 60, 63, MoveReason.NORMAL),
                new Event.PlayerMoved(GAME, NOW, "alice", 63, 61, MoveReason.BOUNCE),
                new Event.TurnStarted(GAME, NOW, "bob")),
                engine.decide(state, new Command.RollDice(GAME, "alice"), dice(2, 3)));
    }

    @Test
    void innCostsExactlyOneTurn() {
        // alice lands on the inn (19): stuck, turn passes to bob
        var state = at(running("alice", "bob"), "alice", 17);
        var landing = engine.decide(state, new Command.RollDice(GAME, "alice"), dice(1, 1));
        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "alice", 1, 1),
                new Event.PlayerMoved(GAME, NOW, "alice", 17, 19, MoveReason.NORMAL),
                new Event.PlayerStuck(GAME, NOW, "alice", 19),
                new Event.TurnStarted(GAME, NOW, "bob")),
                landing);
        state = fold(state, landing);

        // bob rolls: alice is freed but misses this rotation, so bob plays again
        var bobsTurn = engine.decide(state, new Command.RollDice(GAME, "bob"), dice(1, 2));
        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "bob", 1, 2),
                new Event.PlayerMoved(GAME, NOW, "bob", 0, 3, MoveReason.NORMAL),
                new Event.PlayerFreed(GAME, NOW, "alice"),
                new Event.TurnStarted(GAME, NOW, "bob")),
                bobsTurn);
        state = fold(state, bobsTurn);

        // next rotation reaches alice normally
        var next = engine.decide(state, new Command.RollDice(GAME, "bob"), dice(1, 2));
        assertEquals(new Event.TurnStarted(GAME, NOW, "alice"), next.getLast());
    }

    @Test
    void wellHoldsUntilAnotherPlayerTakesThePlace() {
        var state = at(at(running("alice", "bob", "carol"), "alice", 29), "bob", 29);

        // alice falls into the well: stuck, bob plays
        var aliceIn = engine.decide(state, new Command.RollDice(GAME, "alice"), dice(1, 1));
        assertEquals(new Event.PlayerStuck(GAME, NOW, "alice", 31), aliceIn.get(2));
        assertEquals(new Event.TurnStarted(GAME, NOW, "bob"), aliceIn.getLast());
        state = fold(state, aliceIn);

        // alice cannot roll while in the well, even out of spite
        assertEquals(List.of(),
                engine.decide(state, new Command.RollDice(GAME, "alice"), NO_DICE));

        // bob lands in the well too: he takes alice's place, she is freed
        var swap = engine.decide(state, new Command.RollDice(GAME, "bob"), dice(1, 1));
        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "bob", 1, 1),
                new Event.PlayerMoved(GAME, NOW, "bob", 29, 31, MoveReason.NORMAL),
                new Event.PlayerStuck(GAME, NOW, "bob", 31),
                new Event.PlayerFreed(GAME, NOW, "alice"),
                new Event.TurnStarted(GAME, NOW, "carol")),
                swap);
        state = fold(state, swap);

        // rotation after carol skips bob (still in the well) and reaches alice
        var carols = engine.decide(state, new Command.RollDice(GAME, "carol"), dice(1, 2));
        assertEquals(new Event.TurnStarted(GAME, NOW, "alice"), carols.getLast());
    }

    @Test
    void lastFreePlayerIsNeverTrapped() {
        // alice is already in the well; bob (the only free player) lands in the
        // unoccupied prison — the trap is waived so the game cannot freeze
        var state = at(running("alice", "bob"), "alice", 31)
                .apply(new Event.PlayerStuck(GAME, NOW, "alice", 31))
                .apply(new Event.TurnStarted(GAME, NOW, "bob"));
        state = at(state, "bob", 50);

        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "bob", 1, 1),
                new Event.PlayerMoved(GAME, NOW, "bob", 50, 52, MoveReason.NORMAL),
                new Event.TurnStarted(GAME, NOW, "bob")),
                engine.decide(state, new Command.RollDice(GAME, "bob"), dice(1, 1)));
    }

    @Test
    void trapStillAppliesWhileAnotherPlayerIsFree() {
        // alice in the well, but carol is free: bob landing in prison IS trapped
        var state = at(running("alice", "bob", "carol"), "alice", 31)
                .apply(new Event.PlayerStuck(GAME, NOW, "alice", 31))
                .apply(new Event.TurnStarted(GAME, NOW, "bob"));
        state = at(state, "bob", 50);

        var events = engine.decide(state, new Command.RollDice(GAME, "bob"), dice(1, 1));
        assertEquals(new Event.PlayerStuck(GAME, NOW, "bob", 52), events.get(2));
        assertEquals(new Event.TurnStarted(GAME, NOW, "carol"), events.getLast());
    }

    // --- Trap edge cases, found by mutation testing ---

    /** Puts {@code player} on {@code square} and traps them there. */
    private static GameState stuckOn(GameState state, String player, int square) {
        return at(state, player, square).apply(new Event.PlayerStuck(GAME, NOW, player, square));
    }

    @Test
    void aCurrentPlayerWhoIsStuckCannotRoll() {
        // unreachable through decide(), whose turn logic never hands the turn
        // to a trapped player; the guard is pinned anyway
        var state = stuckOn(running("alice", "bob"), "alice", 31);
        assertEquals(List.of(),
                engine.decide(state, new Command.RollDice(GAME, "alice"), NO_DICE));
    }

    @Test
    void twoPlayerSwapInTheWellFreesTheOccupant() {
        // with two players the swap is what keeps someone free, so the
        // last-free-player rule must not waive it
        var state = stuckOn(running("alice", "bob"), "alice", 31)
                .apply(new Event.TurnStarted(GAME, NOW, "bob"));
        state = at(state, "bob", 29);

        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "bob", 1, 1),
                new Event.PlayerMoved(GAME, NOW, "bob", 29, 31, MoveReason.NORMAL),
                new Event.PlayerStuck(GAME, NOW, "bob", 31),
                new Event.PlayerFreed(GAME, NOW, "alice"),
                new Event.TurnStarted(GAME, NOW, "alice")),
                engine.decide(state, new Command.RollDice(GAME, "bob"), dice(1, 1)));
    }

    @Test
    void landingInTheWellDoesNotFreeThePrisoner() {
        // only the occupant of the *same* square is swapped out
        var state = stuckOn(running("alice", "bob", "carol"), "alice", 52)
                .apply(new Event.TurnStarted(GAME, NOW, "bob"));
        state = at(state, "bob", 29);

        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "bob", 1, 1),
                new Event.PlayerMoved(GAME, NOW, "bob", 29, 31, MoveReason.NORMAL),
                new Event.PlayerStuck(GAME, NOW, "bob", 31),
                new Event.TurnStarted(GAME, NOW, "carol")),
                engine.decide(state, new Command.RollDice(GAME, "bob"), dice(1, 1)));
    }

    @Test
    void theInnIsNotASwap() {
        // alice rests at the inn; carol lands there too. Nobody is swapped out:
        // alice is freed only by the rotation, which still skips her once
        var state = stuckOn(running("alice", "bob", "carol"), "alice", 19)
                .apply(new Event.TurnStarted(GAME, NOW, "carol"));
        state = at(state, "carol", 17);

        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "carol", 1, 1),
                new Event.PlayerMoved(GAME, NOW, "carol", 17, 19, MoveReason.NORMAL),
                new Event.PlayerStuck(GAME, NOW, "carol", 19),
                new Event.PlayerFreed(GAME, NOW, "alice"),
                new Event.TurnStarted(GAME, NOW, "bob")),
                engine.decide(state, new Command.RollDice(GAME, "carol"), dice(1, 1)));
    }

    @Test
    void theInnTrapsEvenTheLastFreePlayer() {
        // the waiver is for the well and the prison only: the inn frees by itself
        var state = stuckOn(running("alice", "bob"), "alice", 31)
                .apply(new Event.TurnStarted(GAME, NOW, "bob"));
        state = at(state, "bob", 17);

        var events = engine.decide(state, new Command.RollDice(GAME, "bob"), dice(1, 1));
        assertEquals(new Event.PlayerStuck(GAME, NOW, "bob", 19), events.get(2));
    }

    @Test
    void aPlayerAtTheInnDoesNotCountAsHeldForTheWaiver() {
        // alice only misses a turn at the inn, so bob is not the last free
        // player: falling into the empty well traps him as usual
        var state = stuckOn(running("alice", "bob"), "alice", 19)
                .apply(new Event.TurnStarted(GAME, NOW, "bob"));
        state = at(state, "bob", 29);

        var events = engine.decide(state, new Command.RollDice(GAME, "bob"), dice(1, 1));
        assertEquals(new Event.PlayerStuck(GAME, NOW, "bob", 31), events.get(2));
    }

    @Test
    void aFreePlayerFallingIntoAnEmptyWellIsTrapped() {
        var state = at(running("alice", "bob"), "alice", 29);

        assertEquals(List.of(
                new Event.DiceRolled(GAME, NOW, "alice", 1, 1),
                new Event.PlayerMoved(GAME, NOW, "alice", 29, 31, MoveReason.NORMAL),
                new Event.PlayerStuck(GAME, NOW, "alice", 31),
                new Event.TurnStarted(GAME, NOW, "bob")),
                engine.decide(state, new Command.RollDice(GAME, "alice"), dice(1, 1)));
    }

    @Test
    void fullScriptedGameEndsWithAWinner() {
        var state = running("alice", "bob");
        var random = new Random(7);
        DiceRoller dice = () -> random.nextInt(6) + 1;

        for (int rolls = 0; state.phase() == GameState.Phase.RUNNING; rolls++) {
            assertTrue(rolls < 1_000, "game did not finish within 1000 rolls");
            String player = state.currentPlayer().orElseThrow();
            state = fold(state, engine.decide(state, new Command.RollDice(GAME, player), dice));
        }

        assertEquals(GameState.Phase.FINISHED, state.phase());
        assertTrue(state.winner().isPresent());
        assertEquals(63, state.positions().get(state.winner().orElseThrow()));
    }

    @Test
    void winnerIsDeterministicForAGivenDiceScript() {
        // same seed twice => byte-identical event history (decide is pure)
        assertEquals(playSeeded(11), playSeeded(11));
    }

    private List<Event> playSeeded(long seed) {
        var state = running("alice", "bob");
        var random = new Random(seed);
        DiceRoller dice = () -> random.nextInt(6) + 1;
        var history = new ArrayList<Event>();
        while (state.phase() == GameState.Phase.RUNNING && history.size() < 10_000) {
            var events = engine.decide(
                    state, new Command.RollDice(GAME, state.currentPlayer().orElseThrow()), dice);
            history.addAll(events);
            state = fold(state, events);
        }
        assertInstanceOf(Event.GameWon.class,
                history.stream().filter(Event.GameWon.class::isInstance).findFirst().orElseThrow());
        return history;
    }

    private static GameState fold(GameState state, List<Event> events) {
        for (Event event : events) {
            state = state.apply(event);
        }
        return state;
    }
}
