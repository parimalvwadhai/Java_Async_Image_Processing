package com.image.imageprocessing.processor;

import com.image.imageprocessing.filter.GreyScaleFilter;
import com.image.imageprocessing.image.ImageData;
import com.image.imageprocessing.image.TileSink;
import com.image.imageprocessing.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Backpressure: what happens when the consumer cannot keep up.
 *
 * <p>Bounding the draw queue means {@code TileSink.accept} can now block, and that is a real
 * change in the contract the processor operates under. Two things could go wrong and neither
 * would be obvious by looking at the code:
 *
 * <ul>
 *   <li><strong>Deadlock.</strong> In the parallel path every worker could end up parked in
 *       {@code put()} at once. That is fine only because the consumer is an independent thread;
 *       if the drain ever depended on a worker finishing, the pool would wedge. The timeout on
 *       these tests is what catches that.</li>
 *   <li><strong>Silent loss.</strong> If blocking were ever "handled" by dropping a tile — a
 *       bare {@code offer()} whose false return is ignored, say — the image would come out with
 *       holes in it and nothing would report an error. Counting what arrives catches that.</li>
 * </ul>
 *
 * <p>The sink here is deliberately far too small: 8 slots for 256 tiles, so producers spend most
 * of the run blocked. That is the point. A capacity large enough never to fill would exercise
 * none of this.
 */
class BackpressureTest {

    private static final int SIZE = 512;
    private static final int TILE = 32;
    private static final int EXPECTED_TILES = (SIZE / TILE) * (SIZE / TILE);   // 256
    private static final int SINK_CAPACITY = 8;

    private ImageProcessor processor;

    @BeforeEach
    void setUp(){
        processor = new ImageProcessor();
    }

    @AfterEach
    void tearDown(){
        processor.shutdown();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProcessingMode.class)
    @DisplayName("a sink far too small to hold the run still receives every tile, and nothing wedges")
    void blockingSinkThrottlesProducersWithoutLosingTiles(ProcessingMode mode){
        BufferedImage source = TestImages.gradient(SIZE, SIZE);
        BlockingQueue<ImageData> handoff = new ArrayBlockingQueue<>(SINK_CAPACITY);
        AtomicInteger drained = new AtomicInteger();
        AtomicBoolean producersFinished = new AtomicBoolean();

        Thread consumer = new Thread(() -> {
            while (!producersFinished.get() || !handoff.isEmpty()) {
                try {
                    if (handoff.poll(50, TimeUnit.MILLISECONDS) != null) {
                        drained.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "test-consumer");
        consumer.setDaemon(true);
        consumer.start();

        TileSink blockingSink = tile -> {
            try {
                handoff.put(tile);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while publishing", ex);
            }
        };

        // If the producers and the consumer can ever deadlock against each other, this is where
        // it shows up - as a hang rather than as a wrong answer. Preemptive timeout so a wedged
        // run fails the build instead of stalling it.
        assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> processor.processImage(source, TILE, new GreyScaleFilter(), blockingSink, mode),
                "processing did not finish - producers and consumer are deadlocked");

        producersFinished.set(true);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> consumer.join());

        assertEquals(EXPECTED_TILES, drained.get(),
                "tiles were lost while the sink was applying backpressure");
    }
}
