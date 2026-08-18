package com.image.imageprocessing.processor;

import com.image.imageprocessing.filter.GreyScaleFilter;
import com.image.imageprocessing.support.RecordingSink;
import com.image.imageprocessing.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The concurrency test proper: does running the work across N threads produce the same answer
 * as running it on one?
 *
 * <p>What makes this worth writing is the shared raster. {@code getSubimage} does not copy —
 * it returns a view onto the parent's {@code DataBuffer} — so in asynchronous mode every
 * worker is reading one shared array at once. That is safe only because {@code ImageFilter}
 * implementations are pure and allocate a fresh output. If someone later writes a filter that
 * modifies its input in place, nothing in the type system stops them, the application will
 * still appear to work, and this test is what fails.
 *
 * <p>A passing run does not prove the absence of a race — no test can, since a race may simply
 * not interleave badly on this run. Repeating the comparison raises the odds of catching one,
 * which is the most a deterministic test suite can offer here.
 */
class SyncAsyncEquivalenceTest {

    private static final int WIDTH = 1023;
    private static final int HEIGHT = 769;
    private static final int TILE = 32;

    private ImageProcessor processor;

    @BeforeEach
    void setUp(){
        processor = new ImageProcessor();
    }

    @AfterEach
    void tearDown(){
        processor.shutdown();
    }

    @RepeatedTest(value = 5, name = "run {currentRepetition} of {totalRepetitions}")
    @DisplayName("parallel output is identical to single-threaded output")
    void parallelMatchesSequential(){
        BufferedImage source = TestImages.gradient(WIDTH, HEIGHT);

        RecordingSink sequential = new RecordingSink();
        RecordingSink parallel = new RecordingSink();

        processor.processImage(source, TILE, new GreyScaleFilter(), sequential, ProcessingMode.SYNCHRONOUS);
        processor.processImage(source, TILE, new GreyScaleFilter(), parallel, ProcessingMode.ASYNCHRONOUS);

        assertEquals(sequential.count(), parallel.count(), "tile count differs between modes");

        // Reassembled by coordinate, not by arrival order - the parallel run completes tiles
        // in whatever order the pool happens to finish them, and that order is not stable.
        assertArrayEquals(
                TestImages.pixels(sequential.reassemble(WIDTH, HEIGHT)),
                TestImages.pixels(parallel.reassemble(WIDTH, HEIGHT)),
                "parallel run produced different pixels from the sequential run");
    }

    @Test
    @DisplayName("processing does not modify the source image")
    void sourceImageIsLeftUntouched(){
        BufferedImage source = TestImages.gradient(WIDTH, HEIGHT);
        int[] before = TestImages.pixels(source);

        processor.processImage(source, TILE, new GreyScaleFilter(), new RecordingSink(),
                ProcessingMode.ASYNCHRONOUS);

        assertArrayEquals(before, TestImages.pixels(source),
                "the source raster was written to during processing - filters must be pure, "
                        + "because every tile is a view onto this one buffer");
    }
}
