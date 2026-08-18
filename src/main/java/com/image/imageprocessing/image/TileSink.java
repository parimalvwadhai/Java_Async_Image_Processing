package com.image.imageprocessing.image;

/**
 * The destination for finished tiles — the seam between the processing engine and whatever
 * consumes its output.
 *
 * <p>{@code ImageProcessor} publishes through this interface and therefore knows nothing about
 * canvases, JavaFX, or rendering at all. That inversion is what lets the same engine drive
 * three very different consumers: {@link DrawMultipleImagesOnCanvas} for the live UI, a
 * counting no-op sink for benchmarking (where queueing and pixel conversion would only add
 * noise to the measurement), and recording sinks in the test suite.
 *
 * <p>It also makes the engine testable at all. The canvas implementation converts each tile
 * with {@code SwingFXUtils.toFXImage}, which requires a running JavaFX toolkit; a unit test
 * of the tiling logic would otherwise have to boot the whole UI to observe a single tile.
 *
 * <h2>Threading contract</h2>
 * <p>Implementations <strong>must be thread-safe</strong>. In {@link
 * com.image.imageprocessing.processor.ProcessingMode#ASYNCHRONOUS} mode this method is called
 * concurrently from every pool worker; in {@code SYNCHRONOUS} mode it is called from whichever
 * single thread is driving the run. Implementations may block — that is how backpressure
 * reaches the producers — so callers must never invoke it while holding a lock that the
 * consumer needs to make progress.
 */
@FunctionalInterface
public interface TileSink {

    /**
     * Publishes one finished tile. Called from producer threads, possibly many at once.
     *
     * @param tile the filtered tile together with its origin and dimensions. Edge tiles are
     *             smaller than the nominal tile size, so implementations must take the
     *             dimensions from the tile rather than assuming a square.
     */
    void accept(ImageData tile);
}
