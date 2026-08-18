package com.image.imageprocessing.support;

import java.awt.image.BufferedImage;

/** Deterministic synthetic images, so tests never depend on a bundled JPEG. */
public final class TestImages {

    private TestImages(){ }

    /**
     * An image whose every pixel is a distinct function of its coordinates.
     *
     * <p>The pattern matters: with a flat or repeating fill, a tile drawn at the wrong offset,
     * or a tile omitted and left as black, could still produce output that compares equal.
     * Making colour vary with position means any misplacement changes the result.
     */
    public static BufferedImage gradient(int width, int height){
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 7 + y * 3) & 0xFF;
                int g = (x * 13 + y * 5) & 0xFF;
                int b = (x + y * 11) & 0xFF;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    /** Snapshot of every pixel, for asserting that something did not modify an image. */
    public static int[] pixels(BufferedImage image){
        int width = image.getWidth();
        int height = image.getHeight();
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[y * width + x] = image.getRGB(x, y);
            }
        }
        return out;
    }
}
