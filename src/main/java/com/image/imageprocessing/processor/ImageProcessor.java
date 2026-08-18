package com.image.imageprocessing.processor;

import com.image.imageprocessing.filter.ImageFilter;
import com.image.imageprocessing.image.DrawMultipleImagesOnCanvas;
import com.image.imageprocessing.image.ImageData;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
     * @param num tile size in pixels; must divide both image dimensions evenly, or the
     *            remainder pixels along the right and bottom edges are silently skipped
     * @return elapsed wall-clock time in nanoseconds
     */
    public long processImage(BufferedImage image, int num, ImageFilter imageFilter,
                             DrawMultipleImagesOnCanvas drawFn, ProcessingMode mode){
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
                                     DrawMultipleImagesOnCanvas drawFn){
        int numHorizontalImages = image.getWidth() / num;
        int numVerticalImages = image.getHeight() / num;

        for (int i = 0; i < numHorizontalImages; i++){
            for (int j = 0; j < numVerticalImages; j++){
                BufferedImage subImage = image.getSubimage(i*num, j*num, num, num);
                BufferedImage result = imageFilter.filter(subImage);
                drawFn.addImageToQueue(new ImageData(result, i*num, j*num, num, num));
            }
        }
    }

    /** Parallel path: one task per tile across the pool, then await them all. */
    private void processInParallel(BufferedImage image, int num, ImageFilter imageFilter,
                                   DrawMultipleImagesOnCanvas drawFn){
        int numHorizontalImages = image.getWidth() / num;
        int numVerticalImages = image.getHeight() / num;

        List<Future<ImageData>> futures = new ArrayList<>();

        for (int i = 0; i<numHorizontalImages; i++){
            for(int j=0; j<numVerticalImages; j++){
                BufferedImage subImage = image.getSubimage(i*num, j*num, num, num);
                int finalI = i;
                int finalJ = j;
                Future<ImageData> future = executorService.submit(new Callable<ImageData>() {
                    @Override
                    public ImageData call(){
                        // Safe to read `subImage` concurrently: getSubimage aliases the
                        // source raster rather than copying it, but ImageFilter is a pure
                        // function that only reads the input and allocates a fresh output.
                        BufferedImage result = imageFilter.filter(subImage);
                        ImageData imageData = new ImageData(result, finalI *num, finalJ *num, num, num);
                        // Add to queue immediately when processing is complete
                        drawFn.addImageToQueue(imageData);
                        return imageData;
                    }
                });
                futures.add(future);
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
