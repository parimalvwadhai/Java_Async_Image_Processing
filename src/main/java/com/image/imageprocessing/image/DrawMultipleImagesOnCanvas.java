package com.image.imageprocessing.image;

import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The consumer half of the producer/consumer pipeline.
 *
 * <p>Worker threads call {@link #addImageToQueue(ImageData)} to publish finished tiles;
 * an {@link AnimationTimer} running on the JavaFX Application Thread drains the queue and
 * draws. The queue is the thread-safety boundary — {@link GraphicsContext} is touched by
 * the FX thread and nothing else.
 *
 * <p>Two deliberate choices worth noting:
 * <ul>
 *   <li>The {@code BufferedImage -> } JavaFX {@link Image} conversion happens on the
 *       <em>producer</em> thread, inside {@code addImageToQueue}. That conversion copies
 *       every pixel, so it is real work and belongs where the parallelism is. The FX
 *       thread is left with only {@code drawImage}.</li>
 *   <li>{@code handle()} is already invoked on the FX Application Thread, so it needs
 *       neither a spawned thread nor {@code Platform.runLater} to draw. It drains a
 *       time-bounded batch per frame so a large backlog cannot monopolise the frame.</li>
 * </ul>
 */
public class DrawMultipleImagesOnCanvas {

    /**
     * How much of each frame the drain loop may consume. A 60fps pulse is ~16.6ms; leaving
     * headroom keeps the UI responsive while still draining steadily.
     */
    private static final long FRAME_BUDGET_NANOS = 8_000_000L;

    /** Written by pool workers, drained by the FX thread. */
    private final Queue<Tile> queue = new ConcurrentLinkedQueue<>();

    private Canvas canvas;
    private GraphicsContext gc;

    /** A tile that has already been converted to an FX image and is ready to blit. */
    private record Tile(Image image, int x, int y, int width, int height) { }

    /**
     * Called from pool worker threads (and, in synchronous mode, from whichever thread is
     * driving the run). Performs the pixel-copying conversion off the FX thread, then
     * publishes the result.
     */
    public void addImageToQueue(ImageData image){
        Image fxImage = SwingFXUtils.toFXImage(image.getImage(), null);
        queue.offer(new Tile(fxImage, image.getI(), image.getJ(), image.getX(), image.getY()));
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
