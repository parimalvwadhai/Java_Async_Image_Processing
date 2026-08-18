package com.image.imageprocessing.processor;

import com.image.imageprocessing.filter.GreyScaleFilter;
import com.image.imageprocessing.image.ImageData;
import com.image.imageprocessing.support.RecordingSink;
import com.image.imageprocessing.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The regression suite for the edge-tile bug: the grid was sized with integer division, so the
 * remainder strips along the right and bottom edges were never filtered or drawn.
 *
 * <p>The bug survived as long as it did because nothing ever checked coverage. Both the buggy
 * and the fixed version distributed their tiles across the pool perfectly reliably; the
 * concurrency was never wrong. Only the answer was. That is what these tests assert.
 */
class TileCoverageTest {

    private ImageProcessor processor;

    @BeforeEach
    void setUp(){
        processor = new ImageProcessor();
    }

    @AfterEach
    void tearDown(){
        processor.shutdown();
    }

    @ParameterizedTest(name = "{0}x{1} at tile size {2}, {3} mode")
    @CsvSource({
            // Dimensions that divide evenly - the case the original code handled.
            "400, 300, 100, SYNCHRONOUS",
            "400, 300, 100, ASYNCHRONOUS",
            // The awkward case: 1023x769 at tile size 100 leaves a 23px column and a 69px row.
            "1023, 769, 100, SYNCHRONOUS",
            "1023, 769, 100, ASYNCHRONOUS",
            // A tile larger than the image: the grid is 1x1 and the single tile is clamped to
            // the image, not to the nominal tile size.
            "50, 40, 512, SYNCHRONOUS",
            // Prime dimensions, so nothing divides anything.
            "97, 89, 10, ASYNCHRONOUS",
            // Tile size 1: one task per pixel. Slow, but it is the degenerate boundary.
            "37, 29, 1, ASYNCHRONOUS",
    })
    @DisplayName("every pixel is covered by exactly one tile")
    void everyPixelIsCoveredExactlyOnce(int width, int height, int tileSize, ProcessingMode mode){
        BufferedImage source = TestImages.gradient(width, height);
        RecordingSink sink = new RecordingSink();

        processor.processImage(source, tileSize, new GreyScaleFilter(), sink, mode);

        int[][] coverage = new int[height][width];
        for (ImageData tile : sink.tiles()) {
            for (int dy = 0; dy < tile.getY(); dy++) {
                for (int dx = 0; dx < tile.getX(); dx++) {
                    coverage[tile.getJ() + dy][tile.getI() + dx]++;
                }
            }
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(1, coverage[y][x],
                        () -> "pixel coverage count at the position reported above");
            }
        }
    }

    @Test
    @DisplayName("the tile grid is sized by ceiling division, not integer division")
    void gridIsSizedByCeilingDivision(){
        // 1023/100 = 10 and 769/100 = 7 under integer division, giving 70 tiles and silently
        // discarding a 23px column and a 69px row. Ceiling division gives 11 x 8 = 88.
        BufferedImage source = TestImages.gradient(1023, 769);
        RecordingSink sink = new RecordingSink();

        processor.processImage(source, 100, new GreyScaleFilter(), sink, ProcessingMode.SYNCHRONOUS);

        assertEquals(88, sink.count(), "expected an 11x8 grid including both remainder strips");
    }

    @Test
    @DisplayName("edge tiles are clamped to the image, so they are not square")
    void edgeTilesAreClampedRatherThanSquare(){
        BufferedImage source = TestImages.gradient(1023, 769);
        RecordingSink sink = new RecordingSink();

        processor.processImage(source, 100, new GreyScaleFilter(), sink, ProcessingMode.SYNCHRONOUS);

        ImageData corner = sink.tiles().stream()
                .filter(tile -> tile.getI() == 1000 && tile.getJ() == 700)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tile at the bottom-right corner"));

        // Without the clamp, getSubimage(1000, 700, 100, 100) runs past the raster and throws
        // RasterFormatException - which is why ceilDiv and the clamp had to land together.
        assertEquals(23, corner.getX(), "width of the final column");
        assertEquals(69, corner.getY(), "height of the final row");
    }
}
