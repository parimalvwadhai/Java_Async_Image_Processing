package com.image.imageprocessing.support;

import com.image.imageprocessing.image.ImageData;
import com.image.imageprocessing.image.TileSink;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link TileSink} that keeps every tile it is given, so a test can inspect what the
 * processor actually produced.
 *
 * <p>This is the reason {@code TileSink} exists. The production sink converts each tile with
 * {@code SwingFXUtils.toFXImage}, which needs a running JavaFX toolkit; without an interface
 * to substitute, testing the tiling logic would mean booting a UI.
 *
 * <p>Backed by a {@link CopyOnWriteArrayList} because in asynchronous mode every pool worker
 * appends concurrently, and a plain {@code ArrayList} would corrupt its internal array or drop
 * entries under that access pattern. Copy-on-write is a poor choice for a hot path — every add
 * copies the whole backing array — but tile counts here are in the thousands and the
 * alternative (a lock, or a concurrent queue plus a drain step) buys nothing for a test.
 */
public final class RecordingSink implements TileSink {

    private final List<ImageData> tiles = new CopyOnWriteArrayList<>();

    @Override
    public void accept(ImageData tile){
        tiles.add(tile);
    }

    public List<ImageData> tiles(){
        return tiles;
    }

    public int count(){
        return tiles.size();
    }

    /**
     * Reassembles the recorded tiles into a single image of the given size.
     *
     * <p>Tiles arrive in a nondeterministic order in asynchronous mode, so anything comparing
     * output between modes has to reassemble by coordinate rather than by arrival order. Each
     * tile carries its own origin and dimensions, which is what makes that possible — and what
     * makes non-square edge tiles work.
     */
    public BufferedImage reassemble(int width, int height){
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (ImageData tile : tiles) {
            BufferedImage image = tile.getImage();
            for (int dy = 0; dy < tile.getY(); dy++) {
                for (int dx = 0; dx < tile.getX(); dx++) {
                    canvas.setRGB(tile.getI() + dx, tile.getJ() + dy, image.getRGB(dx, dy));
                }
            }
        }
        return canvas;
    }
}
