package com.goosegame.engine;

import com.goosegame.protocol.MoveReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The classic 63-square Game of the Goose board: pure movement rules only, no
 * turn or player state.
 *
 * <p>Rules encoded here: geese repeat the last movement, the bridge (6) jumps
 * to 12, the maze (42) sends back to 39, death (58) sends back to 1, and a move
 * past 63 bounces back by the excess. The inn (19), well (31) and prison (52)
 * do not move the token — they trap the player, which is turn logic and lives
 * in {@link GameEngine}.
 *
 * <p>A goose hop repeats the movement <em>in its current direction</em>: after
 * a bounce off 63 the direction is reversed, so a goose reached while bouncing
 * back sends the token further backwards. This is what makes every chain
 * finite — forward hops strictly climb until at most one reflection, backward
 * hops strictly descend.
 */
public final class Board {

    public static final int LAST_SQUARE = 63;
    public static final int BRIDGE = 6;
    public static final int BRIDGE_TARGET = 12;
    public static final int INN = 19;
    public static final int WELL = 31;
    public static final int MAZE = 42;
    public static final int MAZE_TARGET = 39;
    public static final int PRISON = 52;
    public static final int DEATH = 58;
    public static final int DEATH_TARGET = 1;

    private static final Set<Integer> GEESE =
            Set.of(5, 9, 14, 18, 23, 27, 32, 36, 41, 45, 50, 54, 59);

    private Board() {
    }

    /** One segment of a token's movement, in board-rule order. */
    public record Move(int from, int to, MoveReason reason) {
    }

    /**
     * Resolves one dice roll from {@code from} into every movement segment it
     * triggers, in order. The last segment's {@code to} is where the token
     * finally rests; the caller decides what that square means (win, trap,
     * nothing).
     *
     * @throws IllegalArgumentException if {@code from} is not a board square
     *                                  (0-63) or {@code roll} is not a two-dice
     *                                  total (1-12)
     */
    public static List<Move> resolve(int from, int roll) {
        if (from < 0 || from > LAST_SQUARE) {
            throw new IllegalArgumentException("from must be 0-63, got: " + from);
        }
        if (roll < 1 || roll > 12) { // two dice; a step of 0 would make goose chains spin forever
            throw new IllegalArgumentException("roll must be 1-12, got: " + roll);
        }
        var moves = new ArrayList<Move>();
        var cursor = move(moves, from, roll, MoveReason.NORMAL);
        while (true) {
            int pos = cursor.pos();
            if (pos == BRIDGE) {
                moves.add(new Move(pos, BRIDGE_TARGET, MoveReason.BRIDGE));
                cursor = new Cursor(BRIDGE_TARGET, cursor.step());
            } else if (pos == MAZE) {
                moves.add(new Move(pos, MAZE_TARGET, MoveReason.MAZE));
                cursor = new Cursor(MAZE_TARGET, cursor.step());
            } else if (pos == DEATH) {
                moves.add(new Move(pos, DEATH_TARGET, MoveReason.DEATH));
                cursor = new Cursor(DEATH_TARGET, cursor.step());
            } else if (GEESE.contains(pos)) {
                cursor = move(moves, pos, cursor.step(), MoveReason.GOOSE);
            } else {
                return List.copyOf(moves);
            }
        }
    }

    /** Squares where the player gets stuck instead of resting free. */
    public static boolean traps(int square) {
        return square == INN || square == WELL || square == PRISON;
    }

    /** The well and the prison hold a player until another lands there and takes the place. */
    public static boolean holdsUntilReplaced(int square) {
        return square == WELL || square == PRISON;
    }

    /** Position plus the signed step a goose square would repeat. */
    private record Cursor(int pos, int step) {
    }

    private static Cursor move(List<Move> moves, int pos, int step, MoveReason reason) {
        int target = pos + step;
        if (target > LAST_SQUARE) {
            moves.add(new Move(pos, LAST_SQUARE, reason));
            int reflected = 2 * LAST_SQUARE - target;
            moves.add(new Move(LAST_SQUARE, reflected, MoveReason.BOUNCE));
            return new Cursor(reflected, -Math.abs(step));
        }
        if (target < 0) {
            // Unreachable on the classic board (backward goose chains stop by
            // square 42), but guarantees resolve() can never emit an invalid square.
            target = 0;
        }
        moves.add(new Move(pos, target, reason));
        return new Cursor(target, step);
    }
}
