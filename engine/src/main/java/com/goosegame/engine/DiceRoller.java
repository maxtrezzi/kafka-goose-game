package com.goosegame.engine;

/**
 * Source of single die rolls. Tests inject scripted values; the server injects
 * a {@code SecureRandom}-backed implementation so dice are only ever rolled
 * server-side.
 */
@FunctionalInterface
public interface DiceRoller {

    /** @return the value of one die, from 1 to 6 inclusive */
    int roll();
}
