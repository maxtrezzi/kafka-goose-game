package com.goosegame.protocol;

/** Why a {@link Event.PlayerMoved} landed where it did. */
public enum MoveReason {
    /** Plain dice move. */
    NORMAL,
    /** Landed on a goose square: move forward again by the same roll. */
    GOOSE,
    /** The Bridge (6): jump to 12. */
    BRIDGE,
    /** Overshot 63: bounce back by the excess. */
    BOUNCE,
    /** The Maze (42): sent back to 39. */
    MAZE,
    /** Death (58): back to square 1. */
    DEATH
}
