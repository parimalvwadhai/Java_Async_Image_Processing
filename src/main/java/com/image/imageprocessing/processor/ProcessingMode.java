package com.image.imageprocessing.processor;

/**
 * How {@link ImageProcessor} should distribute tile work.
 *
 * <p>This controls <em>parallelism only</em> — not which thread the processing runs on.
 * Those are independent variables, and conflating them is what made the original
 * implementation impossible to reason about: a single-threaded run on the JavaFX
 * Application Thread differs from a pooled background run in two ways at once, so the
 * resulting timings cannot be attributed to either cause.
 *
 * <p>Keeping the two separate means {@link #SYNCHRONOUS} and {@link #ASYNCHRONOUS}, both
 * dispatched off the FX thread, isolate the speedup attributable purely to parallelism.
 */
public enum ProcessingMode {

    /** One tile at a time on the calling thread. No thread pool involved. */
    SYNCHRONOUS("Synchronous (single thread)"),

    /** Tiles distributed across a fixed pool sized to the available CPU count. */
    ASYNCHRONOUS("Asynchronous (thread pool)");

    private final String label;

    ProcessingMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
