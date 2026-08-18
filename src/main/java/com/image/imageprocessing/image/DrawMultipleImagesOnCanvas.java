package com.image.imageprocessing.image;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The consumer half of the producer/consumer pipeline.
 *
 * <p>Worker threads call {@link #accept(ImageData)} to publish finished tiles; an
 * {@link AnimationTimer} running on the JavaFX Application Thread drains the queue and
 * draws. The queue is the thread-safety boundary — {@link GraphicsContext} is touched by
 * the FX thread and nothing else.
 *
 * <p>This is the UI implementation of {@link TileSink}. The processing engine depends only on
 * that interface, so it has no idea a canvas exists.
 *
 * <p>Two deliberate choices worth noting:
 * <ul>
 *   <li>The {@code BufferedImage -> } JavaFX {@link Image} conversion happens on the
 *       <em>producer</em> thread, inside {@code accept}. That conversion copies
 *       every pixel, so it is real work and belongs where the parallelism is. The FX
 *       thread is left with only {@code drawImage}.</li>
 *   <li>{@code handle()} is already invoked on the FX Application Thread, so it needs
 *       neither a spawned thread nor {@code Platform.runLater} to draw. It drains a
 *       time-bounded batch per frame so a large backlog cannot monopolise the frame.</li>
 * </ul>
 */
public class DrawMultipleImagesOnCanvas implements TileSink {

    /**
     * How much of each frame the drain loop may consume. A 60fps pulse is ~16.6ms; leaving
     * headroom keeps the UI responsive while still draining steadily.
     */
    private static final long FRAME_BUDGET_NANOS = 8_000_000L;

    /**
     * Default bound on the queue. Large enough that the consumer keeps up during ordinary use
     * — a 10px tile blits in microseconds, so a frame's budget drains far more than this —
     * but small enough that memory cannot grow without limit if it ever does fall behind.
     * Override with {@code -Dapp.queue=16} to force backpressure to engage visibly.
     */
    public static final int DEFAULT_CAPACITY = 1024;

    /**
     * How long a publish from the FX thread waits before declaring deadlock. Only ever
     * reached through the deliberately-wrong "Run Sync on FX thread" control.
     */
    private static final long SELF_DEADLOCK_TIMEOUT_MS = 2_000L;

    /**
     * Written by pool workers, drained by the FX thread.
     *
     * <p><strong>Bounded on purpose.</strong> An unbounded queue lets a fast producer allocate
     * without limit while a slow consumer falls behind — the backlog is charged to the heap
     * and eventually to the garbage collector. Bounding it means a worker that outruns the
     * consumer blocks in {@link #accept} until space appears, which is backpressure: the
     * consumer's rate propagates back to the producers instead of being absorbed by memory.
     */
    private final BlockingQueue<Tile> queue;

    private final int capacity;

    private Canvas canvas;
    private GraphicsContext gc;

    public DrawMultipleImagesOnCanvas(){
        this(Integer.getInteger("app.queue", DEFAULT_CAPACITY));
    }

    public DrawMultipleImagesOnCanvas(int capacity){
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /** A tile that has already been converted to an FX image and is ready to blit. */
    private record Tile(Image image, int x, int y, int width, int height) { }

    /**
     * Called from pool worker threads (and, in synchronous mode, from whichever thread is
     * driving the run). Performs the pixel-copying conversion off the FX thread, then
     * publishes the result.
     *
     * <p><strong>This method blocks</strong> when the queue is full. That is the point: it is
     * how the consumer's rate reaches the producers. A blocked worker is idle rather than
     * allocating, which is exactly what should happen when the drawing side cannot keep up.
     *
     * @throws SelfDeadlockException if called on the JavaFX Application Thread and the queue
     *         stays full, because the caller is then also the only thread that could drain it
     */
    @Override
    public void accept(ImageData image){
        Image fxImage = SwingFXUtils.toFXImage(image.getImage(), null);
        Tile tile = new Tile(fxImage, image.getI(), image.getJ(), image.getX(), image.getY());

        if (Platform.isFxApplicationThread()) {
            publishFromConsumerThread(tile);
            return;
        }

        try {
            queue.put(tile);
        } catch (InterruptedException ex) {
            // Interruption during a run means the run is being abandoned. Restore the flag so
            // the executor and any enclosing loop can see it, and abort this tile.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing a tile", ex);
        }
    }

    /**
     * The pathological case: the publishing thread is the draining thread.
     *
     * <p>A plain {@code put()} here would hang forever once the queue filled — the drain runs
     * from {@code AnimationTimer.handle()} on this same thread, and this thread is parked
     * inside {@code put()}. Nothing can break the cycle. Waiting a bounded time and then
     * throwing keeps the failure diagnosable, and keeps the window alive.
     */
    private void publishFromConsumerThread(Tile tile){
        try {
            if (!queue.offer(tile, SELF_DEADLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new SelfDeadlockException(capacity);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SelfDeadlockException(capacity);
        }
    }

    /**
     * Builds the canvas and starts the consumer loop. Call once, on the FX thread; the
     * returned node is the caller's to place in a scene.
     */
    public Canvas createCanvas(int width, int height){
        this.canvas = new Canvas(width, height);
        this.gc = canvas.getGraphicsContext2D();
        this.gc.clearRect(0, 0, width, height);

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                drainFrameBudget();
            }
        }.start();

        return canvas;
    }

    /**
     * Discards anything still queued and wipes the canvas, so a fresh run starts from a
     * blank slate rather than drawing over the previous result. FX thread only.
     */
    public void clear(){
        queue.clear();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    /** True while tiles are still waiting to be drawn. */
    public boolean hasPendingTiles(){
        return !queue.isEmpty();
    }

    /**
     * Draws queued tiles until the queue is empty or this frame's time budget is spent.
     * Runs on the JavaFX Application Thread. The budget is checked <em>after</em> drawing
     * so that at least one tile always makes progress.
     */
    private void drainFrameBudget(){
        long deadline = System.nanoTime() + FRAME_BUDGET_NANOS;
        Tile tile;
        while ((tile = queue.poll()) != null) {
            gc.drawImage(tile.image(), tile.x(), tile.y(), tile.width(), tile.height());
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }
}
