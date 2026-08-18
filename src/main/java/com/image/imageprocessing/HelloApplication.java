package com.image.imageprocessing;

import com.image.imageprocessing.filter.GreyScaleFilter;
import com.image.imageprocessing.filter.ImageFilter;
import com.image.imageprocessing.image.DrawMultipleImagesOnCanvas;
import com.image.imageprocessing.io.FileImageIO;
import com.image.imageprocessing.io.ImageReadInf;
import com.image.imageprocessing.processor.ImageProcessor;
import com.image.imageprocessing.processor.ProcessingMode;
import javafx.application.Application;
import javafx.application.Platform;
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
import java.util.Arrays;

public class HelloApplication extends Application {

    private static final String TEST_IMAGE = "/com/image/imageprocessing/io/test.jpg";
    private static final int TILE_SIZE = 10;

    private ImageProcessor processor;
    private DrawMultipleImagesOnCanvas canvasView;
    private BufferedImage sourceImage;
    private ImageFilter imageFilter;

    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 5;
    private static final int RESULTS_PANEL_HEIGHT = 170;

    private Button asyncButton;
    private Button syncButton;
    private Button syncOnFxButton;
    private Button benchmarkButton;
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

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(buildLayout(canvas), screen.getWidth(), screen.getHeight());

        stage.setScene(scene);
        stage.setTitle("Image Processor — Sync vs Async Comparison");
        // Maximise rather than size to content. The canvas is as large as the source image,
        // so a content-sized window is typically taller than the display and pushes the
        // results panel off-screen; maximising always fits the work area.
        stage.setMaximized(true);
        stage.show();

