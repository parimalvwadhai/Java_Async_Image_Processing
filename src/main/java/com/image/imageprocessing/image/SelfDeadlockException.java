package com.image.imageprocessing.image;

/**
 * Thrown when a tile is published from the same thread that drains the queue — a producer and
 * consumer that are one thread, which cannot make progress once the queue is full.
 *
 * <p>This is a genuine deadlock, not merely a slow path. The JavaFX Application Thread drains
 * the queue from {@code AnimationTimer.handle()}; if that same thread blocks inside
 * {@link DrawMultipleImagesOnCanvas#accept(ImageData)} waiting for space, the timer can never
 * fire, so space can never appear, so the wait never ends. Nothing external will break the
 * cycle.
 *
 * <p>The application reaches this state only through the deliberately-wrong "Run Sync on FX
 * thread" control, which exists to demonstrate the failure. Rather than hanging the JVM
 * outright, {@code accept} waits a bounded time and then throws this, so the deadlock is
 * named and the window survives to demonstrate anything else.
 *
 * <p>Note that this is a different failure from the one the same control demonstrates with an
 * unbounded queue. There, the FX thread is merely <em>busy</em>: no tile is drawn until the
 * work finishes, but it always finishes. That is starvation of the event loop. This is
 * deadlock — a circular wait that does not resolve on its own.
 */
public class SelfDeadlockException extends IllegalStateException {

    public SelfDeadlockException(int capacity) {
        super("Self-deadlock: a tile was published from the JavaFX Application Thread, which is "
                + "also the only thread that drains the queue. The queue is full (capacity "
                + capacity + ") and the drain can never run, because this thread is blocked "
                + "here. Processing must not be run on the FX thread.");
    }
}
