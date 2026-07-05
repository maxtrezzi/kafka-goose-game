package com.goosegame.client;

import com.goosegame.protocol.Event;

/**
 * Callback surface any UI implements to observe one game. Both methods are
 * invoked on the client's event-loop thread, in log order — implementations
 * should hand off to their own rendering thread if they need one, and must not
 * block for long or they delay subsequent events.
 */
public interface GameListener {

    /** One event of this game, before it is folded into the view. */
    void onEvent(Event event);

    /** The view after folding that event — what a UI should now display. */
    void onViewUpdated(GameView view);
}
