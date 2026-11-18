# Umbrix Code Guidelines, Architecture Standards & Engineering Practices

> **Scope:** Architectural standards, memory management rules, coordinate mathematics, and code conventions for **Umbrix**, establishing core system invariants and engineering practices across all modules.

---

## Table of Contents
- [1. Architectural Foundation: Clean Architecture & SOLID](#1-architectural-foundation-clean-architecture--solid-in-systems-programming)
- [2. SOLID Principles & Service Responsibility Matrix](#2-solid-principles-in-practice)
- [3. Explicit Dependency Injection & Composition Root](#3-dependency-injection--service-lifecycle)
- [4. Zero-Boxing & High-Performance Memory Discipline](#4-zero-boxing--high-performance-memory-discipline)
- [5. Tri-Mode Viewport Geometry & Coordinate Mathematics](#5-viewport-geometry--resizability-implementation)
- [6. Tri-Tier Concurrency & Threading Subsystem](#6-concurrency--threading-strategy)
- [7. Naming Conventions & Code Style Standards](#7-naming-conventions--code-style)
- [8. Functional Patterns & Idiomatic Scala 3 Templates](#8-functional-patterns--idiomatic-scala-3-templates)
- [9. Error Management & Forensic Diagnostic Reporting](#9-error-management--forensic-diagnostic-reporting)
- [10. Internal Workstation Logging Standards (SLF4J 2.x)](#10-internal-workstation-logging-standards)
- [11. Standalone Library Readiness & Modular Decoupling](#11-standalone-library-readiness--modular-decoupling)
- [12. Off-Heap Piece Table Architecture for Future Editing](#12-off-heap-piece-table-architecture-for-future-editing)
- [13. UI Presentation, Theming & Interaction Standards](#13-ui-presentation-theming--interaction-standards)

---

## 1. Architectural Foundation: Clean Architecture & SOLID in Systems Programming

Umbrix is built on the **Ports and Adapters (Hexagonal Architecture)** pattern combined with **"Functional Core, Imperative Shell"**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PRESENTATION LAYER (Adapters)                     │
│   • Compose Desktop UI (Kotlin) / JetBrains Jewel (IntelliJ New UI)         │
│   • ViewportRenderer (Direct Skia GPU Blitting via Skiko)                   │
│   • McpJsonRpcTransport (JSON-RPC 2.0 over stdio, SSE, or Named Pipe)       │
└──────────────────────────────────────▲──────────────────────────────────────┘
                                       │ Port Interfaces (Commands / State Flows)
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                        APPLICATION & DOMAIN CORE (Pure JVM)                 │
│                                                                             │
│   [Coordinators & Orchestrators]                                            │
│   • DocumentSession (Lifecycle of an opened log document)                   │
│   • SearchCoordinator (Dispatches partition workers & aggregates results)   │
│   • ForensicsCoordinator (SimHash clustering & causal trace assembly)       │
│                                                                             │
│   [Functional Domain Core]                                                  │
│   • Zero-allocation Opaque Domain Types (LineIndex, ByteOffset, EpochMicros)│
│   • Pure Query AST (Filter trees, boolean expressions, literal matchers)    │
│   • ViewportCoordinateSystem (Pure mathematical translation, 0-cost math)   │
└──────────────────────────────────────▲──────────────────────────────────────┘
                                       │ Port Interfaces (Readers / Indexers)
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                       INFRASTRUCTURE LAYER (Adapters)                       │
│   • MemoryMappedLogReader (FFM Arena.ofShared continuous mapping)           │
│   • PositionalLogReader (Win32 Overlapped IOCP / POSIX pread)               │
│   • AvxVectorScanner (Java Vector API AVX-512 / AVX2 primitive kernels)     │
│   • Re2AutomatonMatcher (Linear-time regex verification)                    │
│   • LangChain4jClient (Local Ollama / Enterprise Cloud LLM gateway)         │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. SOLID Principles in Practice

### S — Single Responsibility Principle (Eliminating God Objects)
Monolithic "God Objects" (e.g., a 3,000-line manager handling file reads, newline scanning, UI event dispatching, and search) are **strictly prohibited**. Every service must have one discrete axis of change:

| Service / Port | Single Responsibility | What it MUST NOT Do |
| :--- | :--- | :--- |
| **`LogStorageReader`** | Reading raw off-heap bytes from storage or virtual memory. | Parsing text, knowing about newlines, or formatting output. |
| **`PhysicalLineIndexer`** | Detecting `0x0A` delimiters and building the Two-Level Sparse Index. | Searching strings, parsing timestamps, or touching the UI. |
| **`EventBoundaryClassifier`**| Classifying multi-line event boundaries into an off-heap `RoaringBitmap`. | Reading disk files or calculating pixel positions. |
| **`TemporalCheckpointIndex`**| Sampling timestamps and executing $O(\log N)$ binary wall-clock seeks. | Rendering timeline graphics or running regexes. |
| **`SimdSearchEngine`** | Executing AVX vector scans and linear-time regex verification. | Opening files or managing viewport scrolling. |
| **`ViewportCoordinateSystem`**| Translating 64-bit line numbers to and from screen pixel coordinates. | Calling Skia draw commands or allocating memory. |
| **`DlpSanitizer`** | Sanitizing PII and secrets via Shannon entropy and regex masking. | Transmitting network requests or talking to LLMs. |

### O — Open/Closed Principle
- Core interfaces (ports) are open for extension by implementing new adapters:
  - Add a remote S3 or Kubernetes stream reader by implementing `LogStorageReader` without altering `PhysicalLineIndexer`.
  - Add a new AI model provider by implementing `AiInferenceGateway` without altering `ForensicsCoordinator`.

### L — Liskov Substitution Principle
- Storage adapters (`MemoryMappedLogReader` and `PositionalLogReader`) must strictly satisfy the `LogStorageReader` contract: zero-copy off-heap reads, deterministic bounds checking, and graceful error propagation without surprise side effects or unchecked crashes.

### I — Interface Segregation Principle
- Clients must not be forced to depend on interfaces they do not use.
- Prefer narrow, capability-based traits (e.g., `RandomAccessReader`, `SequentialStreamReader`, `CloseableResource`) rather than broad monolithic abstractions.

### D — Dependency Inversion Principle
- High-level domain services depend exclusively on abstract port interfaces (`LogStorageReader`, `VectorScanner`), never on concrete operating system APIs or third-party libraries (`MemorySegment`, `kernel32.dll`, `RE2J`).

---

## 3. Dependency Injection & Service Lifecycle

### Strict Rule: No Runtime Reflection DI
- **Do not introduce Spring, Guice, Micronaut, or runtime reflection.**
- Runtime DI containers add hundreds of megabytes of classpath overhead, inflate application boot times by 300–800ms, and complicate native `jpackage` / `jlink` runtime tree-shaking.

### Explicit Compile-Time Constructor Wiring
All dependencies are injected explicitly via class constructors. The application composition root in `umbrix-app` wires singletons deterministically:

```scala
package umbrix.core.bootstrap

import umbrix.core.io.*
import umbrix.core.index.*
import umbrix.core.search.*
import java.nio.file.Path

final class UmbrixApplicationContext(
  val targetFilePath: Path,
  val sessionConfig: SessionConfig
) extends AutoCloseable {

  // 1. Storage Infrastructure Adapter
  val storageReader: LogStorageReader = {
    if (sessionConfig.isLiveTailing) {
      PositionalLogReader.open(targetFilePath)
    } else {
      MemoryMappedLogReader.open(targetFilePath)
    }
  }

  // 2. Indexing Services
  val lineIndexer: PhysicalLineIndexer = PhysicalLineIndexer(storageReader)
  val eventClassifier: EventBoundaryClassifier = EventBoundaryClassifier(storageReader, lineIndexer)
  val temporalIndex: TemporalCheckpointIndex = TemporalCheckpointIndex(storageReader, lineIndexer)

  // 3. Search Engine
  val searchEngine: SimdSearchEngine = SimdSearchEngine(storageReader, lineIndexer)

  override def close(): Unit = {
    storageReader.close()
    lineIndexer.close()
  }
}
```

---

## 4. Zero-Boxing & High-Performance Memory Discipline

### Rule 1: Scala 3 Opaque Types for Domain Identifiers
Never wrap primitive integers and longs in standard JVM `case class` wrappers. Use Scala 3 **opaque types** to gain 100% compile-time type safety with **zero runtime memory allocation and zero boxing**:

```scala
package umbrix.core.types

opaque type LineIndex = Long
object LineIndex {
  inline def apply(rawLineIndex: Long): LineIndex = rawLineIndex
  extension (lineIndex: LineIndex) {
    inline def value: Long = lineIndex
    inline def +(lineOffset: Long): LineIndex = lineIndex + lineOffset
    inline def -(otherLineIndex: LineIndex): Long = lineIndex - otherLineIndex.value
  }
}

opaque type ByteOffset = Long
object ByteOffset {
  inline def apply(rawByteOffset: Long): ByteOffset = rawByteOffset
  extension (byteOffset: ByteOffset) {
    inline def value: Long = byteOffset
    inline def +(byteOffsetDelta: Long): ByteOffset = byteOffset + byteOffsetDelta
  }
}

opaque type EpochMicros = Long
object EpochMicros {
  inline def apply(rawEpochMicros: Long): EpochMicros = rawEpochMicros
  extension (epochMicros: EpochMicros) {
    inline def value: Long = epochMicros
  }
}
```

### Rule 2: No Generic JVM Collections on Hot Paths
- **Forbidden on Hot Paths:** `scala.collection.immutable.List[Long]`, `java.util.ArrayList<Long>`, `Map[Long, Any]`.
  - Storing 10 Million lines in a `List[Long]` allocates 10 Million `java.lang.Long` heap objects + 10 Million `::` cons cells $\approx \mathbf{480\text{ MB}}$ of heap garbage.
- **Mandated Alternatives:**
  - Flat primitive arrays: `Array[Long]`.
  - Off-heap direct memory: `java.lang.foreign.MemorySegment`.
  - Compressed bitsets: `org.roaringbitmap.longlong.Roaring64NavigableMap` (64-bit `LineIndex` / `ByteOffset`) or `org.roaringbitmap.RoaringBitmap` (32-bit).
  - High-performance primitive collections: `it.unimi.dsi.fastutil.longs.{LongArrayList, Long2IntOpenHashMap, Long2LongOpenHashMap}`.

### Rule 3: Zero `new String(...)` During Ingestion and Search
- Raw log bytes stay in off-heap memory (`MemorySegment`).
- Newline detection, literal searches, regex matching, and SimHash generation operate directly on raw `byte` pointers in `MemorySegment`.
- `String` objects are allocated **strictly on-demand for the ~50 lines currently visible on screen** during viewport drawing.

### Rule 4: Buffer Reuse & Object Pooling
- For live tailing, decompression, and vector scratchpads, pre-allocate reusable direct buffers per worker thread using `Arena.ofConfined()`.
- Never allocate temporary byte arrays ( `new Array[Byte](65536)` ) inside read or search loops.

---

## 5. Viewport Geometry & Resizability Implementation

The viewport layout engine supports three distinct modes to balance performance and presentation:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             TRI-MODE VIEWPORT                               │
├───────────────────────┬──────────────────────────┬──────────────────────────┤
│ COMPACT MODE          │ EXPANDABLE MODE (Default)│ FULL WRAP MODE           │
├───────────────────────┼──────────────────────────┼──────────────────────────┤
│ Row Height: Fixed 20px│ Row Height: Base 20px    │ Row Height: Dynamic      │
│ Long lines: Clamped   │ Unfolded lines: Inlined  │ Wrapped: Soft wrap       │
│ Coordinates: O(1)     │ Coordinates: O(log k)    │ Coordinates: Bounded LRU │
│ Target: 60–120 FPS    │ Target: 60 FPS           │ Target: 30–60 FPS        │
└───────────────────────┴──────────────────────────┴──────────────────────────┘
```

### Mode 2 (Expandable) Coordinate Math
In Expandable Mode, base rows are 20px. When a user clicks a drop-down chevron `[⯈]`, the expanded line height is registered in an off-heap map:
$$\text{expandedRows}: \text{it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap}$$

To compute the $Y$ position of line $L$:
$$Y(L) = L \times 20 + \sum_{e < L, \, e \in \text{keys}(\text{expandedRows})} (\text{height}(e) - 20)$$
Because users rarely expand more than a few dozen lines at once ($k < 50$), binary searching the sorted expanded line keys takes $<10\text{ nanoseconds}$, preserving 60 FPS scrolling.

### Hard Safety Clamp for Giant Lines
Regardless of mode, any single line exceeding **4,096 characters** must be clamped during Skia text shaping:
```kotlin
const val MAX_SHAPED_CHARS = 4096

fun clampForDisplay(rawText: String): String {
    return if (rawText.length > MAX_SHAPED_CHARS) {
        rawText.substring(0, MAX_SHAPED_CHARS) + " [… +${rawText.length - MAX_SHAPED_CHARS} chars clamped]"
    } else {
        rawText
    }
}
```

---

## 6. Concurrency & Threading Strategy

Umbrix adopts a **Tri-Tier Concurrency Model** aligned with JDK 25:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      TRI-TIER CONCURRENCY SUBSYSTEM                         │
├───────────────────────┬──────────────────────────┬──────────────────────────┤
│ CONCURRENCY TIER      │ THREAD PRIMITIVE         │ ARCHITECTURAL ROLE       │
├───────────────────────┼──────────────────────────┼──────────────────────────┤
│ Tier 1: SIMD Compute  │ Pinned Platform Threads  │ 1 dedicated thread per   │
│                       │ (Thread.ofPlatform())    │ physical CPU core. 100%  │
│                       │                          │ vector unit utilization. │
├───────────────────────┼──────────────────────────┼──────────────────────────┤
│ Tier 2: I/O & Network │ JDK 25 Virtual Threads   │ Lightweight M:N fibers   │
│                       │ (StructuredTaskScope)    │ for remote tailing, SSH, │
│                       │                          │ K8s sockets, and timers. │
├───────────────────────┼──────────────────────────┼──────────────────────────┤
│ Tier 3: UI & Skia     │ Skiko Main Event Loop &  │ State flows, animations, │
│                       │ Kotlin Coroutines        │ and GPU canvas drawing.  │
└───────────────────────┴──────────────────────────┴──────────────────────────┘
```

> [!WARNING]
> **Never run CPU-bound SIMD vector kernels on Virtual Threads.** Virtual threads are optimized for blocking I/O; pinning a virtual thread to a vector unit starves the underlying ForkJoin carrier pool.

---

## 7. Naming Conventions & Code Style Standards

### Package Organization
```
umbrix.core.types       # Opaque types and value primitives
umbrix.core.io          # Storage readers (MemoryMapped, Positional)
umbrix.core.index       # Two-level sparse index, temporal table
umbrix.core.search      # SIMD scanner, RE2J automaton matcher
umbrix.core.model       # Pure query AST, filter models, configurations
umbrix.ui.viewport      # Virtual64Viewport, coordinate systems
umbrix.ui.canvas        # Skia direct rendering, minimap seismograph
umbrix.ui.components    # Jewel IntelliJ UI wrappers, dialogs, drawers
umbrix.mcp.protocol     # JSON-RPC 2.0 transports (stdio, SSE, Named Pipe)
umbrix.mcp.tools        # Exposed MCP agent tools
umbrix.mcp.ai           # SimHash clustering, Drain mining, DLP redactor
```

### Identifier & Variable Naming
- **Descriptive Variable Names:** Name variables thoroughly and descriptively. Avoid cryptic abbreviations (avoid `n`, `pSize`, `rfRes`, `s1`, `buf`). Use clear names: `bytesTransferred`, `fileSizePointer`, `readFileStatus`, `initialSlice`, `threadScratchBuffer`.
- **Interfaces / Ports:** Named by capability: `LogStorageReader`, `VectorScanner`, `SearchEngine`, `AiInferenceGateway`.
- **Implementations / Adapters:** Named by underlying mechanism: `MemoryMappedLogReader`, `PositionalLogReader`, `Win32PositionalHandle`.
- **Value Snapshots & DTOs:** Suffix with `Snapshot` or `Spec`: `ViewportSnapshot`, `QuerySpec`, `ClusterResult`.
- **Opaque Types:** PascalCase (`LineIndex`, `ByteOffset`).
- **SIMD Kernels:** Prefix with instruction set: `avx512ScanNewlines`, `neonScanNewlines`, `scalarScanNewlines`.

### High-Value Comments & Signal-to-Noise Ratio
- **Architectural Docstrings:** Classes, ports, native adapters, and critical algorithms must feature docstrings explaining operational roles, zero-heap memory invariants, and fault mitigations.
- **Explain Non-Obvious Rationale:** Detail hardware SIMD mechanics, OS downcall behaviors (e.g., Win32 Overlapped section locks, POSIX truncation `SIGBUS` immunity, UTF-8 byte boundary invariance), or mathematical lookup formulas.
- **Eliminate Useless / Filler Comments:** Avoid comments that merely restate code syntax.

---

## 8. Functional Patterns & Idiomatic Scala 3 Templates

Core syntax invariants are detailed across this specification (specifically Sections 4, 7, and 8). Below are canonical patterns:

### Pattern 1: Short-Circuiting via Tail-Recursion (`@tailrec`)
```scala
package umbrix.core.search

import scala.annotation.tailrec

final class ScanRangeSearch(val searchStride: Long) {
  @tailrec
  def findFirstMatch(currentOffset: Long, maximumOffset: Long, isMatchPredicate: Long => Boolean): Option[Long] = {
    if (currentOffset >= maximumOffset) {
      None
    } else if (isMatchPredicate(currentOffset)) {
      Some(currentOffset)
    } else {
      findFirstMatch(currentOffset + searchStride, maximumOffset, isMatchPredicate)
    }
  }
}
```

### Pattern 2: Zero-Intermediate Collection Pipelines with `.view`
```scala
// Preferred: Evaluated lazily with zero intermediate collection heap overhead
val visibleTimestamps: List[Long] = rawEventSequence.view
  .filter(_.isError)
  .map(_.timestampEpochMicros)
  .take(50)
  .toList
```

### Pattern 3: Strict Curly Brace Class & Method Syntax
```scala
package umbrix.core.io

import java.lang.foreign.MemorySegment
import java.nio.file.Path

final class MemoryMappedLogReader(val filePath: Path, val mappedSegment: MemorySegment) extends LogStorageReader {
  def readSlice(byteOffset: Long, sliceLength: Int): MemorySegment = {
    if (byteOffset < 0L || sliceLength <= 0) {
      throw new IllegalArgumentException(s"Invalid slice: offset=$byteOffset, length=$sliceLength")
    } else {
      mappedSegment.asSlice(byteOffset, sliceLength)
    }
  }
}
```

---

## 9. Error Management & Forensic Diagnostic Reporting

1. **Graceful Degradation & Fallback:** When platform-specific native calls fail (Win32 or POSIX downcalls), the subsystem falls back transparently to a safe universal standard (e.g., `NioPositionalHandle` or standard FFM `segment.load()`) with structured warning logs rather than crashing the application.
2. **Crash & Forensic Error Reports:** The workstation must generate comprehensive diagnostic error reports upon encountering an unrecoverable fault:
   - System state snapshot: OS version, architecture, JVM version, CPU SIMD vector width.
   - Storage state: active file path, file size, mapping mode, last queried byte offset.
   - Resource metrics: process RSS memory, direct off-heap memory usage, active arenas.
   - Structured error payload: timestamped stack trace, error category code, and forensic causal context.
3. **Structured Failure Propagation:** Domain coordinators use Scala 3 `Either` or dedicated typed ADTs (`StorageError`, `IndexCorruptedError`) to model predictable failure modes without throwing untyped runtime exceptions across module boundaries.

---

## 10. Internal Workstation Logging Standards

1. **API:** Standardize strictly on **SLF4J 2.x** (`org.slf4j.Logger`).
2. **Asynchronous Execution:** All log messages must be dispatched through an asynchronous ring-buffer appender (Logback `AsyncAppender` or tinylog async writer). Log writes must never block UI rendering or SIMD indexing threads.
3. **Dual Output Target:** Support an asynchronous Console Appender (for developers and CLI runs) and an asynchronous Rolling File Appender writing to `~/.umbrix/logs/umbrix.log`.
4. **No String Interpolation on Hot Paths:** Use parameterized logging (`logger.debug("Indexed chunk in {} ms", duration)`) to avoid String concatenation when logging levels are disabled.

---

## 11. Standalone Library Readiness & Modular Decoupling

To ensure core components can be published as independent open-source libraries:
- **`umbrix-vfs`:** Has ZERO dependencies on UI, indexing, or search. Depends only on standard Java 25 FFM API and OS system calls.
- **`umbrix-simd`:** Depends only on `jdk.incubator.vector` and raw `MemorySegment` buffers.
- **`umbrix-index`:** Depends on `umbrix-vfs` and `org.roaringbitmap`. Knows nothing about Compose or Jewel.
- **`umbrix-core`:** Coordinates domain logic and query execution by composing the underlying libraries.

---

## 12. Off-Heap Piece Table Architecture for Future Editing

When implementing file editing or log annotation:
1. **Never mutate the original memory map.** Treat original file bytes as strictly immutable.
2. **Piece Table Representation:** Model the document as a sequence of piece descriptors referencing either the `ORIGINAL` mapped file or an off-heap `APPEND` buffer.
3. **Async Write-Back:** Saving mutations executes asynchronously via kernel slicing (`TransmitFile`, `copy_file_range`, `clonefile`) to a temporary file before atomic replacement.

---

## 13. UI Presentation, Theming & Interaction Standards

1. **Centralized Semantic Theming (`UmbrixThemeSpec`):**
   - All component colors must reference semantic tokens in the active `UmbrixThemeSpec` via `LocalUmbrixTheme.current`.
   - Hardcoded `Color(0x...)` literals inside composables are strictly prohibited.
2. **Monospace Typography Hierarchy (`EditorTypography`):**
   - Monospaced editor and line number typography must use `EditorTypography.editorFontFamily` to ensure consistent fallback across Windows (`Cascadia Code`, `Consolas`), macOS (`SF Mono`, `Menlo`), and Linux (`DejaVu Sans Mono`).
3. **Global Keyboard Routing (`WorkstationKeyDispatcher`):**
   - Window-level keyboard shortcuts (`Ctrl+F`, `Ctrl+G`, `Ctrl+C`, `F3`, `Shift+F3`, `Escape`) are registered centrally via `WorkstationKeyDispatcher.onGlobalKeyEvent`.
   - Shortcuts must be focus-aware: text copy (`Ctrl+C`) only captures the viewport row when a line is selected, falling through to default `TextField` copy behavior otherwise.
4. **Non-Blocking Modal Overlays & Unified State (`ActiveDialog`):**
   - Dialogs (such as `GoToTimestampDialog`, `SettingsDialog`, managed via the `ActiveDialog` sealed state hierarchy) render as lightweight in-tree modal overlays with `UmbrixModalCard` and semi-transparent scrims, avoiding native OS window parenting issues and preserving keyboard focus.
5. **Zero-GC Viewport Rendering Invariant:**
   - The viewport must never instantiate `java.lang.String` objects for off-screen rows. String shaping is limited strictly to visible rows clamped to 4,096 characters.