        // Scripted entry point: `mvnw javafx:run -Dapp.benchmark=true` runs the comparison
        // automatically and prints it to stdout, with no clicking required.
        if (Boolean.getBoolean("app.benchmark") || getParameters().getRaw().contains("--benchmark")) {
            runBenchmark();
        }
    }

    private BorderPane buildLayout(Canvas canvas){
        asyncButton = new Button("Run Async (thread pool)");
        asyncButton.setOnAction(event -> runOffFxThread(ProcessingMode.ASYNCHRONOUS));

        syncButton = new Button("Run Sync (single thread)");
        syncButton.setOnAction(event -> runOffFxThread(ProcessingMode.SYNCHRONOUS));

        syncOnFxButton = new Button("Run Sync on FX thread (freezes UI)");
        syncOnFxButton.setOnAction(event -> runOnFxThread());

        benchmarkButton = new Button("Benchmark both");
        benchmarkButton.setDefaultButton(true);
        benchmarkButton.setOnAction(event -> runBenchmark());

        // Ceiling division, matching the grid ImageProcessor actually builds: a tile size that
        // does not divide the image evenly still yields a (smaller) edge tile per remainder
        // strip, so the count is one row and one column larger than integer division suggests.
        statusLabel = new Label(String.format(
                "%d x %d, tile size %d = %,d tiles | pool size %d",
                sourceImage.getWidth(), sourceImage.getHeight(), TILE_SIZE,
                Math.ceilDiv(sourceImage.getWidth(), TILE_SIZE)
                        * Math.ceilDiv(sourceImage.getHeight(), TILE_SIZE),
                processor.getPoolSize()));

        HBox controls = new HBox(10, asyncButton, syncButton, syncOnFxButton, benchmarkButton, statusLabel);
        controls.setPadding(new Insets(10));

        resultsLog = new TextArea();
        resultsLog.setEditable(false);
        resultsLog.setPrefRowCount(7);
        resultsLog.setStyle("-fx-font-family: 'Consolas', monospace; -fx-control-inner-background: #f4f6f8;");
        resultsLog.setText("""
                Run Sync first to establish a baseline, then Async to compare.

                Sync and Async both run OFF the FX thread, so the only variable
                between them is parallelism — the difference is a fair speedup.
                The third button runs the same single-threaded work ON the FX
                thread, which freezes the window until it finishes.
                """);

        ScrollPane canvasScroll = new ScrollPane(new Group(canvas));
        canvasScroll.setPannable(true);
        // The canvas is as large as the source image, which gives the ScrollPane a large
        // minimum height. Without this, BorderPane satisfies the centre region first and
        // starves the results panel below it down to zero height.
        canvasScroll.setMinSize(0, 0);
        canvasScroll.setMaxHeight(Double.MAX_VALUE);
        // Pin the results panel to a fixed height. Left to negotiate, BorderPane satisfies
        // the centre region (whose content is a full-size image canvas) first and collapses
        // this to nothing.
        resultsLog.setMinHeight(RESULTS_PANEL_HEIGHT);
        resultsLog.setPrefHeight(RESULTS_PANEL_HEIGHT);
        resultsLog.setMaxHeight(RESULTS_PANEL_HEIGHT);

        BorderPane root = new BorderPane();
        root.setTop(controls);
        root.setCenter(canvasScroll);
        root.setBottom(resultsLog);
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

    /**
     * Runs both modes repeatedly and reports the distribution.
     *
     * <p>A single click of each button cannot produce a valid comparison: on a cold JVM the
     * first run pays for JIT compilation and heap growth, so whichever mode happens to run
     * first is penalised heavily. Measured directly after launch, a cold parallel run can
     * look <em>slower</em> than a warm single-threaded one. Discarding warm-up rounds and
     * reporting the median across several measured runs removes that artefact.
     */
    private void runBenchmark(){
        setButtonsDisabled(true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                logLine("");
                logLine(String.format("=== Benchmark: %d warm-up + %d measured runs per mode ===",
                        WARMUP_RUNS, MEASURED_RUNS));

                double[] sync = measure(ProcessingMode.SYNCHRONOUS);
                double[] async = measure(ProcessingMode.ASYNCHRONOUS);

                report(ProcessingMode.SYNCHRONOUS, 1, sync);
                report(ProcessingMode.ASYNCHRONOUS, processor.getPoolSize(), async);
                logLine(String.format("Speedup (median): %.2fx on %d cores",
                        median(sync) / median(async), processor.getPoolSize()));
                return null;
            }

            private double[] measure(ProcessingMode mode){
                for (int i = 0; i < WARMUP_RUNS; i++) {
                    processor.processImage(sourceImage, TILE_SIZE, imageFilter, canvasView, mode);
                    discardBacklog();
                }
                double[] timings = new double[MEASURED_RUNS];
                for (int i = 0; i < MEASURED_RUNS; i++) {
                    timings[i] = processor.processImage(
                            sourceImage, TILE_SIZE, imageFilter, canvasView, mode) / 1_000_000.0;
                    discardBacklog();
                }
                return timings;
            }

            /**
             * Drops tiles the consumer has not drawn yet. Without this the queue accumulates
             * tens of thousands of images across runs, and the resulting GC pressure would
             * itself distort the measurements.
             */
            private void discardBacklog(){
                Platform.runLater(canvasView::clear);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }

            private void report(ProcessingMode mode, int threads, double[] timings){
                double[] sorted = timings.clone();
                Arrays.sort(sorted);
                double mean = Arrays.stream(timings).average().orElse(Double.NaN);
                logLine(String.format("%-28s %2d thread%s  min %7.1f  median %7.1f  mean %7.1f ms",
                        mode.getLabel(), threads, threads == 1 ? " " : "s",
                        sorted[0], median(timings), mean));
            }

            private double median(double[] values){
                double[] sorted = values.clone();
                Arrays.sort(sorted);
                return sorted[sorted.length / 2];
            }
        };

        task.setOnSucceeded(event -> finishRun());
        task.setOnFailed(event -> {
            task.getException().printStackTrace();
            logLine("BENCHMARK FAILED: " + task.getException());
            finishRun();
        });

        Thread thread = new Thread(task, "benchmark");
        thread.setDaemon(true);
        thread.start();
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
        benchmarkButton.setDisable(disabled);
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

    /**
     * Safe to call from any thread — the benchmark logs from its worker thread, and a
     * TextArea may only be mutated on the FX Application Thread.
     */
    private void logLine(String line){
        System.out.println(line);
        if (Platform.isFxApplicationThread()) {
            resultsLog.appendText(line + System.lineSeparator());
        } else {
            Platform.runLater(() -> resultsLog.appendText(line + System.lineSeparator()));
        }
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
