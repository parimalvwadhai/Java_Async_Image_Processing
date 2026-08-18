# Concurrency Design Notes

A technical write-up of how this application handles threading, the three concurrency bugs
that were found and fixed in it, and the issues that remain open.

The application's job is simple — greyscale an image — but doing it *while keeping a JavaFX UI
responsive and rendering results progressively* is where the actual engineering is. This
document covers that part.

---

## Contents

1. [Architecture](#architecture)
2. [Thread model](#thread-model)
3. [Bug 1 — Blocking the JavaFX Application Thread](#bug-1--blocking-the-javafx-application-thread)
4. [Bug 2 — A dead guard flag and unbounded thread creation](#bug-2--a-dead-guard-flag-and-unbounded-thread-creation)
5. [Bug 3 — A singleton that was not a singleton](#bug-3--a-singleton-that-was-not-a-singleton)
6. [Supporting fixes](#supporting-fixes)
7. [Verification](#verification)
8. [Measuring it: synchronous vs asynchronous](#measuring-it-synchronous-vs-asynchronous)
9. [Bug 4 — Edge tiles silently dropped](#bug-4--edge-tiles-silently-dropped)
10. [Open issues](#open-issues)

---

## Architecture

Data flows in one direction:

```
HelloApplication.start()                       [JavaFX Application Thread]
      |
      |-- FileImageIO.readImage(InputStream) -> BufferedImage        (io/)
      |
      |-- DrawMultipleImagesOnCanvas.initialize(stage)               (image/)
      |         creates Canvas + GraphicsContext
      |         starts AnimationTimer  <-- the consumer loop
      |
      '-- Task<Void> -> background thread
                |
                '-- ImageProcessor.processImage(image, tileSize, filter, drawFn)
                          |
                          |-- splits image into a grid via getSubimage()
                          |-- submits one Callable per tile to the pool
                          |         each task: filter(tile)
                          |                    -> ImageData
                          |                    -> drawFn.accept()   [TileSink]
                          |                         (converts to FX Image here,
                          |                          on the worker thread)
                          '-- blocks on future.get()  <-- safe: not the FX thread
                                        |
                                        v
                       bounded LinkedBlockingQueue<Tile>   <-- thread-safety boundary
                         producers put() and block when full  (backpressure)
                                        |
                                        v
                              AnimationTimer.handle()  ->  gc.drawImage()
                                                            [FX thread, time-budgeted]
```

### Components

| Package | Type | Role |
|---|---|---|
| `filter/` | `ImageFilter` | Single-method interface, `BufferedImage filter(BufferedImage)`. Strategy pattern. Implementations must be pure functions — see the invariant below. |
| `filter/` | `GreyScaleFilter` | Luminance weights 0.2126R + 0.7152G + 0.0722B. |
| `processor/` | `ImageProcessor` | The parallel engine. Owns the `ExecutorService`, tiles the image, submits tasks, awaits completion. |
| `image/` | `ImageData` | Value carrier crossing the thread boundary: a filtered tile plus its destination coordinates. |
| `image/` | `DrawMultipleImagesOnCanvas` | The consumer. Owns the `Canvas`, `GraphicsContext`, and the queue. |
| `io/` | `ImageReadInf` / `FileImageIO` | Read abstraction. `sendImage()` is currently an unimplemented stub. |

### The core constraint

JavaFX scene graph objects — including `Canvas` and `GraphicsContext` — are **not thread-safe
and may only be mutated on the JavaFX Application Thread.** Every design decision below follows
from that single rule.

The response is standard producer/consumer decoupling: worker threads never touch UI objects at
all. They publish finished tiles into a thread-safe queue and return. A consumer already running
on the FX thread drains that queue and draws. **The queue is the boundary between the two
worlds**, and it is the only place the two thread groups meet.

That architecture was sound from the beginning. All three bugs described below were defects in
its *implementation*, not in the design.

### An important invariant: filters must be pure

`BufferedImage.getSubimage()` does **not** copy pixel data. Every tile aliases the *same*
underlying `DataBuffer` as the source image, so thousands of worker threads read one shared
raster concurrently.

This is safe, for two specific reasons:

1. No task ever **writes** to the source — `GreyScaleFilter` allocates a brand-new output image.
2. `getRGB()` allocates its own scratch array per call, so it is re-entrant.

Concurrent reads with no writes require no synchronisation. But that safety is a *consequence*
of `ImageFilter` implementations being pure, and nothing in the code enforces it. **Any new
filter must allocate and return a fresh image rather than modifying its input**; an in-place
filter would introduce a data race that appears only under load.

---

## Thread model

| # | Thread | Created by | Runs | Constraint |
|---|---|---|---|---|
| 1 | **JavaFX Application Thread** | JavaFX runtime | `start()`, every `AnimationTimer.handle()`, every `Platform.runLater` body, all rendering | The **only** thread permitted to touch `Canvas` / `GraphicsContext` / scene graph |
| 2 | **Coordinator thread** (1) | `HelloApplication` (via `Task`) | `processImage()` — task submission and the blocking await | Daemon |
| 3 | **Pool workers** (= CPU count) | `ImageProcessor` | `Callable.call()` — filtering and the FX-image conversion | Never touch FX scene objects; may only publish to the queue |
| 4 | **JavaFX pulse / Prism render threads** | JavaFX runtime | Internal frame rendering | Not touched by application code |

### The `AnimationTimer` contract

`AnimationTimer.handle(long now)` is invoked **on the JavaFX Application Thread**, roughly 60
times per second, driven by the same event loop that dispatches input events and repaints.

Two consequences follow, and the original code violated both — in opposite directions:

1. Because `handle()` is *already* on the FX thread, it does **not** need `Platform.runLater`
   and does **not** need a spawned thread in order to draw. The original code added both.
   → [Bug 2](#bug-2--a-dead-guard-flag-and-unbounded-thread-creation)
2. Because `handle()` is driven by the FX event loop, **anything that blocks the FX thread stops
   the timer entirely.** The original code blocked it.
   → [Bug 1](#bug-1--blocking-the-javafx-application-thread)

Both bugs reduce to the same root cause: *not knowing which thread a given line of code actually
runs on.*

---

## Bug 1 — Blocking the JavaFX Application Thread

**Severity: critical.** This defect defeated the application's entire purpose.

### The original code

```java
// HelloApplication.start()  -- runs ON the JavaFX Application Thread
processor.processImage(image, 10, imageFilter, drawMultipleImagesOnCanvas);

// ImageProcessor.processImage()
for (Future<ImageData> future : futures) {
    try {
        future.get();          // <-- BLOCKS. On the FX thread.
    } catch (Exception ex) {
        System.err.println("Error processing image: " + ex.getMessage());
    }
}
```

### What happened

`start()` runs on the FX Application Thread. It called `processImage()`, which submitted every
tile and then looped over each `Future` calling `get()` — a blocking call. The FX thread
therefore sat blocked inside `start()` until the last tile completed.

The consequences cascade:

- The FX event loop never advanced, so `AnimationTimer.handle()` **never fired during
  processing**. The consumer was inert precisely while the producer was running.
- Every `Platform.runLater` runnable queued up unexecuted.
- The window did not paint and did not respond to input; the OS marked it "Not Responding".
- When `processImage()` finally returned, the event loop resumed and drained everything at once
  — so all tiles appeared simultaneously, at the end.

Notably, the producer had been written correctly. Its comment read:

```java
// Add to queue immediately when processing is complete
drawFn.addImageToQueue(imageData);
```

Tiles *were* published incrementally, exactly as intended for progressive rendering. That effort
was wasted, because the same method doing the publishing was blocking the consumer's thread. The
producer/consumer split existed, but only one end of it could run at a time.

### The fix

Dispatch the work via a `javafx.concurrent.Task` on a background thread, so `start()` returns
immediately and the event loop keeps pulsing:

```java
long startedAt = System.nanoTime();
Task<Void> processingTask = new Task<>() {
    @Override
    protected Void call() {
        processor.processImage(image, TILE_SIZE, imageFilter, drawMultipleImagesOnCanvas);
        return null;
    }
};
processingTask.setOnSucceeded(event -> System.out.printf(
        "Processing finished in %d ms%n", (System.nanoTime() - startedAt) / 1_000_000));
processingTask.setOnFailed(event -> processingTask.getException().printStackTrace());

Thread processingThread = new Thread(processingTask, "image-processing-coordinator");
processingThread.setDaemon(true);
processingThread.start();
```

`processImage()` remains **blocking internally**, and that is correct — its `future.get()` loop
is a legitimate "await all tiles" barrier. It simply must not run on the UI thread. The method's
Javadoc now states that constraint explicitly.

`Task` was chosen over a bare `Thread` because its `setOnSucceeded` / `setOnFailed` handlers fire
on the FX thread automatically, and it exposes `updateProgress` / `updateMessage` should a
progress indicator be added later. `CompletableFuture.allOf(...).thenRun(...)` would be the
fully non-blocking alternative, at the cost of being less idiomatic within JavaFX.

### Takeaway

> Moving work to a thread pool does not make an application responsive if the UI thread then
> blocks waiting for the result. "Asynchronous" means the UI thread returns to its event loop.

`Future.get()` is a *synchronous await*. Placing it on the UI thread converts a parallel program
back into a blocking one — retaining all of concurrency's overhead and none of its benefit.

Worth being precise about what was and wasn't lost: the filtering itself was genuinely parallel,
so total wall-clock time did improve. What was destroyed was **responsiveness and progressive
feedback**. Throughput and latency are separate goals, and the original code optimised the first
while ruining the second.

---

## Bug 2 — A dead guard flag and unbounded thread creation

Three distinct defects in eight lines.

### The original code

```java
private boolean isDrawing = false;          // never set to true, anywhere

// inside AnimationTimer.handle()  -- already on the FX thread
if (!isDrawing && !queue.isEmpty()) {
    new Thread(() -> {
        try { drawNextImage(); }
        finally { isDrawing = false; }
    }).start();
}

private void drawNextImage(){
    ImageData imageData = queue.poll();
    Platform.runLater(() -> {           // ... straight back to the FX thread
        if (imageData != null) {
            this.gc.drawImage(SwingFXUtils.toFXImage(imageData.getImage(), null), ...);
            System.out.println("Drawing using thread " + Thread.currentThread().getName());
        }
    });
}
```

### (a) The flag was never set to `true`

It was initialised `false`, read in the guard, and reset to `false` in the `finally`. There was
**no assignment of `true` anywhere in the class**, so `!isDrawing` was permanently true and the
guard was a no-op.

The result: on *every* animation pulse where the queue was non-empty, a new `Thread` was
allocated and started — roughly **60 threads per second**, each reserving stack space, each
alive for microseconds. This is unbounded thread creation: precisely the problem thread pools
exist to prevent, reintroduced in the consumer of an application built around a thread pool.

### (b) The flag was not `volatile` — a data race regardless

`isDrawing` was a plain `boolean`, **written by spawned threads** and **read by the FX thread**,
with no happens-before edge between them. Under the Java Memory Model the FX thread is **not
guaranteed to ever observe** that write — the JIT may legally hoist the read.

Fixing (a) alone would therefore have produced a consumer that could hang permanently after the
first tile. The flag needed `volatile` at minimum.

On atomicity: check-then-act (`if (!isDrawing) { isDrawing = true; }`) is not atomic in general
and would normally call for `AtomicBoolean.compareAndSet`. In this specific case only the single
FX thread would ever set the flag `true`, so `volatile` alone would have been sufficient.

### (c) The spawned thread accomplished nothing

The thread performed one cheap, thread-safe `queue.poll()` and then wrapped **all** the real
work in `Platform.runLater` — handing it straight back to the FX thread. Full thread-creation
cost, zero parallelism gained. And since `handle()` was already on the FX thread, the entire
apparatus — thread, `runLater`, and flag — was unnecessary.

There *was* work worth moving off the FX thread, just not the work that was moved.
`SwingFXUtils.toFXImage()` allocates a `WritableImage` and copies every pixel, and in the
original code it ran **on the FX thread** inside `runLater`. The design was inverted: it
offloaded the trivial part and retained the expensive part on the UI thread.

### The fix

The conversion moved into `accept()`, which is invoked from inside the `Callable` and
therefore already runs on a pool worker. The pixel copy lands where the parallelism is, and the
FX thread is left with only `drawImage`:

```java
private record Tile(Image image, int x, int y, int width, int height) { }

/** Called from pool worker threads. Converts off the FX thread, then publishes. */
@Override
public void accept(ImageData image){
    Image fxImage = SwingFXUtils.toFXImage(image.getImage(), null);
    queue.put(new Tile(fxImage, image.getI(), image.getJ(), image.getX(), image.getY()));
}
```

The consumer loses the thread, the `runLater`, and the flag, and gains a **per-frame time
budget**:

```java
private static final long FRAME_BUDGET_NANOS = 8_000_000L;   // ~half of a 16.6ms frame

private void drainFrameBudget(){
    long deadline = System.nanoTime() + FRAME_BUDGET_NANOS;
    Tile tile;
    while ((tile = queue.poll()) != null) {
        gc.drawImage(tile.image(), tile.x(), tile.y(), tile.width(), tile.height());
        if (System.nanoTime() >= deadline) {
            break;                   // checked AFTER drawing: always make progress
        }
    }
}
```

The budget is essential. Draining the entire queue in a single `handle()` call would block the
FX thread again — recreating Bug 1 at a smaller scale. Bounding work per frame keeps the frame
rate smooth while still making steady progress, and checking the deadline *after* drawing
guarantees at least one tile advances per frame.

### Queue choice — and why it was revisited

The first decision was to replace `LinkedBlockingQueue` with `ConcurrentLinkedQueue`. The
reasoning was that the blocking API was never used — the consumer is a non-blocking `poll()`
drain — so a lock-free queue with no lock and no capacity bookkeeping was the better fit. That
reasoning was sound as far as it went, and it went one step short: an unbounded queue means a
fast producer with a slow consumer simply grows the heap, and there is no mechanism to stop it.

The queue is now a **bounded `LinkedBlockingQueue`**, and producers `put()`. When it is full, a
worker blocks until the consumer makes space. That is backpressure: the consumer's rate reaches
the producers instead of being absorbed by memory, and a blocked worker is idle rather than
allocating — which is what should happen when the drawing side cannot keep up.

The blocking is deliberately **asymmetric**, and that asymmetry is the design:

| Side | Operation | May block? |
|---|---|---|
| Producers (pool workers) | `put()` | Yes — this is the backpressure |
| Consumer (FX thread) | `poll()` | **Never** — blocking it is the failure this whole design exists to prevent |

The cost is a lock where there previously was none, and one that is now contended by every
worker. At these tile counts it does not show up against the per-tile filtering and pixel
conversion, but it is a real trade and worth naming: lock-free throughput was exchanged for a
bound on memory.

#### The deadlock this introduced

Bounding the queue turned the deliberately-wrong "Run Sync on FX thread" control into a genuine
deadlock, where before it was only a freeze. The distinction is worth being precise about,
because the two are commonly conflated:

- **Before (unbounded):** the FX thread is *busy* inside the processing loop, so
  `AnimationTimer` never fires and nothing paints. Unresponsive, but it always completes. This
  is starvation of the event loop.
- **After (bounded):** the FX thread blocks in `put()` waiting for space — and it is also the
  only thread that drains the queue. The space can never appear, because the thread that would
  create it is the one waiting. A circular wait with a cycle length of one.

`accept()` therefore checks `Platform.isFxApplicationThread()`, and on that path uses a timed
`offer` that throws `SelfDeadlockException` rather than a `put` that would hang the JVM. The
failure names itself and the window survives.

The per-tile `System.out.println` calls were also removed. Roughly 20,000 synchronised writes to
`System.out` is a measurable bottleneck in its own right and would distort any timing measurement.

---

## Bug 3 — A singleton that was not a singleton

### The original code

```java
private static DrawMultipleImagesOnCanvas instance;

public static DrawMultipleImagesOnCanvas getInstance(){
    if(instance == null){
        return new DrawMultipleImagesOnCanvas();   // never assigned to `instance`
    }
    return instance;
}
```

### What was wrong

The constructed object was returned but **never stored** in the `instance` field. `instance`
remained `null` permanently, the `if` branch was taken on every call, and **every call returned
a different object**. This was a factory method with a singleton's signature.

It never visibly failed because `getInstance()` was called exactly **once**, and that reference
was then passed explicitly into `processImage(...)`. Producer and consumer shared one instance by
accident of the call graph — because the object was dependency-injected — not because of the
singleton mechanism. The singleton was dead code that happened to be harmless.

It was, however, a latent fault. The moment a second caller appeared — a second filter run, a CLI
entry point, a unit test — it would receive an object with a **different queue** and a **null
`GraphicsContext`**, because `initialize()` had only ever been called on the first instance.
Tiles enqueued on it would silently never render, or drawing would throw a `NullPointerException`.
Silent, delayed failures are the most expensive kind to diagnose.

### The thread-safety dimension

Even with the missing assignment restored, this lazy check-then-act would **not** be thread-safe:
two threads can both evaluate `instance == null` as true and both construct. The standard
remedies, in ascending order of sophistication:

1. **Eager initialisation** — `private static final X INSTANCE = new X();`. Thread-safe via
   class-initialisation guarantees.
2. **Initialization-on-Demand Holder idiom** — a private static nested class holding the
   instance. Lazy *and* thread-safe with zero synchronisation, since the JVM serialises class
   initialisation.
3. **Enum singleton** — serialisation- and reflection-safe.
4. **Double-checked locking** — correct **only** if the field is `volatile`. Without it, the
   constructor's writes may be reordered *after* the reference is published, allowing another
   thread to observe a non-null reference to a partially-constructed object.

### The fix

`getInstance()` and the `instance` field were deleted outright; the single call site now uses
`new DrawMultipleImagesOnCanvas()`.

The class holds mutable UI state and a queue, which makes a singleton actively hostile to testing
and to running more than one processing session. The code already passed the collaborator
explicitly into `processImage(...)` — dependency injection, which is strictly better. The correct
fix for a broken singleton here was to not have one.

---

## Supporting fixes

### Pool sizing: 100 → `availableProcessors()`

```java
// Filtering is pure CPU work with no blocking I/O, so the useful degree of
// parallelism is bounded by core count.
int poolSize = Runtime.getRuntime().availableProcessors();
executorService = Executors.newFixedThreadPool(poolSize, daemonThreadFactory());
```

The original `newFixedThreadPool(100)` was mismatched to the workload. Greyscaling is pure CPU
with no blocking I/O; on an 8-core machine, 100 threads add context-switching and roughly 100MB
of stack reservations for no throughput gain.

The general sizing rule is `N × (1 + wait/compute)`. For compute-only work the wait/compute ratio
is zero, collapsing it to `N` — one thread per core.

### Executor lifecycle: the JVM would not exit

`Executors.newFixedThreadPool()` creates **non-daemon** threads by default. After `processImage()`
returned, 100 idle non-daemon threads kept the JVM alive; combined with
`Platform.setImplicitExit(false)`, closing the window did not terminate the process.

The fix has four parts: a named **daemon** `ThreadFactory` (`image-worker-N` — named threads make
stack traces and profiler output legible), an explicit `shutdown()` on `ImageProcessor`, an
`Application.stop()` override that calls it, and removal of the unnecessary
`Platform.setImplicitExit(false)`.

### Exception handling: causes were being discarded

```java
} catch (ExecutionException ex) {
    System.err.println("Error processing tile:");
    ex.getCause().printStackTrace();      // the actual failure
} catch (InterruptedException ex) {
    Thread.currentThread().interrupt();   // restore the interrupt flag
    return;
}
```

The original caught bare `Exception` and printed only `ex.getMessage()`. On an
`ExecutionException` that message is typically `null`, so a real failure inside a tile printed
`Error processing image: null` and nothing more — no cause, no stack trace.

The `InterruptedException` branch matters too: catching and swallowing it destroys the thread's
interrupt status. The correct idiom restores the flag so callers further up the stack can still
observe the cancellation.

### Resource loading

The image had been loaded from `C:\Users\Lovepreet\Desktop\...`, an absolute path from another
machine. It now lives at `src/main/resources/com/image/imageprocessing/io/test.jpg` and loads as
a module resource.

One subtlety: the file was originally under `src/main/java/`. Maven copies only
`src/main/resources` into `target/classes`, so a `.jpg` beneath `src/main/java` never reaches the
classpath and `getResourceAsStream` would return `null`. Moving it to the correct resource root
is the fix.

`FileImageIO.readImage` now accepts either an `InputStream` or a `String` path, and **throws
rather than returning `null`** on failure — a `null` return surfaced only later, as an NPE deep
inside the processor and far from the actual cause.

### Build configuration

`pom.xml` pointed `mainClass` at `com.converter.imageprocessorapp/com.my.app.HelloApplication` —
a module *and* package absent from the source tree. Corrected to
`com.image.imageprocessing/com.image.imageprocessing.HelloApplication`.

---

## Verification

| Check | Result |
|---|---|
| `mvnw clean compile` | Clean, no errors |
| Resource reaches the classpath | `[INFO] Copying 1 resource` |
| Application launches with corrected `mainClass` | Window opens |
| Pipeline completes end to end | `Processing finished in 606 ms` |
| **FX event loop alive during processing** | That message originates in `setOnSucceeded`, which fires **on the FX thread** — evidence the loop was pulsing rather than blocked |
| Image renders completely | Full greyscale output, edge to edge, no missing tiles |
| JVM exits on window close | Maven exited `0`; lingering java processes released |

**Caveat, stated plainly:** progressive tile-by-tile rendering was not *visually* confirmed — at
606ms the run is too brief to capture mid-flight. The evidence that Bug 1 is resolved is
structural (the FX thread never blocks, the timer drains per frame, and an FX-thread callback
demonstrably executed) rather than photographic. To observe the progressive fill directly, reduce
the tile size or introduce a deliberate delay inside the filter.

---

## Measuring it: synchronous vs asynchronous

The application ships both modes so the two can be compared directly. `ProcessingMode`
controls **parallelism only** — not which thread the work runs on. Those are independent
variables, and conflating them is precisely what made the original implementation impossible
to reason about. Both `SYNCHRONOUS` and `ASYNCHRONOUS` are dispatched off the FX thread, so
the difference between them isolates the speedup attributable to parallelism alone.

Three interactive buttons plus a benchmark are exposed:

| Control | Threads | Runs on | Demonstrates |
|---|---|---|---|
| Run Async | pool (= CPU count) | background | normal operation |
| Run Sync | 1 | background | single-threaded baseline |
| Run Sync on FX thread | 1 | **FX thread** | reproduces Bug 1 — the window freezes |
| Benchmark both | both | background | warmed, repeated measurement |

### A single run is not a measurement

The first attempt at comparing the modes clicked each button once, on a freshly started JVM,
and produced this:

```
Asynchronous (thread pool)    8 threads     597.2 ms
Synchronous (single thread)   1 thread      664.3 ms
```

An apparent speedup of 1.11× on eight cores — and the numbers are meaningless. The
asynchronous run happened to go **first**, so it absorbed the cost of JIT compilation and
initial heap growth. Later warm single-threaded runs in the same session completed in ~305ms,
roughly **twice as fast as the "faster" cold parallel run**.

This is why the benchmark discards warm-up rounds and reports a distribution rather than a
single figure. On the JVM, a one-shot timing measures the compiler as much as the code.

### Results

Run on 8 logical cores, 1920×1080 source, tile size 10 (20,736 tiles), 3 warm-up and 5
measured runs per mode:

```
=== Benchmark: 3 warm-up + 5 measured runs per mode ===
(measuring the processing pipeline only - tiles are counted, not drawn)
Synchronous (single thread)   1 thread   min   135.3  median   145.9  mean   163.4 ms
Asynchronous (thread pool)    8 threads  min    56.8  median    62.6  mean    68.6 ms
Speedup (median): 2.33x on 8 cores
```

Reproduce with:

```powershell
.\mvnw.cmd javafx:run "-Dapp.benchmark=true"
```

#### What the consumer had to be changed to

An earlier version of this benchmark drew every tile to the canvas, and reported 376.6ms sync
against 172.1ms async — **2.19×**. Bounding the queue made that measurement invalid: once
producers block on a full queue, a run that feeds the canvas is limited by how fast the FX
thread drains, and because both modes share one consumer, the ratio would converge on the
consumer's rate no matter how much parallelism the producers achieved. The benchmark now
publishes to a counting sink that cannot be a bottleneck.

That change is also a small piece of evidence in its own right. Removing `toFXImage` from the
measured path cut both absolute figures by more than half but moved the *ratio* by almost
nothing, 2.19× to 2.33×. Had pixel conversion been the constraint, taking it out would have
changed the ratio. It didn't, so it wasn't.

### Why it is 2.3×, not 8×

The parallel path is unambiguously faster, but parallel efficiency is only about 27%. That
gap is the interesting part, and it is explained by the workload rather than by the threading:

- **Task granularity is far too fine.** Each of the 20,736 tasks filters a 10×10 tile — 100
  pixels. Submission, `Future` bookkeeping, and queue contention are significant relative to
  that much work. This is the single biggest lever, and it is still open.
- **The workload is allocation-bound, not compute-bound.** `GreyScaleFilter` calls
  `new Color(g, g, g)` **per pixel** — over two million short-lived objects per run — and each
  tile additionally allocates an output `BufferedImage` and a `WritableImage`. Allocation and
  garbage collection are shared resources; they do not scale with core count.
- **Memory bandwidth is shared.** Copying pixels is memory-bound work, and memory bandwidth
  does not multiply with cores the way arithmetic throughput does.
- **The spread between min and median is GC noise.** Sync ranges 277–384ms and async 97–172ms
  across five runs; collection pauses land unevenly across them.

The honest summary: parallelism delivered a real ~2–3× improvement, and the remaining headroom
is blocked by allocation behaviour and task granularity, not by the thread pool. Fixing
`GreyScaleFilter` to write directly to the raster and coarsening the tiles would very likely
move this number substantially — which is why both remain on the list below.

---

## Bug 4 — Edge tiles silently dropped

Recorded separately from the three above because it is **not** a concurrency bug. It was found
while reading the tiling loop during the same review, deferred as a known issue, and fixed
afterwards.

### The original code

Both the sequential and parallel paths sized their grid with integer division:

```java
int numHorizontalImages = image.getWidth()  / num;
int numVerticalImages   = image.getHeight() / num;

for (int i = 0; i < numHorizontalImages; i++){
    for (int j = 0; j < numVerticalImages; j++){
        BufferedImage subImage = image.getSubimage(i*num, j*num, num, num);
        ...
    }
}
```

### What happened

Integer division truncates, so the grid stops short of the right and bottom edges whenever the
tile size does not divide the dimension evenly. On a 1920×1080 source with tile size 64 the
grid is 30 × 16 tiles, covering 1920×1024 — the bottom 56 rows are never filtered and never
drawn. Since the canvas is cleared before each run, those pixels remain blank rather than
merely unfiltered.

The failure degrades sharply as tiles get coarser. A 101×97 image with tile size 100 produces a
1 × 0 grid: no tiles at all, no output, no error.

The defect was latent rather than visible. The bundled sample is 1920×1080 and the default tile
size is 10, so the shipped configuration divides evenly and covers every pixel. Any other image
— or the coarser tiling suggested by the performance notes above — would have exposed it.

### The fix

Ceiling division to size the grid, and a clamp on each tile's dimensions:

```java
int columns = Math.ceilDiv(image.getWidth(),  num);
int rows    = Math.ceilDiv(image.getHeight(), num);
```

```java
private static BufferedImage tileAt(BufferedImage image, int x, int y, int num){
    int width  = Math.min(num, image.getWidth()  - x);
    int height = Math.min(num, image.getHeight() - y);
    return image.getSubimage(x, y, width, height);
}
```

The two changes are a pair and must land together. Ceiling division alone yields a final column
and row that begin inside the image but extend beyond it, and `getSubimage` throws
`RasterFormatException` for a region outside the raster. The grid grows to reach the edge; the
clamp keeps the last tile within it.

`Math.ceilDiv(int, int)` was added in JDK 18. The older `(a + b - 1) / b` idiom is correct for
positive operands but overflows near `Integer.MAX_VALUE`.

### Consequences elsewhere

With edge tiles clamped, a tile is no longer guaranteed to be `num × num`:

- `ImageData` now carries `tile.getWidth()` / `tile.getHeight()` instead of `num` twice.
- `GreyScaleFilter` required no change — it already derived its dimensions from its input.
- `DrawMultipleImagesOnCanvas` required no change — it already drew each tile at the explicit
  width and height carried on the tile.
- The tile-count label in the UI shared the same integer-division assumption and would have
  under-reported; it now uses `Math.ceilDiv` so the count matches the grid actually built.

Only the producer needed modification, which supports the original producer/consumer split
being sound even though the loop bounds within it were not.

### Verification

Off-by-one bounds are poorly suited to verification by inspection, so the tiling loop was
extracted and executed against a coverage grid: an `int[height][width]` incremented once per
pixel per tile, then checked for pixels covered zero times (the original bug) and pixels covered
more than once (overlapping tiles, which would waste work and, with a non-idempotent filter,
corrupt output). Testing only for gaps would pass a fix that overlapped.

| Case | Grid | Result | Pixels dropped by the old loop |
|---|---|---|---|
| 1920×1080, tile 10 (default) | 192 × 108 | pass | 0 (0.00%) |
| 1920×1080, tile 64 | 30 × 17 | pass | 107,520 (5.19%) |
| 1000×1000, tile 3 | 334 × 334 | pass | 1,999 (0.20%) |
| 101×97, tile 100 | 2 × 1 | pass | 9,797 (100%) |
| 7×5, tile 1 | 7 × 5 | pass | 0 (0.00%) |

### Reproducing it visually

A second sample, `test-odd.jpg`, ships at **1023×769** — dimensions chosen so that they are not
a multiple of any round tile size. Both the image and the tile size are selectable at launch:

```powershell
.\mvnw.cmd javafx:run "-Dapp.image=/com/image/imageprocessing/io/test-odd.jpg" "-Dapp.tile=100"
```

At tile size 100 this yields an 11 × 8 grid whose final column is 23px wide and whose final row
is 69px tall, and the rendered output reaches every edge. The previous loop produced a 10 × 7
grid covering 1000×700, leaving an L-shaped band along the right and bottom edges undrawn.

Both loops rendered over the same source, with magenta marking pixels no tile ever covered (on
the live canvas they are simply never drawn, and stay blank):

| Integer division — 10 × 7 grid, 70 tiles | `ceilDiv` + clamp — 11 × 8 grid, 88 tiles |
|---|---|
| ![Magenta band along the right and bottom edges where tiles were never drawn](images/edge-tiles-before.png) | ![Greyscale output reaching every edge](images/edge-tiles-after.png) |

A coarse tile size is what makes the demonstration legible: the dropped strip can never exceed
`num - 1` pixels, so at the default tile size of 10 the loss is under 10px — a significant part
of why the defect went unnoticed.

### Takeaway

The concurrency machinery surrounding this loop was correct throughout, and distributed the
wrong set of tiles with complete reliability. A correct parallel decomposition still depends on
correct sequential arithmetic; parallelism affects how fast a result arrives, not whether it is
right.

---

## Open issues

Known and deliberately deferred, to keep the change set reviewable:
- **Task granularity is too fine.** `num` is the tile *size*, not a count, despite parameter
  names such as `numHorizontalImages`. Tile size 10 on a 1920×1080 image produces **20,736 tasks
  of 100 pixels each**, where submission and `Future` overhead dominate the actual work.
  **Now measured rather than suspected:** holding the image, the filter and the total pixel
  work constant and varying only tile size gives 2.33× at tile 10, 3.25× at 50, 5.57× at 100,
  and 4.71× at 240 — the parallel path alone improving from 62.6ms to 37.1ms. The fall-off at
  240 is load imbalance, only 40 tasks across 8 threads. The default of 10 is kept because
  small tiles are what makes progressive rendering visible, which is the demo's purpose.
- **`GreyScaleFilter` is slow and subtly incorrect.** Per-pixel `getRGB`/`setRGB` route through
  the `ColorModel`; `new Color(g,g,g)` allocates an object per pixel; and writing an sRGB value
  into a `TYPE_BYTE_GRAY` image, whose colour space is linear, applies a gamma conversion. The
  byte actually stored bears little resemblance to the computed luminance: 54 is stored as 9,
  128 as 55, 255 as 253. `getRGB` converts back on the way out, which is why the rendered
  output looks correct and the defect is invisible without reading the raster directly.

  Note that direct raster access is **not** on its own the fix: writing 54 into the raster
  would make `getRGB` report about 136, a visibly brighter image. The output type has to move
  to a packed sRGB type such as `TYPE_INT_RGB`, which removes the colour-model conversion and
  the per-pixel allocation together.
- **`sendImage()` is an unimplemented stub** — filtered output is never written to disk.
