package com.image.imageprocessing;

import com.image.imageprocessing.filter.GreyScaleFilter;
import com.image.imageprocessing.filter.ImageFilter;
import com.image.imageprocessing.image.DrawMultipleImagesOnCanvas;
import com.image.imageprocessing.io.FileImageIO;
import com.image.imageprocessing.io.ImageReadInf;
import com.image.imageprocessing.processor.ImageProcessor;
import com.image.imageprocessing.processor.ProcessingMode;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public class HelloApplication extends Application {

    private static final String TEST_IMAGE = "/com/image/imageprocessing/io/test.jpg";
    private static final int TILE_SIZE = 10;

    private ImageProcessor processor;
    private DrawMultipleImagesOnCanvas canvasView;
    private BufferedImage sourceImage;
    private ImageFilter imageFilter;

    private Button asyncButton;
    private Button syncButton;
    private Button syncOnFxButton;
    private Label statusLabel;
    private TextArea resultsLog;

    /** Most recent synchronous timing, used as the baseline for the speedup column. */
    private Double baselineMillis;

    @Override
    public void start(Stage stage) throws IOException {
        sourceImage = loadTestImage();
        imageFilter = new GreyScaleFilter();
        processor = new ImageProcessor();

        canvasView = new DrawMultipleImagesOnCanvas();
        Canvas canvas = canvasView.createCanvas(sourceImage.getWidth(), sourceImage.getHeight());

        stage.setScene(new Scene(buildLayout(canvas)));
        stage.setTitle("Image Processor — Sync vs Async Comparison");
        stage.show();
    }

    private BorderPane buildLayout(Canvas canvas){
        asyncButton = new Button("Run Async (thread pool)");
        asyncButton.setOnAction(event -> runOffFxThread(ProcessingMode.ASYNCHRONOUS));

        syncButton = new Button("Run Sync (single thread)");
        syncButton.setOnAction(event -> runOffFxThread(ProcessingMode.SYNCHRONOUS));

        syncOnFxButton = new Button("Run Sync on FX thread (freezes UI)");
        syncOnFxButton.setOnAction(event -> runOnFxThread());

        statusLabel = new Label(String.format(
                "%d x %d, tile size %d = %,d tiles | pool size %d",
                sourceImage.getWidth(), sourceImage.getHeight(), TILE_SIZE,
                (sourceImage.getWidth() / TILE_SIZE) * (sourceImage.getHeight() / TILE_SIZE),
                processor.getPoolSize()));

        HBox controls = new HBox(10, asyncButton, syncButton, syncOnFxButton, statusLabel);
        controls.setPadding(new Insets(10));

        resultsLog = new TextArea();
        resultsLog.setEditable(false);
        resultsLog.setPrefRowCount(7);
        resultsLog.setStyle("-fx-font-family: 'Consolas', monospace;");
        resultsLog.setText("""
                Run Sync first to establish a baseline, then Async to compare.

                Sync and Async both run OFF the FX thread, so the only variable
                between them is parallelism — the difference is a fair speedup.
                The third button runs the same single-threaded work ON the FX
                thread, which freezes the window until it finishes.
                """);

        ScrollPane canvasScroll = new ScrollPane(new Group(canvas));
        canvasScroll.setPannable(true);

        BorderPane root = new BorderPane();
        root.setTop(controls);
        root.setCenter(canvasScroll);
        root.setBottom(resultsLog);

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        root.setPrefSize(Math.min(1500, screen.getWidth() * 0.9),
                         Math.min(950, screen.getHeight() * 0.9));
        return root;
    }

    /**
     * The correct way to run processing: on a background thread, so the FX event loop keeps
     * pulsing and the AnimationTimer can render tiles as they arrive.
     */
    private void runOffFxThread(ProcessingMode mode){
        prepareRun();

        Task<Long> task = new Task<>() {
            @Override
            protected Long call() {
                return processor.processImage(sourceImage, TILE_SIZE, imageFilter, canvasView, mode);
            }
        };
        task.setOnSucceeded(event -> {
            logResult(mode, task.getValue(), false);
            finishRun();
        });
        task.setOnFailed(event -> {
            task.getException().printStackTrace();
            logLine("FAILED: " + task.getException());
            finishRun();
        });

        Thread thread = new Thread(task, "image-processing-coordinator");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Deliberately wrong, kept as a demonstration: this reproduces the original defect by
     * running the blocking call directly on the JavaFX Application Thread. The window stops
     * responding, no tiles are drawn while it works, and everything appears at once when it
     * finishes — because the AnimationTimer cannot fire while this method holds the thread.
     */
    private void runOnFxThread(){
        prepareRun();
        long elapsed = processor.processImage(
                sourceImage, TILE_SIZE, imageFilter, canvasView, ProcessingMode.SYNCHRONOUS);
        logResult(ProcessingMode.SYNCHRONOUS, elapsed, true);
        finishRun();
    }

    private void prepareRun(){
        canvasView.clear();
        setButtonsDisabled(true);
    }

    private void finishRun(){
        setButtonsDisabled(false);
    }

    private void setButtonsDisabled(boolean disabled){
        asyncButton.setDisable(disabled);
        syncButton.setDisable(disabled);
        syncOnFxButton.setDisable(disabled);
    }

    private void logResult(ProcessingMode mode, long elapsedNanos, boolean onFxThread){
        double millis = elapsedNanos / 1_000_000.0;
        int threads = mode == ProcessingMode.ASYNCHRONOUS ? processor.getPoolSize() : 1;

        String comparison;
        if (mode == ProcessingMode.SYNCHRONOUS && !onFxThread) {
            baselineMillis = millis;              // this run becomes the new baseline
            comparison = "baseline";
        } else if (baselineMillis != null) {
            comparison = String.format("%.2fx vs sync baseline", baselineMillis / millis);
        } else {
            comparison = "run Sync for a baseline";
        }

        logLine(String.format("%-28s %2d thread%s  %8.1f ms   %s%s",
                mode.getLabel(), threads, threads == 1 ? " " : "s", millis, comparison,
                onFxThread ? "   [ON FX THREAD - UI was frozen]" : ""));
    }

    private void logLine(String line){
        resultsLog.appendText(line + System.lineSeparator());
    }

    private BufferedImage loadTestImage() throws IOException {
        ImageReadInf imageIO = new FileImageIO();
        try (InputStream source = HelloApplication.class.getResourceAsStream(TEST_IMAGE)) {
            if (source == null) {
                throw new IOException("Bundled test image not found on the module path: " + TEST_IMAGE);
            }
            return imageIO.readImage(source);
        }
    }

    @Override
    public void stop() {
        if (processor != null) {
            processor.shutdown();
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
