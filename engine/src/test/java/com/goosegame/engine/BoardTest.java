package com.goosegame.engine;

import com.goosegame.engine.Board.Move;
import com.goosegame.protocol.MoveReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardTest {

    @Test
    void plainMoveIsOneSegment() {
        assertEquals(List.of(new Move(2, 7, MoveReason.NORMAL)), Board.resolve(2, 5));
    }

    @Test
    void bridgeJumpsToTwelve() {
        assertEquals(List.of(
                new Move(1, 6, MoveReason.NORMAL),
                new Move(6, 12, MoveReason.BRIDGE)),
                Board.resolve(1, 5));
    }

    @Test
    void gooseRepeatsTheRollAndChains() {
        // 1 + 4 = 5 (goose) -> 9 (goose) -> 13
        assertEquals(List.of(
                new Move(1, 5, MoveReason.NORMAL),
                new Move(5, 9, MoveReason.GOOSE),
                new Move(9, 13, MoveReason.GOOSE)),
                Board.resolve(1, 4));
    }

    @Test
    void nineFromStartChainsAcrossEveryGooseToTheWin() {
        var moves = Board.resolve(0, 9);
        assertEquals(new Move(0, 9, MoveReason.NORMAL), moves.getFirst());
        assertEquals(63, moves.getLast().to());
        assertEquals(7, moves.size()); // 9, 18, 27, 36, 45, 54, 63
    }

    @Test
    void mazeSendsBackToThirtyNine() {
        assertEquals(List.of(
                new Move(40, 42, MoveReason.NORMAL),
                new Move(42, 39, MoveReason.MAZE)),
                Board.resolve(40, 2));
    }

    @Test
    void deathSendsBackToOne() {
        assertEquals(List.of(
                new Move(56, 58, MoveReason.NORMAL),
                new Move(58, 1, MoveReason.DEATH)),
                Board.resolve(56, 2));
    }

    @Test
    void overshootBouncesBackByTheExcess() {
        assertEquals(List.of(
                new Move(60, 63, MoveReason.NORMAL),
                new Move(63, 61, MoveReason.BOUNCE)),
                Board.resolve(60, 5));
    }

    @Test
    void gooseAfterABounceContinuesBackwards() {
        // 60 + 7 = 67: bounce to 59 (goose), then 7 further back into the prison
        assertEquals(List.of(
                new Move(60, 63, MoveReason.NORMAL),
                new Move(63, 59, MoveReason.BOUNCE),
                new Move(59, 52, MoveReason.GOOSE)),
                Board.resolve(60, 7));
        assertTrue(Board.traps(52));
    }

    @Test
    void exactLandingOnSixtyThreeIsFinal() {
        assertEquals(List.of(new Move(60, 63, MoveReason.NORMAL)), Board.resolve(60, 3));
    }

    @Test
    void trapSquaresDoNotMoveTheToken() {
        assertEquals(List.of(new Move(17, 19, MoveReason.NORMAL)), Board.resolve(17, 2));
        assertEquals(List.of(new Move(29, 31, MoveReason.NORMAL)), Board.resolve(29, 2));
        assertEquals(List.of(new Move(50, 52, MoveReason.NORMAL)), Board.resolve(50, 2));
    }

    @Test
    void argumentsOutsideTheBoardAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Board.resolve(-1, 3));
        assertThrows(IllegalArgumentException.class, () -> Board.resolve(64, 3));
        assertThrows(IllegalArgumentException.class, () -> Board.resolve(5, 0)); // would goose-chain forever
        assertThrows(IllegalArgumentException.class, () -> Board.resolve(5, 13));
    }

    @Test
    void trapClassification() {
        assertTrue(Board.traps(Board.INN));
        assertTrue(Board.traps(Board.WELL));
        assertTrue(Board.traps(Board.PRISON));
        assertFalse(Board.traps(20));
        assertFalse(Board.holdsUntilReplaced(Board.INN));
        assertTrue(Board.holdsUntilReplaced(Board.WELL));
        assertTrue(Board.holdsUntilReplaced(Board.PRISON));
    }
}
