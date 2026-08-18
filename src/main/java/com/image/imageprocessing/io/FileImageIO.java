package com.image.imageprocessing.io;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public class FileImageIO implements ImageReadInf {

    /**
     * Reads an image from either a filesystem path ({@code String}) or an already-open
     * {@code InputStream} (which is how the bundled test image is loaded, as a module
     * resource). Failure throws rather than returning null: a null here would only
     * surface later as an NPE inside the processor, far from the actual cause.
     */
    @Override
    public <T> BufferedImage readImage(T src){
        try{
            BufferedImage image;
            if (src instanceof InputStream stream) {
                image = ImageIO.read(stream);
            } else if (src instanceof String path) {
                image = ImageIO.read(new File(path));
            } else {
                throw new IllegalArgumentException(
                        "Unsupported image source type: " + (src == null ? "null" : src.getClass().getName()));
            }
            if (image == null) {
                throw new IOException("No registered ImageReader could decode the image source");
            }
            return image;
        }catch (IOException ex){
            throw new UncheckedIOException("Not able to read the image", ex);
        }
    }

    @Override
    public void sendImage(BufferedImage src){
        // Todo: implement this fucntion to store the imageFile
    }

}
