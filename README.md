# ImageProcessorApp

A JavaFX and CLI-based application designed to apply tile-based image filters both synchronously and asynchronously, demonstrating multithreading performance, UI thread responsiveness, and image manipulation.

> **Note:** Initial architectural setup and project scaffolding. Core modular components (`com.my.app.filters`, `com.my.app.processor`, `com.my.app.io`) are configured, with filter implementations and processing logic being committed incrementally.

---

## Features

* **Dual Execution Modes:**
* **Asynchronous Mode:** Processes images using parallel tile-based tasks over a fixed thread pool to maintain UI responsiveness and accelerate execution on multi-core systems.
* **Synchronous Mode:** Processes the whole image sequentially on the main thread for baseline comparisons.


* **Interactive Input:** Dual interface featuring a JavaFX canvas display alongside a console-driven control menu.
* **Output Export:** Filtered output files are auto-rendered and saved to the `output/` directory (`filtered_<filename>.png`).

---

## Project Structure

```text
src/main/java/com/my/app/
├── filters/          # Image filter interfaces and implementations (e.g., GreyScaleFilter)
├── image/            # Data structures for image representations and canvas interactions
├── io/               # Image read/write operations and asset loading
├── processor/        # Multi-threaded execution logic and tile partitioning
└── HelloApplication.java  # Main application entry point

```

---

## Prerequisites

* **JDK 21** or higher (ensure `JAVA_HOME` is set).
* Maven 3.x or the included Maven wrapper (`mvnw` / `mvnw.cmd`).

---

## Quick Setup

### Using Command Line

**Windows (PowerShell / CMD):**

```powershell
# Compile the project
.\mvnw.cmd clean compile

# Run the application
.\mvnw.cmd javafx:run

```

**macOS / Linux:**

```bash
# Compile the project
./mvnw clean compile

# Run the application
./mvnw javafx:run

```

### Using an IDE (IntelliJ IDEA / VS Code)

1. Import the directory as a **Maven** project.
2. Ensure the project SDK is set to **JDK 21**.
3. Run `com.my.app.HelloApplication` directly from your editor.

---

## Architecture & Design Principles

1. **Tile-Based Processing:** Images are split into uniform grid tiles. The tile dimensions must evenly divide both the width and height of the image to ensure seamless boundary processing.
2. **Asynchronous Execution:** Background processing runs on an `ExecutorService` fixed thread pool, freeing the JavaFX Application Thread to progressively render completed tiles onto the canvas.
3. **Filter Pipeline:** All filter types implement `com.my.app.filters.ImageFilter`, making it simple to add custom filters (e.g., blur, sharpen, sepia) by implementing the primary interface.

---

## Benchmark Plan: Synchronous vs. Asynchronous

To test and record performance metrics across execution modes:

1. Place three test images into `src/main/resources/`:
* **Small:** ~800×600 px
* **Medium:** ~1920×1080 px
* **Large:** ~3000px+


2. Execute **Option 2 (Synchronous)** for each image: record execution time, CPU utilization, and UI freeze durations.
3. Execute **Option 1 (Asynchronous)** using a tile size that evenly divides image dimensions: record completion time, peak multi-core CPU usage, and UI smoothness.
4. **Key Takeaways:**
* Synchronous processing blocks the main UI thread and uses a single core.
* Asynchronous processing lowers overall wall-clock processing time and preserves UI responsiveness, though tile size must be balanced to avoid thread scheduling overhead.