package com.image.imageprocessing.processor;

import com.image.imageprocessing.filter.ImageFilter;
import com.image.imageprocessing.image.ImageData;
import com.image.imageprocessing.image.TileSink;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ImageProcessor {

    private final ExecutorService executorService;
    private final int poolSize;

    public ImageProcessor(){
        // Filtering is pure CPU work with no blocking I/O, so the useful degree of
        // parallelism is bounded by core count. A larger pool would only add context
        // switching and thread stacks without improving throughput.
        this.poolSize = Runtime.getRuntime().availableProcessors();
        this.executorService = Executors.newFixedThreadPool(poolSize, daemonThreadFactory());
    }

    public int getPoolSize(){
        return poolSize;
    }

    /**
     * Filters {@code image} tile by tile, publishing each finished tile to {@code drawFn}
     * as soon as it is ready so the UI can render progressively.
     *
     * <p><strong>This method blocks</strong> until every tile is complete, in both modes. It
     * must therefore never be called on the JavaFX Application Thread — doing so freezes the
     * event loop and stops the consumer from drawing anything until all work has finished.
     * (The application deliberately offers one control that violates this rule, in order to
     * demonstrate the resulting freeze.)
     *
     * @param num tile size in pixels. It need not divide the image dimensions evenly — the
     *            grid is sized by ceiling division and the tiles along the right and bottom
     *            edges are clamped to whatever remains, so every pixel is covered exactly once
     * @param drawFn where finished tiles are published. Declared as the {@link TileSink}
     *               interface rather than the canvas class, so this engine has no dependency
     *               on JavaFX and the same code path serves the UI, the benchmark, and the tests
     * @return elapsed wall-clock time in nanoseconds
     */
    public long processImage(BufferedImage image, int num, ImageFilter imageFilter,
                             TileSink drawFn, ProcessingMode mode){
        long startedAt = System.nanoTime();
        switch (mode) {
            case SYNCHRONOUS  -> processSequentially(image, num, imageFilter, drawFn);
            case ASYNCHRONOUS -> processInParallel(image, num, imageFilter, drawFn);
        }
        return System.nanoTime() - startedAt;
    }

    /**
     * Baseline path: every tile filtered in order on the calling thread. Does exactly the
     * same total work as the parallel path — same tiling, same filter, same conversion via
     * {@code addImageToQueue} — so the two timings differ only by parallelism.
     */
    private void processSequentially(BufferedImage image, int num, ImageFilter imageFilter,
                                     TileSink drawFn){
        int columns = Math.ceilDiv(image.getWidth(), num);
        int rows = Math.ceilDiv(image.getHeight(), num);

        for (int i = 0; i < columns; i++){
            for (int j = 0; j < rows; j++){
                int x = i * num;
                int y = j * num;
                filterAndPublish(tileAt(image, x, y, num), x, y, imageFilter, drawFn);
            }
        }
    }

    /** Parallel path: one task per tile across the pool, then await them all. */
    private void processInParallel(BufferedImage image, int num, ImageFilter imageFilter,
                                   TileSink drawFn){
        int columns = Math.ceilDiv(image.getWidth(), num);
        int rows = Math.ceilDiv(image.getHeight(), num);

        List<Future<ImageData>> futures = new ArrayList<>();

        for (int i = 0; i < columns; i++){
            for (int j = 0; j < rows; j++){
                int x = i * num;
                int y = j * num;
                // The sub-image is carved on this thread, not inside the task, so the only
                // thing the workers touch concurrently is the shared raster — and they only
                // read it. Safe because ImageFilter is a pure function that allocates a
                // fresh output rather than writing back into its input.
                BufferedImage tile = tileAt(image, x, y, num);
                futures.add(executorService.submit(
                        () -> filterAndPublish(tile, x, y, imageFilter, drawFn)));
            }
        }

        for (Future<ImageData> future : futures) {
            try {
                future.get();
            } catch (ExecutionException ex) {
                // Report the underlying cause with its stack trace. ExecutionException's
                // own getMessage() is usually null and tells you nothing.
                System.err.println("Error processing tile:");
                ex.getCause().printStackTrace();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The tile whose top-left corner is ({@code x}, {@code y}), clamped so that a tile on the
     * right or bottom edge never runs past the image bounds.
     *
     * <p>This clamp, together with the ceiling division that sizes the grid, is what stops the
     * remainder strips from being dropped when the tile size does not divide the image evenly.
     * Plain {@code width / num} produced a grid that stopped short, so up to {@code num - 1}
     * columns and rows of pixels were never filtered <em>or</em> drawn — a silent correctness
     * bug that only showed up as an unfiltered band on images whose dimensions happened not to
     * be a multiple of the tile size.
     */
    private static BufferedImage tileAt(BufferedImage image, int x, int y, int num){
        int width = Math.min(num, image.getWidth() - x);
        int height = Math.min(num, image.getHeight() - y);
        return image.getSubimage(x, y, width, height);
    }

    /**
     * Filters one tile and publishes it to the consumer immediately, so the UI can draw it
     * without waiting for the rest of the grid. Shared by both modes so that the work being
     * timed is identical and the comparison stays honest.
     *
     * <p>The tile carries its own dimensions rather than assuming {@code num} square, because
     * edge tiles are smaller.
     */
    private ImageData filterAndPublish(BufferedImage tile, int x, int y, ImageFilter imageFilter,
                                       TileSink drawFn){
        BufferedImage result = imageFilter.filter(tile);
        ImageData imageData = new ImageData(result, x, y, tile.getWidth(), tile.getHeight());
        drawFn.accept(imageData);
        return imageData;
    }

    /** Releases the pool. Call once the processor is no longer needed. */
    public void shutdown(){
        executorService.shutdown();
    }

    /**
     * Daemon threads so a forgotten {@link #shutdown()} can never keep the JVM alive after
     * the window closes.
     */
    private static ThreadFactory daemonThreadFactory(){
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "image-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

}
