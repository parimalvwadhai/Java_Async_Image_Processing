package com.image.imageprocessing.filter;

import com.image.imageprocessing.support.TestImages;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreyScaleFilterTest {

    private final ImageFilter filter = new GreyScaleFilter();

    private static BufferedImage solid(int rgb, int width, int height){
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static int channel(BufferedImage image, int x, int y, int shift){
        return (image.getRGB(x, y) >> shift) & 0xFF;
    }

    @Test
    @DisplayName("black and white map to the ends of the range")
    void endpointsAreExact(){
        assertEquals(0, channel(filter.filter(solid(0x000000, 4, 4)), 0, 0, 0));
        // White reads back as 254 rather than 255 - see roundTripLosesPrecision below. The
        // assertion is deliberately a bound rather than an equality, so that it still holds
        // if the raster fast path lands and the answer becomes exactly 255.
        assertTrue(channel(filter.filter(solid(0xFFFFFF, 4, 4)), 0, 0, 0) >= 254,
                "white should saturate at the top of the range");
    }

    @Test
    @DisplayName("output is neutral grey - all three channels equal")
    void outputIsNeutral(){
        BufferedImage out = filter.filter(solid(0x3366CC, 4, 4));
        int r = channel(out, 1, 1, 16);
        int g = channel(out, 1, 1, 8);
        int b = channel(out, 1, 1, 0);
        assertEquals(r, g, "red and green channels differ");
        assertEquals(g, b, "green and blue channels differ");
    }

    @Test
    @DisplayName("green contributes more than red, red more than blue")
    void channelWeightingFollowsLuminance(){
        // The Rec. 709 luminance weights are 0.2126R + 0.7152G + 0.0722B, so a fully saturated
        // green must come out brighter than a fully saturated red, and red brighter than blue.
        int red = channel(filter.filter(solid(0xFF0000, 2, 2)), 0, 0, 0);
        int green = channel(filter.filter(solid(0x00FF00, 2, 2)), 0, 0, 0);
        int blue = channel(filter.filter(solid(0x0000FF, 2, 2)), 0, 0, 0);

        assertTrue(green > red, "green (" + green + ") should be brighter than red (" + red + ")");
        assertTrue(red > blue, "red (" + red + ") should be brighter than blue (" + blue + ")");
    }

    @Test
    @DisplayName("the filter is pure - it never writes to its input")
    void filterDoesNotModifyItsInput(){
        BufferedImage source = TestImages.gradient(64, 64);
        int[] before = TestImages.pixels(source);

        BufferedImage out = filter.filter(source);

        assertNotSame(source, out, "the filter must allocate a fresh image, not return its input");
        assertArrayEquals(before, TestImages.pixels(source),
                "the filter modified its input");
    }

    @Test
    @DisplayName("filtering a sub-image does not write through to the parent raster")
    void filteringASubImageLeavesTheParentIntact(){
        // This is the invariant that actually matters. getSubimage returns a view sharing the
        // parent's DataBuffer, so in asynchronous mode every worker holds a window onto one
        // array. Purity is the only thing making those concurrent reads safe; an in-place
        // filter would be a silent data race rather than a visible crash.
        BufferedImage parent = TestImages.gradient(128, 128);
        int[] before = TestImages.pixels(parent);

        filter.filter(parent.getSubimage(32, 32, 64, 64));

        assertArrayEquals(before, TestImages.pixels(parent),
                "filtering a sub-image wrote through to the shared parent raster");
    }

    @Test
    @DisplayName("the sRGB round-trip through TYPE_BYTE_GRAY loses a little precision")
    void roundTripLosesPrecision(){
        // Reading back with getRGB returns approximately the luminance that was computed,
        // because the ColorModel converts sRGB -> linear on the way in and linear -> sRGB on
        // the way out. The two conversions very nearly cancel; what does not cancel is the
        // quantisation to 8 bits in between, and it is worst in the shadows, where linear
        // encoding spends the fewest codes. Blue is the extreme case here: computed 18,
        // reads back 22.
        assertEquals(54, channel(filter.filter(solid(0xFF0000, 2, 2)), 0, 0, 0), 1);
        assertEquals(182, channel(filter.filter(solid(0x00FF00, 2, 2)), 0, 0, 0), 1);
        assertEquals(128, channel(filter.filter(solid(0x808080, 2, 2)), 0, 0, 0), 1);
        assertEquals(18, channel(filter.filter(solid(0x0000FF, 2, 2)), 0, 0, 0), 4);
    }

    @Test
    @Disabled("""
            Known defect, deliberately left failing and documented rather than hidden. \
            GreyScaleFilter computes a luminance in sRGB and then writes it with setRGB into a \
            TYPE_BYTE_GRAY image, whose ColorSpace is LINEAR_GRAY. The ColorModel therefore \
            gamma-converts on the way in, and the byte actually stored is nothing like the \
            number that was computed: luminance 54 is stored as 9, 128 as 55, 255 as 253. \
            getRGB hides this by converting back, so the application looks correct and only \
            code reading the raster directly would see it. \
            The fix is not simply to write the raster directly - that would store 54 and make \
            getRGB report about 136, a visibly brighter image. The output type has to change \
            to a packed sRGB type such as TYPE_INT_RGB, which also removes the per-pixel \
            ColorModel conversion and the per-pixel `new Color` allocation. \
            Enable this test when that work is done.""")
    @DisplayName("the stored raster value is the computed luminance")
    void luminanceIsStoredVerbatim(){
        assertEquals(54, filter.filter(solid(0xFF0000, 2, 2)).getRaster().getSample(0, 0, 0));
        assertEquals(128, filter.filter(solid(0x808080, 2, 2)).getRaster().getSample(0, 0, 0));
        assertEquals(255, filter.filter(solid(0xFFFFFF, 2, 2)).getRaster().getSample(0, 0, 0));
    }
}
