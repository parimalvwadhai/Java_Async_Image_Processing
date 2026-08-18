package com.image.imageprocessing;

import com.image.imageprocessing.filter.GreyScaleFilter;
import com.image.imageprocessing.filter.ImageFilter;
import com.image.imageprocessing.image.DrawMultipleImagesOnCanvas;
import com.image.imageprocessing.io.FileImageIO;
import com.image.imageprocessing.io.ImageReadInf;
import com.image.imageprocessing.processor.ImageProcessor;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageFilter;
import java.io.IOException;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        DrawMultipleImagesOnCanvas drawMultipleImagesOnCanvas = DrawMultipleImagesOnCanvas.getInstance();
        drawMultipleImagesOnCanvas.initialize(stage);

        ImageReadInf imageIO = new FileImageIO();
        BufferedImage image = imageIO.readImage("C:\\Users\\Lovepreet\\Desktop\\imageProcessing\\src\\main\\java\\com\\image\\imageprocessing\\io\\test.jpg");
        ImageProcessor processor = new ImageProcessor();
        ImageFilter imageFilter = new GreyScaleFilter();
        processor.processImage(image, 10, imageFilter, drawMultipleImagesOnCanvas);

        Platform.setImplicitExit(false);
    }

    public static void main(String[] args) {
        launch();
    }
}