# Umbrix System Design & Architecture Tracker

> **Document Purpose:** Central architectural design reference tracking system topology, component contracts, data pipelines, error management standards, and design evolution across all Umbrix modules.

---

## Table of Contents
- [1. High-Level Architectural Topology](#1-high-level-architectural-topology)
- [2. Subsystem Contracts & Module Boundaries](#2-subsystem-contracts--module-boundaries)
- [3. Data Pipelines & Memory Invariants](#3-data-pipelines--memory-invariants)
  - [3.1 Dual-Path Storage Pipeline](#31-dual-path-storage-pipeline)
  - [3.2 Two-Tier Progressive Indexing Pipeline](#32-two-tier-progressive-indexing-pipeline)
  - [3.3 Viewport Search Highlighting, Navigation & Window Keyboard Routing](#33-viewport-search-highlighting-navigation--window-keyboard-routing)
  - [3.4 Zero-Heap Regex Scanning & OffHeapCharSequence Encoding Architecture](#34-zero-heap-regex-scanning--offheapcharsequence-encoding-architecture)
  - [3.5 GPU Seismograph Minimap Density Pipeline](#35-gpu-seismograph-minimap-density-pipeline)
  - [3.6 Configurable Log Language Profiles & Exception Recognition](#36-configurable-log-language-profiles--exception-recognition)
  - [3.7 Pluggable Asynchronous Log Analyzer SPI](#37-pluggable-asynchronous-log-analyzer-spi)
  - [3.8 Viewport Layout Virtualization, Kinetic Scrolling, Tri-Mode Layout & Selection](#38-viewport-layout-virtualization-continuous-kinetic-scrolling-tri-mode-layout--text-selection)
  - [3.9 Persistent Sidecar Index Disk Cache & 7-Day Auto-Eviction Pipeline](#39-persistent-sidecar-index-disk-cache--7-day-auto-eviction-pipeline-persistentindexcache)
  - [3.10 Virtual Scratchpad Buffers, Persistence & Session Restoration Pipeline](#310-virtual-scratchpad-buffers-persistence--session-restoration-pipeline)
  - [3.11 Continuous Bidirectional Selection Edge-Follow Scroll Engine](#311-continuous-bidirectional-selection-edge-follow-scroll-engine)
  - [3.12 Temporal Navigation, Dual-Handle Range Scrubber & Latency Gap Architecture](#39-pillar-4-temporal-navigation-dual-handle-range-scrubber--latency-gap-architecture)
  - [3.13 Dynamic Theme Engine Architecture](#310-workstation-ui-overhaul--dynamic-theme-engine-architecture)
  - [3.14 Typography & Font Configuration Subsystem](#311-typography--font-configuration-subsystem-ui-overhaul-phase-3)
  - [3.15 Hamburger Menu Subsystem](#312-hamburger-menu-subsystem-ui-overhaul-phase-4)
  - [3.16 Modal Dialog Standardization Subsystem](#314-modal-dialog-standardization-subsystem-ui-overhaul-phase-5)
  - [3.17 Settings Expansion & Atomic Disk Persistence](#315-settings-expansion--atomic-disk-persistence-ui-overhaul-phase-6)
  - [3.18 Welcome Screen & Toast Notification Subsystems](#316-welcome-screen--toast-notification-subsystems-ui-overhaul-phase-7-pack-a)
  - [3.19 Command Palette & Context Menu](#317-universal-multi-persona-command-palette--log-row-context-menu-ui-overhaul-phase-7-pack-b)
  - [3.20 Forensic Timeline Reconstruction & Scratchpads](#318-forensic-timeline-reconstruction-investigation-notebooks--in-app-scratchpads-ui-overhaul-phas)
  - [3.21 Natural Multi-Word Phrase Matching & Viewport Needle Extraction](#37-natural-multi-word-phrase-matching--viewport-needle-extraction)
  - [3.22 Embedded Model Context Protocol (MCP) Server & AI Assistant](#319-embedded-model-context-protocol-mcp-server--forensic-ai-assistant-subsystem)
  - [3.23 Plugin Architecture & Extensibility SDK Subsystem](#323-plugin-architecture--extensibility-sdk-subsystem)
- [4. Error Management & Forensic Reporting Architecture](#4-error-management--forensic-reporting-architecture)
  - [4.1 Resilient Degradation Pattern](#41-resilient-degradation-pattern)
  - [4.2 Forensic Error Report Generator](#42-forensic-error-report-generator)
  - [4.3 UI Error Boundary & Recovery Handler](#43-ui-error-boundary--recovery-handler)
  - [4.4 Universal Multi-Persona Command Palette Engine](#44-universal-multi-persona-command-palette-engine)
  - [4.5 Log Viewport Row Context Action Engine](#45-log-viewport-row-context-action-engine)
  - [4.6 UI Modular Decomposition & Viewport Gesture Reliability](#46-ui-modular-decomposition--viewport-gesture-reliability)
  - [4.7 Modular UI & Multi-Pane Docking Subsystem](#47-modular-ui--multi-pane-docking-subsystem)
  - [4.8 Workstation Enhancements & Forensic Usability](#48-workstation-enhancements--forensic-usability)
  - [4.9 Native Distribution, Single-Instance IPC & Packaging Pipeline](#49-native-distribution-single-instance-ipc--packaging-pipeline)
- [5. Engineering & Code Style Disciplines](#5-engineering--code-style-disciplines)
- [6. Implementation Status & Evolution Roadmap](#6-implementation-status--evolution-roadmap)

---

## 1. High-Level Architectural Topology

Umbrix follows the **Ports & Adapters (Hexagonal)** pattern combined with **Functional Core, Imperative Shell**:

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
│   • Storage Subsystem: MemoryMappedLogReader (FFM) / PositionalLogReader    │
│   • Native Handles: Win32PositionalHandle / PosixPositionalHandle / NIO     │
│   • Vector Scanning: AvxVectorScanner (Java Vector API AVX-512 / AVX2)      │
│   • Query Verification: Re2AutomatonMatcher (Guaranteed linear-time regex)  │
│   • Diagnostics & Reporting: ForensicErrorReportGenerator                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Subsystem Contracts & Module Boundaries

| Module | Core Responsibility | Inbound Ports Used | Outbound Ports Provided |
| :--- | :--- | :--- | :--- |
| **`umbrix-vfs`** | Low-level off-heap file I/O, cross-platform downcalls, OS paging advisories, and NIO fallback. | Operating System System Calls (Win32, POSIX, NIO) | `LogStorageReader`, `PositionalIoHandle` |
| **`umbrix-simd`** | Hardware vector scanning kernels (AVX-512, AVX2, ARM NEON) for newline and substring detection. | Java Vector API (`jdk.incubator.vector`) | `VectorScanner` |
| **`umbrix-index`** | Two-Level Hierarchical Sparse Index ($16,384 \times 64$), temporal checkpoints, and piece tables. | `LogStorageReader`, `VectorScanner` | `PhysicalLineIndexer`, `SparseIndex` |
| **`umbrix-core`** | Domain types, query AST, search coordinator, event classifier (`RoaringBitmap`), error reports. | `LogStorageReader`, `PhysicalLineIndexer` | `SearchEngine`, `DocumentSession` |
| **`umbrix-mcp`** | Embedded JSON-RPC MCP server exposing forensic inspection tools to AI coding agents. | `DocumentSession`, `SearchEngine` | `McpServer` |
| **`umbrix-ui`** | Compose Desktop UI, JetBrains Jewel dark carbon components, Skia 64-bit virtual viewport. | `DocumentSession`, `ViewportCoordinateSystem` | Viewport Presentation |
| **`umbrix-app`** | Application composition root, command-line arguments, native packaging (`jpackage`). | All Subprojects | Application Executable |

---

## 3. Data Pipelines & Memory Invariants

### 3.1 Dual-Path Storage Pipeline
1. **Static Files (`MemoryMappedLogReader`):**
   - Memory-mapped via Java 25 FFM `Arena.ofShared()` + `FileChannel.map`.
   - Reads execute via instantaneous zero-copy pointer arithmetic (`segment.asSlice`).
   - Closed deterministically via `arena.close()`, immediately unmapping virtual pages and releasing kernel file locks.
2. **Active / Rotating Files (`PositionalLogReader`):**
   - Windows NT: Panama downcalls to Win32 `CreateFileW` (`FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE`) and positional `ReadFile` with `OVERLAPPED` offsets. Eliminates `ERROR_SHARING_VIOLATION` (error 32).
   - Linux & macOS: Positional `pread` downcalls to `libc`, eliminating `SIGBUS` crashes during log truncation (`copytruncate`).
   - Memory Management & Leak Prevention: Employs a pre-allocated circular off-heap ring buffer (16MB default) for transient `readSlice` calls, guaranteeing zero unbounded native allocation growth. Supports caller-scoped lifecycles via `readSlice(offset, length, arena)` and zero-allocation destination writes via `readInto`.
   - Universal Fallback: Transparent off-heap `NioPositionalHandle` via `FileChannel.read(ByteBuffer.allocateDirect)`.
3. **Paging Advisories & Working Set Eviction (`PagingAdvisory`):**
   - Pre-warms the OS page cache for sequential scans via Win32 `PrefetchVirtualMemory`, POSIX `posix_madvise(POSIX_MADV_WILLNEED)`, and standard FFM `segment.load()` fallback.
   - Proactive Native Eviction: Issues POSIX `posix_madvise(POSIX_MADV_DONTNEED)` per scanned chunk and Win32 `K32EmptyWorkingSet(GetCurrentProcess())` every ~1GB of scanned data and upon query completion/dismissal to immediately release physical RAM and drop process working set to baseline (<50MB–150MB).

### 3.2 Two-Tier Progressive Indexing Pipeline & Fused Streaming Indexer
- **Hardware-Aligned SIMD Scanning (`VectorScanner`):** Computes exact byte address misalignment `(segment.address() + offset) & (vectorByteWidth - 1L)`. Executes a scalar prologue until the memory pointer is aligned to 32/64 bytes, runs vector lanes on aligned cache-lines at 13.3–14.3 GB/s throughput, and concludes with a scalar epilogue.
- **Single-Pass Fused Ingestion (`FusedStreamingIndexer`):** Unifies Tier 1 (physical newline indexing) and Tier 2 (temporal checkpoints and event boundary classification) into a single sequential disk pass via decomposed zero-overhead helpers (`handleNewlineBoundary`, `trySampleTimestampAtLine`, `checkContinuationLine`, and `readChannelFully` for short-read safety), eliminating repeated multi-pass file reading:
  - *Mathematical Stride Harmony:* Synchronizes indexing intervals via compile-time `SparseIndex` `inline def` stride helpers (`superblockIndex`, `intraSlotIndex`, `fineScanOffset`, `intraTableOffset`, `unsignedIntraDelta`, `clampIntraDelta`) and unified zero-boxing binary search (`SparseIndex.binarySearchFloor`): Intra-Stride $K_{\text{intra}} = 64$ lines, Temporal Stride $K_{\text{temp}} = 8,192 = 128 \times 64$ lines (constructed/parsed off-heap by `TemporalCheckpointBuilder`), and Super-Stride $K_{\text{super}} = 16,384 = 256 \times 64$ lines.
  - *Vectorized Continuation Filter Gate:* For vector chunks containing newlines (`nlMask != 0L`), loads `nextByteChunk` at `currentByteOffset + 1L` and evaluates `isWhitespaceOrControl | isContinuationChar ('a', '.', 'C', 'S')`.
  - *2-Cycle POPCNT Fast Path:* If `!hasContinuationCandidate` and the match count does not cross the next 64-line intra-stride, simply increments the line counter by `bitCount(nlMask)` and decrements `linesUntilNextIntraStride` in just 2 CPU cycles, completely bypassing `TZCNT`/`BLSR` bit-decoding for ~98% of vector iterations.
  - *Double-Buffered Ping-Pong I/O:* After synchronously scanning Chunk 0 (<10ms) to unblock initial viewport rendering, allocates two 32MB buffers in `Arena.ofShared()`, asynchronously prefetching chunk $k+1$ via `FileChannel` while SIMD scans chunk $k$.
  - *Inverted Continuation Tracking:* Tracks continuation lines (multi-line stack traces) in `Roaring64NavigableMap` rather than event headers, keeping bitmap insertions near 0 for standard logs and coalescing contiguous runs via `addRange`.
- **Sub-Second Warm Opens via Tier-2 Sidecar (`.t2`):** When persistent indexing cache is enabled, writes `<fingerprint>.t2` alongside `<fingerprint>.idx`, storing `TemporalCheckpointTable` arrays and the continuation bitmap with CRC32 integrity validation. Warm file re-opens load both tiers in **<850ms** even for 211M-line files.

### 3.3 Viewport Search Highlighting, Navigation & SIMD Search Optimization
- **Fused Dual-Literal Compound SIMD Search (`scanDualLiteral`, `scanDualLiteralCaseInsensitive`):**
  - When evaluating boolean queries of two literals (`QueryAst.And(Literal(a), Literal(b))` or `QueryAst.Or(Literal(a), Literal(b))`), the search engine intercepts the compound AST and executes `searchDualLiteralParallel`.
  - Shares vector register loads across needle A and needle B: loads `vectorFirstChunk` once at offset $i$ (and folds ASCII bit-5 once for case-insensitive mode), loads `vectorLastChunkA` at $i + \text{dist}_A$, and `vectorLastChunkB` at $i + \text{dist}_B$.
  - Generates dual candidate masks in a single pass, slashing memory bus traffic and instruction retirements by ~50% compared to dual-pass evaluation.
- **Dynamic Lock-Free Work-Stealing Coordinator:**
  - `SimdSearchEngine` unifies parallel single-literal, dual-literal, and AST chunk dispatch through `dispatchParallelSearchKernel` (`ChunkScanKernel` / `ChunkWorkerState`), while `VectorScanner` centralizes alignment prologue and loop bounds via `alignmentPrologueEnd` / `vectorLoopBound`.
  - Uses dynamic work-stealing worker threads (`nextStepCursor.getAndIncrement()`) across `activeWorkers = min(workerThreads, chunkCount)` to eliminate worker thread imbalance caused by high match-density clusters or uneven thread scheduling.
  - Aggregates hits into worker-local `Roaring64NavigableMap` bitsets, combining them lock-free at the end of the query.
- **Delta-SIMD Line Resolution & Monotonic Run-Coalescing (`ChunkLineResolver`):**
  - Replaces per-match $O(\log N)$ binary searching against `TwoLevelSparseIndex`: when a new match is within 16KB of the previous match in the chunk, counts intermediate `0x0A` newlines via SIMD `VectorScanner.countByte`, resolving exact line indices in nanoseconds.
  - Coalesces consecutive line matches into single `Roaring64NavigableMap.addRange` operations, eliminating redundant bitset tree mutations.
  - Buffers non-consecutive line indices in a pre-sorted 1024-element primitive array, flushing in bulk batches.
- **Global Key Routing (`WorkstationKeyDispatcher`):** All hotkeys (`Ctrl+F`, `F3`, `Shift+F3`, `Esc`) are captured at the root `Window` level via `onPreviewKeyEvent`, ensuring immediate response regardless of child element focus.
- **Zero-Boxing Match Navigation:** `SearchResult` provides primitive `findNextLine(startLine: Long): Long` and `findPreviousLine(startLine: Long): Long` returning `-1L` when no match exists, eliminating `Option[Long]` heap object allocations during rapid navigation (F3/Shift+F3 at 5M–6M ops/sec).
- **Memoized Span Highlighting (`HighlightCache`):** Search needles are highlighted as discrete background pills (`#FF9800` for active match, `#66FFD54F` for general hits) via `AnnotatedString`. A bounded 512-entry LRU cache (`SearchHighlighter.HighlightCache`) eliminates ~15,000 temporary string and styling allocations per second during fast viewport scrolling.
- **Cooperative Search Cancellation & Query Generation Tokens:** Multi-threaded SIMD searches maintain an atomic query generation counter (`currentQueryId: AtomicLong`). Worker chunk tasks check `currentQueryId.get() == queryId && !Thread.currentThread().isInterrupted` before each 16MB chunk, aborting obsolete scans in sub-millisecond time.

### 3.4 Zero-Heap Regex Scanning & OffHeapCharSequence Encoding Architecture
`OffHeapCharSequence` adapts native `MemorySegment` slices directly to Java's `java.lang.CharSequence` interface for linear-time regex matching via Google RE2J:
- **1:1 Byte-to-Char Identity Mapping:** Character resolution executes via `(segment.get(ValueLayout.JAVA_BYTE, baseOffset + index) & 0xFF).toChar`. This treats byte values directly as 8-bit Latin-1 (ISO-8859-1) / ASCII characters.
- **Architectural Rationale for Omitting Variable-Length UTF-8 Decoding:**
  1. *Zero Heap Invariant:* Decoding variable-length UTF-8 sequences (1 to 4 bytes per code point) into Java UTF-16 code units would require dynamic memory allocation or heap-allocated `String` representations, violating the 0-byte GC heap ingestion constraint.
  2. *Strict $O(1)$ Random Access:* Constant-time index access (`charAt(i)`) is mandatory for high-throughput regex automaton state transitions. Variable-length UTF-8 requires $O(N)$ prefix scanning or auxiliary byte-to-char offset index arrays, introducing massive memory and compute overhead.
  3. *Domain-Specific Alignment:* Server logs, traces, timestamps (`2026-09-22 12:00:00`), log levels (`[INFO]`, `[ERROR]`, `[WARN]`), IP addresses, UUIDs, HTTP methods, and stack traces reside entirely within standard 7-bit ASCII and 8-bit Latin-1 ranges.
  4. *Viewport-Only UTF-8 Decoding:* Proper multi-byte UTF-8 decoding occurs strictly at the presentation boundary when rendering visible lines (~50 lines) via `PhysicalLineIndexerImpl.getLineText()`.

### 3.5 GPU Seismograph Minimap Density Pipeline
- **Fixed-Memory Density Histogram (`MinimapDensityTable`):** Computes a fixed 1,000-bin log density distribution across the document. For any file (from 1MB to 100GB+), the memory footprint is strictly bounded to $1,000 \times 4 \times 4\text{ bytes} = 16\text{ KB}$ using primitive arrays (`Array[Int]`), guaranteeing 0 bytes of GC heap bloat.
- **Multi-Channel Severity Tracking:** Each bin tracks `errorCount`, `warnCount`, `infoCount`, and `totalCount`, allowing layered multi-color density rendering in the Skia GPU canvas.
- **Sampled Bin Analysis (`MinimapScanner`):** Rather than re-reading 30GB from disk a third time and evaluating 211M lines with scalar keyword loops (which took 20-30 min), `MinimapScanner` samples 100 lines per bin via `indexer.byteOffsetForLine` and scales counts. Reads only ~25MB of off-heap data in **<50ms** across 30GB files. Full line-by-line inspection is preserved for documents $\le 100,000$ lines.
- **Scrubber Coordinate Math:** Maps canvas click/drag offsets $y \in [0, H_{\text{minimap}}]$ directly to 64-bit line indices $L \in [0, N - 1]$ in sub-millisecond time for instant scrubbing.

### 3.6 Configurable Log Language Profiles & Exception Recognition
- **Extensible SPI (`LogLanguageProfile`):** Decouples log format parsing and severity extraction from the indexing and rendering engine. Provides methods for `detectSeverity(lineText)`, `isStackTraceContinuation(lineText)`, and `extractExceptionType(lineText)`.
- **Built-in Profiles:**
  - `JavaJvmProfile`: Detects standard Log4j/Logback/SLF4J patterns, `at com.foo.Bar(Bar.java:42)` and `Caused by:` continuation lines, and fully-qualified exception class names (`NullPointerException`, `OutOfMemoryError`).
  - `PythonProfile`: Detects standard logging levels, `Traceback (most recent call last):`, `File "...", line ...`, and exception names (`ValueError`, `KeyError`).
  - `GoProfile`: Detects `panic:`, `goroutine X [running]:`, `runtime error:`, and standard zap/logrus/slog severity levels.
  - `RustProfile`: Detects `panicked at`, `stack backtrace:`, and `tracing` crate levels.
  - `SyslogProfile`: RFC 3164 / RFC 5424 severity keywords and facility headers.
- **Language Profile Registry & Auto-Detection:** Scans the initial slice of the document with all registered profiles to automatically select the best match, with user override capability in Settings.

### 3.7 Pluggable Asynchronous Log Analyzer SPI
- **Port Contract (`AsyncLogAnalyzer`):** Designed for high-performance forensic extensions (e.g. JVM GC Pause Analyzer, Heap Dump Analyzer, HTTP Latency Clusterer).
- **Zero-Contention Execution:** Analyzers run asynchronously against `LogSession` off-heap memory segments, emitting structured `AnalysisReport` summaries containing severity indicators, key-value metrics, and target line references.
- **Plugin Registry (`PluginRegistry`):** Central discovery hub for registering and querying analytical plugins, ready for future UI dashboard and panel integration.

### 3.8 Viewport Layout Virtualization, Continuous Kinetic Scrolling, Tri-Mode Layout & Text Selection
- **Tri-Mode Viewport Engine (`COMPACT`, `EXPANDABLE`, `FULL_WRAP`):**
  - Configured globally via `umbrix.ui.settings.ViewportLayoutMode` and hot-swappable in real-time from the status bar or Command Palette (`view.expand_all_events`, `view.collapse_all_events`).
  - **Compact Mode:**
    - Fixed 20px rows with horizontal scrolling enabled.
    - Zero multi-line expansion or soft wrapping, achieving maximum throughput (60–120 FPS continuous scrolling).
    - Safety clamped at 4,096 characters per line to eliminate GPU layout penalties.
  - **Expandable Mode (Default Workstation Mode):**
    - Multi-line stack trace / causal log aggregation powered by the background `EventBoundaryClassifier`.
    - Lines identified as parent event headers (`session.eventClassifier().isEventHeader(line)`) with continuations (`eventLineSpan > 1`) display an interactive gutter chevron (`▶` collapsed, `▼` expanded).
    - Collapsed events display a lightweight `[+N frames]` badge next to the header line.
    - Clicking the chevron or using Command Palette toggles inline expansion of continuation frames inside a themed, elevated border container (`colors.border`, `colors.surfaceElevated`).
  - **Full Wrap Mode:**
    - Eliminates horizontal truncation by dynamically soft-wrapping lines to the visible viewport canvas width (`softWrap = true`, `maxLines = Int.MAX_VALUE`, `heightIn(min = rowHeightDp)`).
    - Horizontal scrollbar is suppressed and viewport width expands to fit the available window.
  - **Non-Destructive Whitespace Visualization (`showWhitespaceChars`):**
    - Enabled via settings or command toggle.
    - Spaces (`' '`) are rendered as subtle middle dots (`·`) and tab characters (`'\t'`) as right arrows (`→`) in muted tone (`colors.textMuted.copy(alpha = 0.45f)`).
    - Preserves exact 1:1 character string lengths and HarfBuzz cluster alignment, avoiding coordinate distortion during mouse selection and cursor hit-testing.
- **Sub-Pixel Kinetic Viewport Virtualization:**
  - Viewport state is modeled as $\langle L_{\text{top}} \in [0, N - 1], \; \Delta y \in [0.0, h_{\text{line}}) \rangle$ where $\Delta y$ tracks sub-line fractional vertical displacement (`subLineScrollOffsetPx`).
  - Viewport lines render with `renderRowCount = visibleRowCount + 2` and a Skia translation layer `translationY = -tab.subLineScrollOffsetPx`.
  - **Unbounded Row Measurement with Hardware Clipping:** Nested row columns use `wrapContentHeight(align = Alignment.Top, unbounded = true)` inside a hardware-clipped Skia container (`Box(modifier = Modifier.weight(1f).clipToBounds())`). This eliminates the layout height truncation artifact where partially visible lines at the top/bottom of the viewport were previously suppressed and drawn blank. Partial rows are rendered in full and smoothly clipped at pixel precision by the GPU layer.
- **Exact Monospace Hit-Testing via HarfBuzz (`TextLayoutResult`):**
  - Rather than relying on font advance heuristics (`adjustedX / measuredCharWidthPx`) which accumulate rounding errors and kerning drift across long lines, each rendered row captures its live Skia `TextLayoutResult` via `onTextLayout`.
  - Mouse clicks and drag selection gestures query `layoutResult.getOffsetForPosition(Offset(localX, rowHeight / 2))` directly against the shaped HarfBuzz glyph cluster array, achieving 100% exact 1:1 character index resolution.
  - Text viewport is styled with `PointerIcon(Cursor.TEXT_CURSOR)` providing native desktop I-beam cursor feedback.
  - Selection drags use stable, clipped text-viewport coordinates instead of translated row coordinates. A drag-scoped frame loop uses bounded edge velocity to advance horizontal `ScrollState` and 64-bit vertical rows while recomputing the highlighted endpoint; canceling the gesture stops the loop. Cached layouts are checked against their virtual and physical row before hit-testing.
  - Successive primary clicks in the text viewport use the platform multi-click timeout and pointer proximity: two clicks replace an existing selection with a bounded word/punctuation span, three with the entire displayed physical log line (not a multi-line event). Subsequent drags preserve that initial unit and snap target endpoints by word or displayed line in either direction, including during edge-follow. Only the target visible row is read for word snapping, cached per gesture; changing tab/view or dragging resets the sequence. Single-click drags, gutter, minimap and scrollbar interactions remain independent.
  - Copy traverses the selected virtual rows in display order, resolving filtered rows via `FilteredLineView` and temporal rows via focus-start before the bounded 4,096-character line read. Explicit clipboard copies reject selections above 8,192 rows or 1 MiB of text instead of loading unbounded offscreen lines.
- **Decluttered Single-Row Search Bar with Sublime/IntelliJ Search Modes:**
  - `SearchBar` is streamlined into a sleek single deck to eliminate visual clutter and maximize viewport viewing space:
    1. *Search Modes:* Toggleable Match Case (`Aa` - Alt+C), Match Whole Word (`|W|` - Alt+W), and Regular Expression (`.*` - Alt+R via linear-time RE2J).
    2. *Match Stepping & Navigation:* Enter / Shift+Enter / F3 / Shift+F3 per-instance match stepping, match telemetry count, line filter toggle (`⬡ Filter Matching Lines`), and sub-file DMA export (`⤓ Export Sub-File`).
    3. *Temporal Focus Confinement:* When Temporal Focus is active, queries and match stepping are strictly confined to the selected range (`[temporalFocusStartLine, temporalFocusEndLine]`), preventing viewport coordinate disorientation and blank screens.
- **Dedicated Forensic Anomaly Stepping Pill:**
  - Shifted out of the search bar into a dedicated, clean pill centered in `BottomStatusBar` (`[⚡ Anomalies: ▲ Prev (Shift+F4)  ▼ Next (F4)]`), keeping anomaly navigation accessible without polluting text search.
- **Decluttered Status Bar with Right-Click Context Menu Customization:**
  - Displays essential information by default (`Line X  |  N lines  |  X MB`).
  - Right-click context menu enables/disables telemetry sections dynamically: Line Position, Document Size & Total Lines, Viewport Mode, Indexing Details & SIMD Width, and JVM Memory & GC Invariant.
- Token Precedence & Minimap Precision Navigation:
  - Refactored language profile token precedence so explicit delimited log level tokens (`[ERROR]`, ` ERROR `, `[WARN]`, ` WARN `, `[INFO]`, ` INFO `) evaluate prior to generic exception keywords, preventing false-positive error classification in informational lines containing exception class names (e.g. Hadoop namenode logs).

### 3.9 Persistent Sidecar Index Disk Cache & 7-Day Auto-Eviction Pipeline (`PersistentIndexCache`)
- **Two-Tier Instant Warm File Re-Opening (<850ms for 211M lines):**
  - **Tier 1 Physical Coordinate Cache (`.idx`):** Serializes `TwoLevelSparseIndex` structures (`SuperStrideOffsets` as `Array[Long]`, `IntraStrideDeltas` as `Array[Int]`, and `totalLinesCount`) to `~/.umbrix/cache/indexes/<fileFingerprint>.idx`. Uses magic signature `0x554D425249584931L` (`'UMBRIXI1'`), version integer, and direct channel ByteBuffer I/O.
  - **Tier 2 Semantic Metadata Sidecar (`.t2`):** Serializes `TemporalCheckpointTable` (`lineIndices`, `epochMicros`, `hasDateComponent`) and `continuationLinesBitmap` (`Roaring64NavigableMap`) to `~/.umbrix/cache/indexes/<fileFingerprint>.t2`. Uses magic signature `0x554D425249585432L` (`'UMBRIXT2'`), format version 2, a 41-byte header, and an appended 8-byte CRC32 integrity checksum. Written via atomic temp-file replace (`Files.move(..., ATOMIC_MOVE)`).
  - **Collision-Resistant File Fingerprinting:** Composite hex hash combining file size, file modification time, and a CRC32 checksum of the first 64KB off-heap memory segment slice.
  - **7-Day Auto-Eviction:** On workstation launch, a background virtual thread scans `~/.umbrix/cache/indexes/` and prunes `.idx` and `.t2` cache files unaccessed in 7+ days.
  - **Zero Re-Scan on Warm Hits:** When opening a file via `LogSession.open`, `tryLoad` and `tryLoadTier2` immediately restore both physical coordinate math and semantic event boundaries, completely eliminating CPU/disk re-scanning.

### 3.10 Virtual Scratchpad Buffers, Persistence & Session Restoration Pipeline
- **Scratchpad Lifecycle & On-Disk Persistence (`ScratchpadManager`):**
  - Manages virtual, editable log buffers stored in `~/.umbrix/scratchpads/<id>.json`.
  - Supports renaming scratchpad titles via `RenameScratchpadDialog` (`UmbrixModalCard`), saving as external `.log` files, and capturing log snippets directly from viewport context menus.
- **Smart DockPaneTabBar Scratchpad Picker (`DockTabContextMenu`):**
  - Left-clicking `+` in `DockPaneTabBar` checks for existing scratchpads: if available scratchpads exist, an interactive popup menu presents a quick list of existing scratchpad buffers alongside "New Scratchpad Buffer", "Manage All Scratchpads (Ctrl+Alt+S)", and "Open Log File (Ctrl+O)".
- **Automatic Multi-Document Session Restoration:**
  - Configurable via `WorkstationSettings.restoreSessionOnStartup` (with toggle in Settings -> View & Viewport).
  - Automatically tracks open log files and the active scratchpad ID, restoring workspace tabs on application startup.

### 3.11 Continuous Bidirectional Selection Edge-Follow Scroll Engine
- **Unbounded Horizontal & Vertical Drag Tracking:**
  - `SelectionEdgeScroll.edgeVelocity` handles pointer positions outside viewport bounds (`x < 0` for leftward scrolling, `x > viewportWidthPx` for rightward scrolling).
  - Smooth 60 FPS coroutine loop continuously advances horizontal scroll and 64-bit document rows when dragging mouse selections off-screen in any direction.
  - Minimap clicks on anomaly bins automatically snap the viewport to the first anomaly line within that bin using $O(1)$-skip indexing (`findNextAnomaly`).
  - Hovering over minimap bins displays a floating dark carbon card detailing the exact document line range, error count, warning count, and info count.

### 3.12 Viewport Scrolling Zero-Allocation Architecture
- **Root Window Recomposition Scope Isolation:**
  - Removed direct reads of `currentTab.topVisibleLine` from `UmbrixMainScreen` root scope. `BottomStatusBar` consumes `activeTab.topVisibleLine` locally, preventing the entire application window (dock host, panels, AI drawer, modal hosts) from recomposing at 60 FPS on scroll.
- **Monospace Constant-Advance Character Width:**
  - Fixed `dynamicCharWidthPx = measuredCharWidthPx` (stable Float computed once via `rememberTextMeasurer()` on font change) instead of dynamically mutating `mutableStateOf` within `onTextLayoutResult`. Eliminates layout invalidations and Skia paragraph re-shaping feedback loops during scrolling.
- **Stable Callback Memoization Across Virtual Viewport Rows:**
  - Memoized `onContextMenuOpen`, `onClearFocus`, `onTextLayoutResult`, and `onScrollToLine` with `remember` across `LogViewportCanvas`, ensuring Compose skips row re-evaluations for unchanged rows.
- **Hoisted Interaction Sources in Viewport Gutter:**
  - Hoisted `MutableInteractionSource` outside the 52-row rendering loop in `ViewportGutter`, eliminating 52 slot table entry allocations per scroll frame.
- **Fallback Event Boundary Classifier Zero-Heap Caching:**
  - Cached `EmptyContinuationBitmap` and `lazy val fallbackClassifier` in `LogSession`, eliminating 104 `Roaring64NavigableMap` object allocations per frame when viewing or scrubbing logs before or during Tier 2 classification. Heap usage remains strictly bounded (<30MB) during rapid scrubbing across 211M-line files.

### 3.9 Pillar 4: Temporal Navigation, Dual-Handle Range Scrubber & Latency Gap Architecture
- **Sampled Temporal Indexing (`TemporalCheckpointTable`):**
  - Samples timestamps every 8,192 lines ($2^{13}$) via zero-copy direct off-heap memory reads.
  - Robust Out-of-Order Log Resilience: Real-world logs (e.g. distributed HDFS, Mesos, rotated/concatenated archives) often contain non-monotonic timestamps across nodes or chunks. `TemporalCheckpointTable` maintains a parallel indirect sorted index array (`sortedCpIndices: Array[Int]`, `sortedTimestamps: Array[Long]`) so binary search is mathematically guaranteed to run in $O(\log N)$ without degradation or range collapsing.
  - Zero-Allocation Timestamp Parsing: Validates ISO-8601 formatting directly on off-heap memory segments with support for both `.` and `,` millisecond delimiters (e.g. Hadoop, Log4j, Syslog). Probes up to 16 lines ahead if a sampled line lands on a multi-line stack trace or un-timestamped continuation payload.
  - Accurate Min/Max Bounds: Computes true global temporal extremes across all checkpoints (`firstTimestamp`, `lastTimestamp`), ensuring range scrubbers cover the full document lifespan regardless of initial line ordering.
- **Asynchronous Latency Gap Scanner (`LatencyGapScanner`):**
  - Analyzes inter-record time deltas ($\Delta t = t_{i+1} - t_i$) across checkpoints.
  - Flags thread freezes, GC pauses, and unlogged execution stalls exceeding configurable thresholds (default 1,000ms warning, 5,000ms severe).
  - Stores gap metadata in an unboxed `Long2LongOpenHashMap` for zero-allocation $O(1)$ viewport gutter lookups.
- **Multi-Format Zero-Allocation Timestamp Parser & Format Lock-In (`TemporalCheckpointBuilder`):**
  - High-performance, line-bounded (`\n`/`\r` clamped) zero-allocation scanner operating directly over off-heap `MemorySegment` slices.
  - Performs document-level timestamp format auto-detection across up to the first 64 header lines (`detectDominantFormatFromSegment`) and locks subsequent checkpoint sampling to the detected format family (`hasDateComponent` tracked in `TemporalCheckpointTable` and `LogSession.hasExplicitDate`), eliminating mid-line false-positive multi-day drift on single-day logs.
  - Supports ISO-8601 / RFC 3339 (`YYYY-MM-DD[ T]HH:mm:ss[.S..]`), Time-of-Day (`00:00:00.000` through `24:00:00.00` with 1–9 fractional digits), Compact `YYYYMMDD HH:mm:ss`, Google `glog`/`klog` (`I1112 14:23:01.123456`), Unix Epoch (10/13/16/19-digit seconds/millis/micros/nanos at line start or explicit `ts`/`time`/`timestamp`/`@timestamp`/`epoch`/`date` keys), Syslog RFC 3164 (`MMM dd HH:mm:ss`), Apache/Nginx CLF & Tomcat (`dd/MMM/yyyy:HH:mm:ss`, `dd-MMM-yyyy HH:mm:ss`), US/European (`MM/dd/yyyy`, `dd/MM/yyyy`), and Dotted/Slashed dates.
- **Go-to-Timestamp Dialog (`GoToTimestampDialog` via `Ctrl+G` / `Cmd+G`):**
  - Unified modal supporting Line Number and Timestamp navigation.
  - Parses freeform wall-clock timestamps (`HH:mm:ss`, `HH:mm:ss.SS`, `24:00:00.00`, full ISO-8601, and epoch millis) without blocking UI rendering, hiding synthetic anchor dates when `!session.hasExplicitDate()`.
- **Dual-Handle Time Range Scrubber (`TemporalRangeScrubber` via `Ctrl+T`):**
  - Custom horizontal slider widget featuring two draggable knobs bounding a continuous temporal window.
  - Adaptive Single-Day & Multi-Day Formatting: Respects `session.hasExplicitDate()` and treats spans up to and including 24 hours (`<= 86,400,000,000 us`, e.g. `00:00:00.000` → `24:00:00.00`) as single-day (`24h 0m 0s`) without leaking synthetic dates or wrapping `24:00:00.000` to `00:00:00.000`.
  - Humanized duration formatting (`formatDurationMicros`): Formats spans cleanly as `${days}d ${hours}h ${minutes}m` for multi-day logs and `${totalHours}h ${minutes}m ${seconds}s` up to 24 hours.
  - "Trim View (Focus Mode)" button normalizing viewport virtual coordinates to the selected temporal slice while preserving physical gutter line numbers and rendering a dimming focus overlay across the GPU minimap.
  - Status Detection: Distinguishes between active background analysis and documents with no recognizable timestamps, reporting clear status and format guidance.

### 3.10 Workstation UI Overhaul & Dynamic Theme Engine Architecture
- **Complete Decoupling from Static Colors:** Completely eliminated the legacy `UmbrixColors` singleton in favor of an extensible interface contract, `UmbrixThemeSpec`, providing 81 semantic color tokens across 11 functional categories (Window Chrome, Tabs, Typography, Gutter, Search Needles, Severity, Badges, Minimap, Scrubber, Controls, Dialogs).
- **10 Production-Grade Built-in Themes:**
  - *Dark Themes (7):* `DarkCarbonTheme` (signature IntelliJ Darcula), `MidnightBlueTheme` (navy/Zed Andromeda), `SublimeMonokaiTheme` (warm charcoal with vibrant neon), `DraculaTheme` (purple-tinted dark), `NordTheme` (arctic cool blue-gray), `SolarizedDarkTheme` (CIELAB dark teal), `HighContrastTheme` (pure black/white >21:1 WCAG AAA accessibility).
  - *Light Themes (3):* `LightIvoryTheme` (soft off-white daylight), `SolarizedLightTheme` (warm parchment), `GitHubLightTheme` (GitHub Primer code view).
- **Central Theme Registry (`ThemeRegistry`):** Singleton catalog managing static registration, $O(1)$ lookup by name/display name, subset filtering (`darkThemes()`, `lightThemes()`), and canonical fallback defaults.
- **Compose Runtime Injection:** Implemented `LocalUmbrixTheme = staticCompositionLocalOf<UmbrixThemeSpec> { DarkCarbonTheme }` and dynamic accessor `UmbrixTheme.colors`.
- **Reactive Jewel Synchronization:** Dynamic `UmbrixThemedApplication` synchronizing `IntUiTheme(isDark = theme.isDark)` with the active theme specification.
- **Theme-Aware Highlight Caching:** `HighlightCache` keyed by `(query, isRegex, matchCase, themeName)` with automated LRU invalidation to eliminate cross-theme color span bleeding without incurring GC overhead.
- **Workstation Settings Integration:** Persisted `themeName` field in `WorkstationSettings` and `SettingsManager` with instant live reactive theme switching from the Appearance category in `SettingsDialog`.
- **Strict Invariants Preserved:** Zero GC heap allocations on log data, 64-bit coordinate math preserved, <150MB RSS maintained.

### 3.11 Typography & Font Configuration Subsystem (UI Overhaul Phase 3)
- **Decoupled Typography Model (`FontSpec`):** Single source of truth immutable data class specifying editor font family, editor font size (8.0–32.0pt), line height multiplier (1.0–2.0x), editor stroke weight (300, 400, 500, 700), UI chrome font family, UI font size (10.0–20.0pt), and OpenType ligature toggle.
- **Intelligent Multi-Platform Fallback Engine (`EditorTypography`):** Prepends user-specified font names before robust platform-specific monospace (`JetBrains Mono`, `Cascadia Code`, `Consolas`, `SF Mono`, `Menlo`, `Monaco`, `DejaVu Sans Mono`) and proportional fallback chains, ensuring zero crash risk if a configured font is missing.
- **Dynamic Monospace Discovery:** Queries the local JVM `GraphicsEnvironment` on demand with keyword heuristics (`mono`, `code`, `console`, `fixed`, `fira`, etc.), populating the Settings font picker without blocking the UI thread.
- **OpenType Feature Settings:** Generates `"\"liga\" 1, \"calt\" 1"` or `"\"liga\" 0, \"calt\" 0"` feature strings to selectively disable contextual ligature joining (e.g. `!=`, `->`, `==`) for dense stack trace inspection.
- **CompositionLocal Typography Injection:** Broadcasts active `FontSpec` globally via `LocalUmbrixFonts` and accessors `UmbrixFonts.current` / `UmbrixTheme.fonts`, configured in `UmbrixThemedApplication`.
- **Two-Way Zoom Synchronization:** Synchronizes toolbar zoom steppers (`+`/`-`), keyboard shortcuts (`Ctrl++`, `Ctrl+-`, `Ctrl+0`), and mouse wheel scroll gestures (`Ctrl+MouseWheel`) directly with `SettingsManager`, atomically updating UI canvas metrics and persisting to `~/.umbrix/settings.json`.
- **Zero-Allocation Layout Recalculation:** Dynamically computes row heights (`rowHeightDp = S_pt * M_lh + 4.0dp`), visible row counts, and gutter digit widths memoized by typography state; Skia synthetic measurer caches character advance bounded by `.coerceAtLeast(1.0f)`.

### 3.12 Hamburger Menu Subsystem (UI Overhaul Phase 4)
- **Package `umbrix.ui.menu`:** Compose-native (no AWT `JPopupMenu`) cascading dropdown hosted inside the `☰` button of `IntegratedTitleBar`, so the popup anchors to the button's window bounds.
- **Action Contract:** `sealed interface MenuAction` (file, navigation, view, theme, dialogs, lifecycle) with typed payloads (`ViewportLayoutMode`, `ViewToggle` enums) instead of string keys.
- **Data Flow:** `IntegratedTitleBar` → `Channel<MenuAction>` (owned by `DesktopAppLauncher`) → `UmbrixMainScreen.handleMenuAction` (collected via `rememberUpdatedState` so the long-lived collector always sees the latest tab/session closures). Settings-scoped actions pass through the pure `MenuSettingsReducer` and are persisted by `SettingsManager`; screen-scoped actions (open file, Find, Go To, temporal scrubber, dialogs, exit) mutate screen state directly.
- **Interaction Model:** `MenuLevelState` per panel — only pointer *enter* updates the hovered key and the open submenu trails it by 200ms, which suppresses flicker while letting the pointer travel into the submenu popup. Root popup is focusable (Escape collapses an open submenu first, then the root; Up/Down move focus; Enter activates); submenu popups are non-focusable so sibling rows keep receiving hover.
- **Geometry (`MenuGeometry`):** Pure pixel math behind `PopupPositionProvider`s — root anchored 4dp below the trigger and clamped to the window; submenus open rightward with 4dp overlap, flip leftward at `windowWidth − 8dp`, and clamp against the bottom edge. Width bounded 220–320dp.
- **Persisted Menu State (`WorkstationSettings`):** `viewportMode`, `showLineNumbers`, `showWhitespaceChars`, `recentFiles` (max 8, de-duplicated, case-insensitive on Windows, recorded on every successful `loadLogFile`). Recent paths are JSON-escaped and newline-joined in `settings.json`; `SettingsDialog` uses `copy()` so menu-owned fields survive dialog saves.
- **Diagnostics:** "Diagnostic Report" routes a non-fatal `OperatorDiagnosticSnapshot` marker through `WorkstationErrorHandler.reportException`, reusing forensic persistence and the banner.
- **Main-Screen Chrome:** The Phase 4 hamburger replaces the standalone top control toolbar for file, search, navigation, view, and settings actions. The tab strip remains; index duration, document size, line count, and SIMD width share the bottom-right status readout with viewport and JVM telemetry.
- **Invariants:** Closed menus compose nothing and own no coroutines; no log bytes are touched; no regex usage.

### 3.13 Desktop Window Sizing
- `DesktopWindowGeometry` derives monitor work bounds by applying AWT screen insets, which reserve taskbars and docks.
- Startup dimensions are capped at 1200×760 and 90% of the primary monitor's usable area; native maximized bounds are refreshed from the current monitor before the custom maximize action to avoid borderless fullscreen behavior.
- Geometry is isolated from window creation and covered with headless unit tests for inset subtraction and small displays.
- **Known Gaps:** Viewport rendering does not yet honour `viewportMode` / `showWhitespaceChars` (Tri-Mode layout engine pending); keyboard traversal does not enter submenus.

### 3.14 Modal Dialog Standardization Subsystem (UI Overhaul Phase 5)
- **Foundation Composable (`UmbrixModalCard`):** Unified modal container encapsulating a 70% black scrim (`0xB3000000`), animated scale/fade transitions (`scaleIn(0.95 -> 1.0) + fadeIn` over 150ms / `scaleOut(1.0 -> 0.95) + fadeOut` over 100ms), 12dp rounded card corners, 24dp elevation drop shadows, standard header with close `✕`, customizable action button row, scrollable body support, and strict focus trapping.
- **Refactored Dialogs:** `GoToTimestampDialog` (superseding legacy `GoToLineDialog` as the unified dual-mode line & wall-clock jump overlay) and `SettingsDialog` refactored to wrap `UmbrixModalCard`, eliminating redundant scrim code and binding all colors directly to reactive `UmbrixTheme.colors` tokens.
- **New Dialog Implementations:**
  - `AboutDialog`: Workstation introspection modal displaying hardware SIMD vector width (AVX-512 / AVX2 / NEON), Azul OpenJDK 25 LTS runtime, JVM memory footprint, and OS architecture.
  - `KeyboardShortcutsDialog`: Searchable keymap cheatsheet organized by functional categories (Navigation, Search, File, View, General) with styled monospace keychips.
  - `ExportSubFileDialog`: Forensic log extractor allowing investigators to specify 64-bit line slices ($L_{\text{start}}$ to $L_{\text{end}}$), with real-time linear byte payload estimation and path targeting.
- **Dispatching & Hotkeys:** Integrated into `MenuAction` (`ExportSubFile`, `KeyboardShortcuts`, `About`, `OpenSettings`), `HamburgerMenuContent`, `IntegratedTitleBar`, and `UmbrixMainScreen` with global keyboard accelerators (`Ctrl+G`, `Ctrl+Shift+S`, `F1`, `Ctrl+,`).
- **Invariants:** 100% Zero Heap Ingestion — line slices export via DMA / `FileChannel` without loading raw log strings into the JVM Garbage Collector heap; dialog state is purely coordinate-driven.

### 3.15 Settings Expansion & Atomic Disk Persistence (UI Overhaul Phase 6)
- **Centralized Workstation Preferences (`WorkstationSettings`):** Unified data model encapsulating 22+ configuration properties spanning visual themes, typography (`FontSpec`), viewport modes, gutter line numbers, whitespace visibility, latency gap thresholds, and status bar telemetry toggles.
- **Atomic File Write Pipeline (`SettingsManager`):** Writes JSON state to `~/.umbrix/.settings.json.tmp` and replaces `~/.umbrix/settings.json` via atomic OS rename (`Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` with standard replace fallback). Eliminates the risk of corrupt 0-byte configuration files on unexpected power loss or process termination.
- **Dedicated View & Viewport Settings Tab:** `SettingsDialog` features a `VIEW("View & Viewport", "👁")` category allowing users to manage layout modes, 64-bit line numbers, whitespace characters, and individual status bar telemetry elements from a unified modal.
- **Forward & Backward Compatibility:** Parser supports legacy key aliases (`isMinimapVisible`, `languageProfile`, `isSimdEnabled`, `showWhitespace`) and gracefully falls back to canonical defaults when reading older configuration files.

### 3.16 Welcome Screen & Toast Notification Subsystems (UI Overhaul Phase 7 Pack A)
- **Empty-State Dashboard (`WelcomeScreen`):** Displayed when `openTabs.isEmpty()`, greeting investigators with a modern workstation welcome card, interactive drag-and-drop file target zone, primary "Open Log File... (Ctrl+O)" action, recent files history with live existence indicators and formatted file sizes, and quick keyboard shortcut cheat chips.
- **Non-Blocking Toast System (`ToastHost`, `ToastManager`):**
  - Lightweight reactive toast stack anchored at the bottom-right of the window.
  - Supports 4 semantic severity categories: Info, Success, Warning, Error.
  - Enforces a bounded memory queue (maximum 4 concurrent toasts) with automated time-based disposal (2.5s–4s) to strictly maintain the <150MB RSS invariant.
  - Integrated across key actions: text snippet copy feedback ("Copied 42 lines to clipboard"), line copy, sub-file export confirmation, and file-not-found warnings.

### 3.17 Universal Multi-Persona Command Palette & Log Row Context Menu (UI Overhaul Phase 7 Pack B)
- **Multi-Persona Command Palette (`CommandPaletteDialog`, `CommandPaletteState`):** Provides instant, keyboard-driven access to every command, setting toggle, theme, recent file, and navigation target. Supports configurable personas:
  - **Unified Omni (`Ctrl+Shift+P` / `Ctrl+P`):** Consolidated search across actions, files, themes, and navigation.
  - **VS Code Style (`Ctrl+Shift+P`):** Explicit `>` prefix for commands, without prefix for files.
  - **IntelliJ Style (`Double Shift` / `Ctrl+Shift+A`):** Modal with filter pills (`All`, `Actions`, `Files`, `Themes`, `Settings`).
  - **Vim / Linux Ex-Commands (`:`):** Modal prompt supporting `:w`, `:q`, `:qa`, `:set minimap!`, `:set mode=compact`, `:theme <name>`, `:1234` line jumps.
- **ReDoS-Immune Fuzzy Matching (`FuzzyMatcher`):** Linear $O(N)$ subsequence scoring with quadratic consecutive character bonuses, word boundary bonuses, and prefix weighting.
- **Right-Click Context Menu (`LogViewportContextMenu`, `LogContextMenuState`):** High-precision contextual menu for the active log row, offering Copy Full Line, Copy Selection, Copy Line Number, Filter by Line/Selection, Search for Line/Selection, Seek in Temporal Scrubber, Pin to Incident Timeline, Toggle Bookmark, Append to Scratchpad, and New Scratchpad from Selection.

### 3.18 Forensic Timeline Reconstruction, Investigation Notebooks & In-App Scratchpads (UI Overhaul Phase 7 Pack C)
- **Investigation Timeline Reconstruction (`InvestigationTimelineManager`, `InvestigationTimelineCanvas`, `TimelineEventCard`):**
  - Consolidates forensic evidence across disparate log sources (`api.log`, `db.log`, `worker.log`) into a unified chronological sequence ($T_0 \to T_1 \dots$) via microsecond epoch timestamps.
  - Real-time elapsed time $\Delta t$ cascade badges (`+12.4ms`, `+1.8s`) calculated relative to the prior event, exposing propagation delays and distributed bottlenecks.
  - Rich forensic event tags (`TRIGGER`, `ROOT_CAUSE`, `CASCADE`, `ANOMALY`, `EVIDENCE`, `RESOLVED`) with high-contrast color badges.
  - **Multi-Row Selection & Batch Operations:** Checkbox selection, multi-select toolbar, batch tag assignment, batch deletion, and batch positional movement (`Move Up`, `Move Down`, `Move to Top`, `Move to Bottom`) preserving internal relative ordering of selected event clusters.
- **One-Click Incident Report Generation (`IncidentReportExporter`, `TimelineExportDialog`):**
  - Generates comprehensive GitHub-Flavored Markdown post-mortems with summary metadata, structured event tables, and clamped monospace code blocks.
  - Exports consolidated continuous log streams and structured JSON evidence archives.
  - Modal export dialog (`TimelineExportDialog`) with live monospace preview pane and file save / clipboard actions.
- **In-App Scratchpad Editor Tabs (`ScratchpadSession`, `ScratchpadManager`, `ScratchpadEditorCanvas`):**
  - Virtual editable document tabs (`scratch://scratch-1.log`) allowing investigators to type notes, paste evidence, and extract snippets from large log files with provenance headers (`--- Source: filename.log, Line: 1243, Timestamp: ... ---`).
  - Integrated dirty state tracking and native disk persistence (`Ctrl+S`).
- **Gutter Bookmarks, Bookmark Filtering & GPU Minimap Pins (`BookmarkManager`, `BookmarkManagerDialog`):**
  - Zero-heap line bookmarking with `Ctrl+B` toggle or gutter bookmark click (`📌`).
  - Fast keyboard stepping via `F2` (next) / `Shift+F2` (previous).
  - Cyan tick markers (`UmbrixTheme.colors.accentPrimary`) rendered directly into the Skia GPU seismograph minimap canvas (`GpuSeismographMinimap`).
  - Searchable bookmark manager modal (`BookmarkManagerDialog`) for tagging, filtering, and jumping to saved document lines.
  - **Bookmark Filtering Mode (`Ctrl+Alt+B`):** Transforms the viewport to display *strictly* bookmarked lines via zero-heap `FilteredLineView` powered by `Roaring64NavigableMap` bitsets, complete with a floating 1-click restore indicator and SearchBar `📌 Bookmarks ($count)` quick filter button.
- **Reliable Row Context Menu & Active File Binding (`LogViewportContextMenu`, `DockPaneTabBar`):**
  - Row context menu intercepts secondary mouse presses directly on the log row container via `PointerEventType.Press`, avoiding gesture contention with row selection clicks.
  - Context menu positioning incorporates horizontal and vertical gutter offsets with intelligent window-edge flipping via `ContextMenuPositionProvider`.
  - Context-aware file awareness dynamically connects active document paths, titles, and selected line offsets to the Hamburger Menu, `ExportSubFileDialog`, `GoToTimestampDialog`, and `BookmarkManagerDialog`.
  - **DockPaneTabBar `+` Workflow:** Left-clicking `+` opens a scratchpad editor tab by default (`Ctrl+N`); right-clicking `+` (via `DockTabContextMenu`) provides a choice between "New Scratchpad Buffer" and "Open Log File... (Ctrl+O)"; right-clicking existing tabs opens contextual tab management (Close, Close Others, Close All, New Scratchpad, Open Log File).

### 3.7 Natural Multi-Word Phrase Matching & Viewport Needle Extraction
- **The Challenge:** In log search, users routinely type queries with spaces (e.g. `added to`, `database timeout`, `connection reset by peer`). Traditional search engines that tokenize on whitespace and synthesize implicit `AND` operators generate two critical bugs: (1) lines containing separated words anywhere on the line match false-positively, and (2) highlighters highlight every isolated instance of common words (e.g. `to`, `added`) across the screen.
- **Natural Phrase Parsing:** `QueryParser` treats adjacent unquoted words separated by whitespace as a single contiguous `QueryAst.Literal(phraseText, caseSensitive = false)`. Original spacing between tokens is preserved verbatim.
- **AST-Derived Highlighting:** `QueryAst.extractNeedlesJava` extracts search needles directly from the compiled AST. In-viewport highlighting and navigation target the exact phrase (`added to`), eliminating false positive highlights on isolated words.
- **Explicit Boolean Support:** Explicit conjunctions (`AND`, `&&`), disjunctions (`OR`, `||`), negations (`NOT`, `!`), and structured filters (`level:ERROR`, `since:`, `until:`, `/regex/`) remain fully supported and compose cleanly with phrases (e.g. `level:ERROR AND added to`).

### 3.19 Embedded Model Context Protocol (MCP) Server & Forensic AI Assistant Subsystem
- **Cognitive Workstation Bridge (`:umbrix-mcp`):**
  - Connects external AI coding agents (Cursor, Claude Code, Antigravity, Copilot) directly to multi-gigabyte off-heap log sessions via JSON-RPC 2.0.
  - Offloads heavy indexing and parsing to the native engine, returning concise, structured forensic summaries consuming $<1,500$ tokens total.
- **Dual Transports:**
  - **Stdio Transport (`McpStdioServer`):** Direct stdin/stdout line protocol for agents spawned as CLI subprocesses.
  - **Embedded HTTP Loopback Server (`McpHttpServer`):** Runs on `http://127.0.0.1:4242/mcp` powered by JDK 25 `HttpServer` with Virtual Threads (`newVirtualThreadPerTaskExecutor()`), servicing POST JSON-RPC queries and GET discovery handshakes.
- **Hardware-Accelerated Forensic Tool Portfolio (`ForensicToolRegistry`):**
  - `search_logs`: AVX-512 vector substring and RE2J regex scanning with time-window filtering.
  - `get_time_slice`: $O(\log N)$ binary seeking across wall-clock timestamps via `TemporalCheckpointTable`.
  - `extract_causal_context`: Backward causal scanning isolating transaction threads and correlation IDs around errors.
  - `get_error_clusters`: Stack trace signature normalization collapsing thousands of exception instances into canonical root-cause clusters.
  - `trim_and_export`: DMA zero-copy kernel slicing to export designated ranges directly to disk.
- **Client-Side Data Loss Prevention (`DlpSanitizer`):**
  - Shannon entropy threshold detector and regex pattern matcher for high-entropy secrets (passwords, JWT tokens, AWS keys).
  - Automatically sanitizes sensitive strings before transmission to external AI agents or cloud LLM endpoints.
- **Interactive In-App AI Assistant Drawer (`AiChatDrawer`, `AiAssistantCoordinator`):**
  - Slide-out forensic chat panel (`Ctrl+Shift+I` / toolbar button `⚡ AI`).
  - Supports conversational log querying against local Ollama models (e.g. `llama3.2`) or cloud providers.
  - Auto-invokes local forensic tools to answer natural language time-window and pattern queries (e.g. *"Between 12:00 and 12:05 find any logging with NullPointerException"*).
  - Clickable line reference chips (`#lineNum`) provide instant 1-click viewport navigation.

### 3.23 Plugin Architecture & Extensibility SDK Subsystem
Implemented across `:umbrix-plugin-api`, `:umbrix-plugin-ui-api`, `:umbrix-index`, `:umbrix-core`, `:umbrix-mcp`, and `:umbrix-ui`:
- **Two-Tier Public SDK Architecture:**
  - **`:umbrix-plugin-api` (Pure Scala 3 SPI):** Decoupled, zero-UI public extension contracts published to Maven Local (`group = "umbrix"`, `version = "1.0.1"`). Exposes `PluginManifest`, `UmbrixPlugin`, `PluginContext`, `PluginSessionView`, `FileFormatProvider`, `RecordFramer`, `RecordFormatter`, `SimdHelper`, `DocumentDecorator`, `QueryFunction`, `PluginAction`, `McpToolProvider`, and `PluginConfigStore`.
  - **`:umbrix-plugin-ui-api` (Kotlin + Compose UI SPI):** UI-aware extensions defining `ComposeDockView`, `ComposePluginSettingsPanel`, and `PluginThemePalette`.
- **Child-First Isolated Runtime (`PluginClassLoader`):**
  - Fat/uber plugin JARs bundle their own third-party dependencies (Jackson, SQLite, Protobuf, etc.) without classpath pollution.
  - Transparently delegates shared platform frameworks (JDK, Scala 3, Kotlin, Compose, Skia, Jewel, RoaringBitmap, Fastutil, and `umbrix.plugin.*`) to the host classloader.
  - Enforces strict architectural isolation by throwing `ClassNotFoundException` on any attempt to import internal host packages (`umbrix.core.*`, `umbrix.simd.*`, `umbrix.ui.*`, `umbrix.mcp.*`, `umbrix.app.*`).
- **Off-Heap Virtual Expansions (`PieceTable`):**
  - Extended `PieceTable.scala` with high-performance `Long2LongOpenHashMap` (offset pointers) and `Long2IntOpenHashMap` (byte sizes) keyed by physical record index.
  - Virtual expansion payloads are written directly into off-heap 1MB Arena chunks (`putVirtualExpansion`, `putVirtualExpansionText`, `clearVirtualExpansion`, `hasVirtualExpansion`, `getVirtualExpansionLines`) without modifying the underlying log or copying into the JVM GC heap.
- **Custom Framed Binary/Non-Newline Formats (`FramedRecordIndexerImpl`):**
  - Connects `TwoLevelSparseIndex` checkpoints (16,384 super-stride / 64 intra-stride) with custom plugin `RecordBoundaryFramer` and `RecordTextFormatter` callbacks.
  - Delivers `<1.5us` coordinate translation (`byteOffsetForLine`, `lineIndexForByteOffset`) and on-demand Skia viewport text shaping on non-newline structured logs (PCAP, HPROF, FIX).
- **Progressive SIMD Hardware Acceleration (`SimdHelperImpl`):**
  - Exposes host AVX-512 / AVX2 Java Vector API kernels to plugins (`findFirstByte`, `countByte`, `scanByte`, `scanEitherByte`, and parallel chunk scanners) for off-heap search and delimiter processing.
- **Document Decorators & Micro-Cards:**
  - `DocumentDecorator` SPI allows plugins to attach gutter badges (`GutterDecoration`), inline pills (`InlineChipDecoration`), 250ms hover inspection cards (`HoverCardDecoration`), and virtual expansion expansion toggles directly into the viewport rendering pipeline.
- **Custom Dock Views (`PluginDockViewHost`):**
  - Multi-pane workstation dock host rendering both `ComposeDockView` composables and high-performance `SkiaCanvasDockView` custom GPU canvas surfaces, with auto-docking companion views on format match (`FormatProbeResult.companionDockViewId`).
- **Custom Query Functions (`QueryAst.CustomFunction`):**
  - Extends `QueryParser` with `fn:<name>(<args>)` syntax, dispatching directly to registered plugin `QueryFunction` instances returning compressed 64-bit `Roaring64NavigableMap` bitsets.
- **Dynamic MCP Tool Exposure & DLP Governance:**
  - `ForensicToolRegistry` dynamically enumerates and dispatches to registered `McpToolProvider` implementations, wrapping execution in 15s asynchronous timeouts and client-side `DlpSanitizer` secret redaction.
- **Fault Supervision & Circuit Breaker (`PluginFaultSupervisor`):**
  - Guards all third-party invocations against unhandled exceptions and infinite loops.
  - Automatically trips faulty plugins after 3 consecutive errors, logging structured forensic error reports and falling back gracefully without terminating the workstation.
- **Declarative Configuration & Workstation Management (`PluginsSettingsTab`):**
  - Automated settings UI rendering for declarative `PluginSettingDescriptor` types (`STRING`, `SECRET`, `INTEGER`, `BOOLEAN`, `CHOICE`, `FILE_PATH`) with persistence in `~/.umbrix/plugins/<pluginId>/config.json`.
  - In-app plugin manager offering "Install Plugin from Disk (.jar)...", "Open Plugins Folder", "Reload Plugins", enable/disable switches, and capability badges.
  - *Upcoming First-Party Add-On:* The official JVM Diagnostic & Profiling Toolkit plugin (off-heap `.hprof`, `.jfr`, and GC log analysis) builds on this SDK and will be published separately in a future release.

---

## 4. Error Management & Forensic Reporting Architecture

### 4.1 Resilient Degradation Pattern
- Primary native paths (Panama downcalls to Win32/libc) catch low-level system faults and seamlessly degrade to standard Java NIO channel I/O with structured diagnostics.
- Operations that fail do not take down the entire application; instead, error states are captured and communicated via typed result abstractions (`Either[UmbrixError, A]`).

### 4.2 Forensic Error Report Generator
Implemented in `umbrix.core.diagnostics.ForensicErrorReportGenerator`:
- Distinguishes fatal JVM errors (`VirtualMachineError`, `OutOfMemoryError`, `StackOverflowError`, `LinkageError`) from catchable non-fatal exceptions (`IllegalStateException`, `IOException`, etc.).
- Persists diagnostic snapshots to disk at `~/.umbrix/reports/error_report_<timestamp>_<id>.json` and `.txt`.
- Captured state includes:
  1. **Environment State:** OS name/version/arch, JDK vendor/version, and active SIMD register byte width (`VectorScanner.vectorByteWidth`).
  2. **Memory State:** Used, total, and max JVM heap memory.
  3. **Execution Context:** Intercepted thread name and thread ID.
  4. **Document Coordinates:** Active file path, file size in bytes, total lines, and viewport coordinate (`ForensicReportContext`).
  5. **Causal Stack Trace:** Timestamped chained stack trace and formatted diagnostic summaries.

### 4.3 UI Error Boundary & Recovery Handler
Implemented in `umbrix.ui.launcher.WorkstationErrorHandler` and `ForensicErrorBanner`:
- **Fatal Error Guard:** When a fatal JVM error occurs (e.g. `OutOfMemoryError`), an emergency dialog is shown alerting the user with the report location before cleanly halting the process to prevent silent data corruption.
- **Non-Fatal Recovery:** Catchable exceptions (such as uninitialized focus requesters, search errors, or coroutine faults) do not crash the application. Instead, they trigger a prominent top-level `ForensicErrorBanner` with exception classification, report path, "Copy Diagnostic Report" to clipboard, and "Dismiss" buttons while keeping all open tabs and memory segments responsive.
- **Asynchronous Logging:** Emits structured logs asynchronously to SLF4J and `${user.home}/.umbrix/logs/umbrix.log` with zero UI thread contention.

### 4.4 Universal Multi-Persona Command Palette Engine
Implemented in `umbrix.ui.palette`:
- **Persona Architecture:** Configurable operational modes supporting:
  1. *Unified Omni Mode:* Automatically detects Vim ex-commands (`:`), file search (`@`), line jumps (`#`), action directives (`>`), or raw fuzzy keyword queries.
  2. *VS Code Mode:* Direct command search triggered by `Ctrl+Shift+P` (auto-prepends `>`), fast file switcher via `Ctrl+P`.
  3. *IntelliJ Search Everywhere:* Triggered by `Double Shift` ($\Delta t \le 400\text{ms}$) or `Ctrl+Shift+A`, featuring interactive category pills (`All`, `Actions`, `Files`, `Settings`).
  4. *Vim / Linux Ex-Commands:* Triggered by `:` (Shift+Semicolon), supporting `:w` (export sub-file), `:q` (close tab), `:qa` (quit workstation), `:set minimap!`, `:set mode=compact`, `:theme <name>`, and numeric line jumping (`:1234`).
- **Linear-Time Fuzzy Subsequence Matcher (`FuzzyMatcher`):**
  - Guaranteed $O(N)$ ReDoS-immune character matching with quadratic consecutive match bonus ($\frac{k(k-1)}{2} \times 20$) and initial prefix bonus ($+150$).
  - Word boundary bonus ($+30$) for characters following spaces, slashes, periods, hyphens, colons, or camelCase transitions.
  - Zero heap log ingestion: commands index metadata, recent files, open tabs, and preferences without retaining log buffers.

### 4.5 Log Viewport Row Context Action Engine
Implemented in `umbrix.ui.contextmenu.LogViewportContextMenu` and `LogViewportCanvas`:
- **Secondary Mouse Detection:** Intercepts right-clicks on viewport rows without disturbing HarfBuzz text selection.
- **Contextual Actions:**
  - *Copy Full Line:* Copies the underlying clamped line text directly to the system clipboard.
  - *Copy Selection:* Active whenever a character range is selected via mouse-drag.
  - *Copy Line Number:* Copies the 1-based physical line index.
  - *Filter by Line / Selection:* Immediately updates query filter to isolate occurrences.
  - *Search for Line / Selection:* Dispatches term into `Ctrl+F` search coordinator without manual typing.
  - *Seek in Temporal Scrubber:* Jumps dual-handle time scrubber directly to the row's temporal checkpoint.
  - *Export Sub-File:* Launches `ExportSubFileDialog` pre-seeded with the target line as start bound.

### 4.6 UI Modular Decomposition & Viewport Gesture Reliability
- **Component File Size Reduction:** Large monolithic UI files decomposed into cohesive, single-responsibility coordinators (<250–500 lines each):
  - `UmbrixMainScreen.kt` (reduced from 1,609 to 484 lines) decomposed into `MainScreenTabCoordinator.kt` (tabs, dock tree, sessions, DMA export), `MainScreenMenuCoordinator.kt` (MenuAction reducer & filter view coordinate math), `MainScreenKeyHandler.kt` (global keyboard shortcuts & clipboard router), and `MainScreenModalHost.kt` (driven by `ActiveDialog` sealed state, 9 parameters).
  - `ScratchpadEditorCanvas.kt` decomposed into `ScratchpadToolbar.kt`, `ScratchpadSearchReplaceDeck.kt`, and `ScratchpadSearchController.kt`.
  - `DockLayoutHost.kt`, `DockPaneView.kt`, and `DetachedWindowManager.kt` refactored to consume `@Immutable` `DockViewportCallbacks`, eliminating 20+ forwarded lambdas across recursive tree levels. Tab context menus extracted to `DockTabContextMenu.kt`.
  - `InvestigationTimelineCanvas.kt` decomposed with `TimelineEventCard.kt` (`TimelineBatchOperationsBar` + `TimelineEventCard`) down to <210 lines.
  - `CommandPaletteDialog.kt` decomposed into `CommandPaletteComponents.kt` (<260 lines).
  - `SettingsDialog.kt` decomposed into 8 category tabs in `umbrix.ui.dialog.settings` (`GeneralSettingsTab.kt`, `AppearanceSettingsTab.kt`, `FontsSettingsTab.kt`, `ViewSettingsTab.kt`, `LanguagesSettingsTab.kt`, `PerformanceSettingsTab.kt`, `AiMcpSettingsTab.kt`, `PluginsSettingsTab.kt`).
  - `LogViewportCanvas.kt` split into `ViewportCoordinateMapper.kt` (Kotlin inline value types `VirtualRowIndex`/`PhysicalLine` with `-1L` sentinels and `OnRowTextLayout`), `ViewportGutter.kt` (line numbers, bookmarks, latency dots, folding chevrons), `ViewportTextRow.kt` (token shaping, search highlight pills, multi-line continuations), `ViewportGestureHandler.kt` (drag selections, `SelectionEdgeScroll.advanceVertical`), and `ViewportInputModifiers.kt` (`Modifier.viewportScrollAndKeyInput` attached outside `pointerInput(Unit)` so recompositions always supply fresh viewport dimensions). Zero-allocation bookmark bitmap caching via `BookmarkManager.generation`, `derivedStateOf`, and row `key(physicalLine, rowIndex)`.
  - `LogSession.scala` indexing progress/status formatting extracted to `SessionStatusFormatter.scala`.
  - `CommandRegistry.kt` decomposed into `FileCommands.kt`, `NavigationCommands.kt`, `ViewCommands.kt`, and `ToolCommands.kt`.
- **Horizontal Scroll Drift & Stationary Click Stabilization:**
  - Removed Compose `Modifier.clickable` from viewport row containers and continuation badges, eliminating Compose's automatic `BringIntoViewRequester` that was shifting `horizontalScrollState` by 8dp on click.
  - Gated selection dragging and edge-scrolling jobs behind `viewConfiguration.touchSlop`. Stationary clicks near the gutter or edges release without starting edge-velocity scrolling or moving horizontal sliders.
  - On stationary release (`!moved && released`), clicks cleanly assign `selectedLineIndex = clickedPhysicalLine` and clear transient character highlight boxes.
- **Temporal Range Scrubber Lifecycle & Gesture Unfreezing:**
  - Retained persistent `MutableState` instances keyed to tab file path, updated via `LaunchedEffect(isTemporalFocusActive, ...)`. This eliminates coroutine state capture mismatches where clearing focus created dead delegates and froze scrubber knobs until closed and reopened.
- **Search Navigation & Anchor-Prioritized Streaming:**
  - *Anchor-Prioritized Chunk Dispatch:* `SimdSearchEngine` circular-schedules parallel chunk tasks starting from the partition covering the user's active cursor/viewport offset ($C_{\text{anchor}} \dots C_{N-1}, C_0 \dots C_{\text{anchor}-1}$), ensuring carrier threads immediately scan the region surrounding the user's current position first.
  - *Viewport-Aware Anchor Resolution:* `MainScreenSearchController.resolveAnchorLine` checks whether `selectedLineIndex` is currently visible in the active viewport ($[\text{topVisibleLine}, \text{topVisibleLine} + \text{visibleRowCount}]$); if visible, search anchors to the selected line; if the user scrolled away so the selection is off-screen, search automatically anchors to the top visible line in the viewport.
  - *Safe Progressive Auto-Jumping:* The viewport only auto-jumps during progressive streaming if the discovered match resides within the local anchor chunk ($[C_{\text{anchor}}]$); distant out-of-order chunk matches are held until full query completion, eliminating out-of-order jump skips.
  - *Elimination of False Wrap Prompts:* Wrap prompts (*"No matches below cursor. Press Enter to wrap"*) are strictly suppressed on partial snapshots and evaluated exclusively once the entire file has completed scanning.
  - *Queued Navigation Intent (`pendingSearchNavigation`):* Pressing Enter or F3 while search indexing is actively scanning queues the navigation intent, displaying an immediate status toast and automatically executing the jump to the next/prev match the instant the search completes.
- **Deterministic Cross-Platform Hit-Testing & Zero-Dilation Invariant:**
  - *Exact Hit-Testing via LayoutCoordinates:* Viewport hit-testing captures the root viewport `LayoutCoordinates` and maps pointer coordinates directly to row text space via `textCoordinates.localPositionOf(viewportCoordinates, pointerPosition)`. This eliminates speculative pixel math, manual horizontal scroll subtraction, and padding/chevron offsets from gesture handling.
  - *Dynamic-Height Row Resolution:* Rows in Full Wrap or expanded Expandable mode have variable heights, so `y / rowHeightPx` is invalid. `resolveRowAtPointer` walks the attached `RowTextLayout.coordinates` in order, reading each row's top via `viewportCoordinates.localPositionOf(rowCoordinates, Offset.Zero).y` (zero allocation, ≤60 rows), clamps above/below the rendered range, and falls back to fixed-height math only when coordinates are unattached. Full Wrap intra-row Y is clamped to the text layout height. The row context menu Y is mapped from the row container's live coordinates rather than `rowIndex * rowHeightPx`.
  - *Gutter alignment:* `ViewportGutter` still uses fixed `rowHeightDp` boxes, so in Full Wrap/expanded rows its line numbers do not track wrapped row heights (known limitation; fixing requires sharing measured row heights from `textLayoutResults`).
  - *Zero-Dilation Styling Invariant:* Standardized all search and syntax highlights across both `SearchHighlighter.kt` and `ScratchpadEditorCanvas.kt` to pure `background` and `color` styling strictly without `FontWeight.Bold` or `Shadow`. On macOS CoreText/Skia with Menlo and Retina displays, synthetic emboldening dilated character advances by +0.5px to +1.0px per bold glyph, causing character indices to drift to the right of shaped text.
  - *Layout Freshness & Identity Validation:* Text row layouts are encapsulated in `RowTextLayout(virtualRow, physicalLine, layout, coordinates)`. Recomposed or recycled rows republish fresh layouts via `SideEffect` on every scroll step or jump so duplicate or blank lines never retain stale coordinates, and hit-testing validates entries by `(virtualRow, physicalLine)` before resolving offsets.
  - *Conditional Multi-Line Chevrons:* Interactive folding chevrons (`"▼"` / `"▶"`) render exclusively for event headers spanning multiple lines (`eventSpan > 1`) and lines with virtual expansion (`session.pieceTable().hasVirtualExpansion()`), remaining completely hidden for plain indented lines.
  - *Retina 2.0x Density & Full-Wrap Scroll Alignment:* Replaced hardcoded pixel constants in `ViewportTextRow.kt` and `LogViewportCanvas.kt` with density-aware pixel calculations (`8.dp.toPx()` text padding and `1.dp.toPx()` gutter border) and guarded `horizontalScrollValue` when `isFullWrap == true`, eliminating context menu leftward drift on high-DPI Retina displays.

- **Tri-Mode Coordinate Translation & Unsigned 64-Bit Safety:**
  - Fixed coordinate translation when toggling between Full View, Temporal Slice View, and Filtered View: `topVisibleLine` correctly converts between absolute physical indices and relative temporal offsets $[0, \text{endLine} - \text{startLine}]$, while `selectedLineIndex` remains strictly physical.
  - Added non-negative boundary clamps in `SearchResult.constrainToRange`, `SearchResult.findNextLine`, `SearchResult.findPreviousLine`, and `FilteredLineView.virtualIndexForPhysicalLine` to prevent negative coordinates from triggering unsigned 64-bit integer overflow in `Roaring64NavigableMap`.

### 4.7 Modular UI & Multi-Pane Docking Subsystem
Implemented in `umbrix.ui.docking`:
- **Recursive Binary Docking Tree (`DockNode`):**
  - Modeled as a pure recursive binary tree with `DockNode.Leaf` (tab IDs, active tab ID) and `DockNode.Split` (orientation, children, `splitRatio`, `isSyncScrollEnabled`).
  - Pure tree mutation algorithms in `DockTreeManager`: `splitLeaf`, `removeTab` (with automatic empty-leaf pruning and unary split collapse), `moveTab`, `addTabToLeaf`, `findLeafForTab`, and `equalizeSplits`.
- **Draggable Proportional Split Dividers (`DockSplitDivider`):**
  - 7dp interactive hit target with 1dp center hairline divider (`UmbrixTheme.colors.border`).
  - OS resize cursors (`Cursor.E_RESIZE_CURSOR` / `Cursor.N_RESIZE_CURSOR`), hover glow styling, and smooth $[0.15, 0.85]$ ratio clamping.
  - Double-click divider reset to 50/50 balance.
  - Synchronized scrolling `⇋` toggle button embedded directly on the split divider, enabling delta-line lock-step scrolling between split logs.
- **Drag-to-Dock Coordinate Tracking & Ghost Outline (`DockDragCoordinator`, `DockGhostOverlay`):**
  - Hit-testing against registered pane bounds with $35\%$ edge threshold up to 220px for generous split snapping, with boundary tolerance maintaining snaps when slamming against monitor borders.
  - Dedicated tab bar boundary registration (`registerTabBarBounds`, `registerTabBounds`): pointer hovering over any tab strip calculates the exact slot index ($0 \dots N$) and renders a crisp 3dp vertical insertion caret indicator (`UmbrixTheme.colors.accentPrimary`) instead of a full canvas split box.
  - Full-screen animated translucent drop outline rendered atop docking host with floating action chips (`◧ Split Right`, `⬒ Split Bottom`, `◨ Split Left`, `⬓ Split Top`, `⧉ Group with Tab`).
  - Dragged tab pill showing document icon and title following cursor.
- **Tab Reordering, Cross-Pane Moving & Automatic Pane Collapse (`DockTreeManager.moveTab`):**
  - Within-leaf reordering: dragging tabs within the same pane calculates target insertion slot and shifts indices cleanly (`insertIndex > currentIndex ? insertIndex - 1 : insertIndex`).
  - Cross-pane movement: dragging a tab into another pane's tab strip inserts it at the exact target slot.
  - Automatic split pruning and collapse: when a tab is moved out of a leaf leaving it with 0 tabs, the empty leaf is automatically pruned and its parent `DockNode.Split` collapses to elevate the surviving sibling, smoothly merging split panes back together.
- **Per-Pane Tab Bar & Mixed Content Support (`DockPaneTabBar`, `DockPaneView`):**
  - Option A architecture: each split pane owns a localized tab bar with drag initiation (>6dp), middle-click close, active accent indicator, full tab context menus, and quick pane actions (`+`, `◫`, `⬒`, `⤢`, `✕`).
  - Universal content hosting: interchangeably hosts `LogViewportCanvas` or `ScratchpadEditorCanvas` in any pane.
  - Pointer press interception (`PointerEventType.Press`) guaranteeing immediate tab selection and pane focus on primary mouse down without delaying for drag thresholds.
- **Keyboard Shortcuts & Zero-Cost Document Cloning:**
  - `Ctrl+\` (Split Right) and `Ctrl+Alt+\` (Split Down) duplicate the document view if the pane contains only 1 tab, allowing simultaneous inspection of different line ranges of the same multi-gigabyte log.
  - Split tabs share the underlying thread-safe off-heap `LogSession` zero-copy; session closure guards against disposal until the last instance closes.
  - `Ctrl+Alt+=` equalizes all split dividers; `Ctrl+Shift+W` closes the active pane; `F6` cycles focus across panes.
- **Secondary Floating Desktop Windows (`DetachedWindowsHost`):**
  - Standalone Compose Desktop `Window`s hosting popped-out tabs with re-docking button (`⤓`) and native window close handling.

### 4.8 Workstation Enhancements & Forensic Usability
1. **Search Navigation & Wrap Control (`MainScreenSearchController`):**
   - Anchor-Relative Navigation: Searches anchor to the active reading position (`selectedLineIndex ?: topVisibleLine`). Modifying query text or pressing Enter jumps to the nearest matching line strictly *at or below* the current position.
   - Non-Destructive Wrap Protection: Automatic wrapping to the beginning of the file is prevented. If no matches remain below the current line, a prompt `"End of file reached. Press Enter to wrap to top"` is displayed. Only upon explicit second Enter/Shift+Enter confirmation does wrap occur, accompanied by a visual wrap indicator (`🔁 Wrap to top / bottom`).
2. **Extended Temporal Checkpoint Parsers & Single-Day Scope Detection (`TemporalCheckpointTable`):**
   - Added zero-heap off-heap timestamp parsers for:
     - Time-of-Day timestamps (`HH:mm:ss[.SSS|,SSS]`, e.g. `14:23:01.450`).
     - Bracketed timestamps (`[HH:mm:ss]`).
     - Month-Day Time (`MM-dd HH:mm:ss`, `MMM dd HH:mm:ss`).
     - 2-digit years (`yy-MM-dd HH:mm:ss`).
     - US / European Date Formats (`MM/dd/yyyy HH:mm:ss`, `dd/MM/yyyy HH:mm:ss`).
   - 10-Digit Epoch False-Positive Protection: Guarded Unix epoch parser with strict numeric boundaries and reasonable epoch timestamp sanity limits ($[2010, 2038]$), preventing 10-digit payload data or random numbers from generating false 100-year temporal spans.
   - Single-Day Scrubber Badge: `TemporalRangeScrubber` detects when the total file span is within a single calendar day (<24 hours) and displays `Single Day (Duration: Xh Ym)` alongside clean Time-of-Day boundary labels.
3. **Themed Scratchpad Editor (`ScratchpadEditorCanvas`):**
   - Full synchronization with `UmbrixThemeSpec` across all 10 theme presets (IntelliJ Dark, Cyberpunk 2077, Nord Deep Blue, Monokai Pro, Gruvbox Dark, Obsidian Void, Dracula Vampire, Solarized Dark, GitHub Dark, Carbon Emerald).
   - Custom gutter rendering line numbers matching `surfaceGutter`, `gutterLineNormal`, and `gutterBorder`.
   - Syntax coloring for log levels (`[ERROR]`, `[WARN]`, `[INFO]`, `[DEBUG]`, `[TRACE]`) and configurable editor typography via `UmbrixFonts`.
4. **Application Branding & Custom Monogram (`UmbrixIcon`):**
   - Dark dual-tone monogram badge (matte slate-indigo `#424B66` transitioning through teal into mint/cyan `#98F1D2` "U") on a dark charcoal squircle with a chamfered border rim and smooth alpha transparency.
   - Matte Finish & Chromatic Flow: Soft satin-matte slate texture (`#424B66`) on the left arm, paired with a facet cut and a continuous gradient transition around the base curve into mint (`#98F1D2`).
   - 3D Border & Sharpening: Composited border (top highlight, perimeter rim bevel, and inner dark groove) with micro-sharpening for crisp silhouette definition at all resolutions.
   - 100% Full-Bleed Dock Sizing: Full-bleed squircle rasters span 100% of the icon tile boundary (`[0, 0]` to `[size-1, size-1]`), matching native Windows desktop icon sizing.
   - Optical Header Alignment: Sized the title bar icon to `22.dp` in `IntegratedTitleBar.kt` for vertical and horizontal balance alongside the 26.dp hamburger menu.
   - Multi-Resolution Pipeline & Deduplicated Bundling: Single source of truth in `umbrix-ui/src/main/resources/icons` (eliminating multi-module resource collisions during fat JAR and `jpackage` bundling). Serves pre-rendered exact-match rasters (`16x16` through `512x512`) from memory cache, with bicubic downsampling fallback. Registers full-bleed taskbar image via `Taskbar.getTaskbar().iconImage` and `window.iconImages`. Includes multi-frame `umbrix.ico` (9 sizes: 16–256) and macOS `Umbrix.iconset`.
5. **Tab-Based Incident Timeline Integration (`InvestigationTimelineCanvas`):**
   - Migrated Incident Timeline from a fixed bottom drawer into a first-class dockable workspace tab (`timeline://incident`).
   - Supports full multi-pane split layout (Split Right `Ctrl+\`, Split Down `Ctrl+Alt+\`), tab dragging, pane docking, and popping out into secondary floating windows.
   - Interactive events list with search, category filtering, chronological sorting, and direct jump to source log file and line number.
6. **Unified In-Scratchpad & Workstation Search (`MainScreenSearchController`, `ScratchpadEditorCanvas`):**
   - Full Workstation SearchBar Integration: The primary workstation `SearchBar` (Ctrl+F, F3/Shift+F3, match navigation `↑`/`↓`, telemetry badges, search error feedback, and whole-word/match-case/regex toggles) seamlessly searches active scratchpad buffers.
   - Synchronized Search State (`WorkspaceTab`): Populates `tab.searchResult` via `Roaring64NavigableMap` with total match counts and matching line indices in `triggerScratchpadSearch`, eliminating grayed-out navigation buttons and enabling the `⬡ Filter Matching Lines` action on scratchpads.
   - Fast $O(\log L)$ Coordinate Translation: Precomputes line starting offsets (`computeLineStartOffsets`) and utilizes binary search (`findLineIndexForOffset`) to map in-memory match character intervals to line numbers in logarithmic time.
   - Smooth Match Scrolling & Centering: `ScratchpadEditorCanvas` watches `tab.searchNavigationGeneration` to auto-scroll and center the active matched line on screen upon navigation, with visual active/inactive highlight discrimination.
   - Bi-Directional Canvas Find/Replace Deck: In-canvas find/replace bar (Ctrl+R / Find button) stays bi-directionally synchronized with `WorkspaceTab.searchQuery` and mode flags, supporting single-occurrence replacement and Replace All.
   - Filtered View & Sub-File Export Support: Supports toggling filtered matching lines view on scratchpads with original line numbers in the gutter, and exporting full scratchpad content or filtered match subsets to standalone disk files.
   - Wrap-Around Protection: Prevents abrupt wrapping during forward/backward navigation, issuing an informative confirmation prompt ("Reached end. Press Enter to wrap to top") before wrapping on repeated execution.

### 4.9 Native Distribution, Single-Instance IPC & Packaging Pipeline
Implemented in `umbrix-app/build.gradle.kts`, `SingleInstanceCoordinator.kt`, and `distribution/`:
- **Stripped Azul OpenJDK 25 Runtime (`createMinRuntime`):**
  - Synthesizes a dedicated minimal modular JRE via `jlink` (`--compress zip-6`, `--strip-debug`, `--no-man-pages`, `--no-header-files`).
  - Runtime image stripped from 380MB down to 85.3 MB uncompressed.
  - Bundles mandatory platform modules: `jdk.incubator.vector` (AVX-512 vector pipelines), `java.desktop` (AWT/Skia hardware rendering), `java.net.http`, `jdk.httpserver` (embedded MCP server), `jdk.unsupported` (`Unsafe` internal stubs required by fastutil/RoaringBitmap), and `jdk.crypto.mscapi` (Windows TLS keystore).
- **Standalone Native Application Image (`createAppImage`):**
  - Generates cross-platform desktop application bundles via JDK 25 `jpackage --type app-image`.
  - On Windows: outputs `Umbrix.exe` + bundled `runtime/` + multi-frame `umbrix.ico` dock asset.
  - On macOS: outputs `Umbrix.app` + bundled runtime + `Umbrix.icns`.
- **Zero-Admin Portable Distribution (`packagePortableZip`):**
  - Packages `Umbrix-1.0.1-windows-x64-portable.zip` (~160MB compressed) containing the full app-image and context-menu setup utilities.
  - Requires 0 administrator / UAC privileges to install or run.
- **Single-Instance Loopback IPC Router (`SingleInstanceCoordinator`):**
  - Listens on `127.0.0.1:4243` to coordinate file launches across processes.
  - On launch, the coordinator tests connection to port 4243. If a running primary instance responds, the secondary process transmits `OPEN <filePath>\n` commands over TCP and exits cleanly (`exitProcess(0)`).
  - Primary instance decodes incoming paths, dispatches them reactively into `incomingFiles: Flow<List<String>>`, brings the application window to foreground (`window.toFront()`, `requestWindowFocus()`), and un-minimizes the frame if minimized.
- **Cross-Platform Context Menu & File Associations:**
  - Windows User-Space Context Menu (`register-open-with.bat` & `unregister-open-with.bat`): Writes directly to `HKCU\Software\Classes\*\shell\OpenWithUmbrix` and file extension progids (`.log`, `.txt`, `.out`, `.err`, `.trace`, `.jsonl`, `.gz`) without requiring administrator elevation.
  - Windows Installer (`distribution/windows/installer.iss`): Complete Inno Setup script generating a standalone wizard installer `Umbrix-1.0.1-windows-x64-Setup.exe`.
  - macOS Document Types: `distribution/macos/Info.plist` declares `CFBundleDocumentTypes` associating Umbrix with `public.plain-text`, `public.log`, `.log`, `.txt`, `.out`, `.err`, `.trace`, and `.jsonl`.
  - macOS Event Handler: Registers `Desktop.getDesktop().setOpenFileHandler` to seamlessly consume AppleEvents triggered by Finder "Open With" or file drag-and-drop.
  - Homebrew Cask (`distribution/macos/umbrix.rb`): Standalone cask formula supporting single-command installation (`brew install --cask umbrix`).

### 4.10 Standalone Headless Engine Library (`umbrix.engine.LogEngine`) & Throughput Optimizations
- **Zero-UI Headless Facade (`umbrix.engine.LogEngine`, `EngineOptions`):**
  - Encapsulates the entire Umbrix off-heap ingestion, SIMD search, temporal navigation, and slicing stack into a standalone, embeddable Scala/Java engine library without any UI or Compose dependencies.
  - Lifecycle: `val engine = LogEngine.open(path, EngineOptions(enableIndexCache = true))` -> `engine.search(...)`, `engine.searchProgressive(...)`, `engine.getLineText(...)`, `engine.getLineSlice(...)`, `engine.seekTimestamp(...)`, `engine.exportSlice(...)` -> `engine.close()`.
  - Configurable: `EngineOptions` exposes worker thread allocation, cache directory, live tailing toggle, and line clamping bounds.

#### Measured Multi-Gigabyte Performance (Azul OpenJDK 25 LTS / AVX-512)

| Benchmark Metric | Hadoop HDFS Namenode (5.42 GB, 16.89M lines) | Sandia Thunderbird (31.79 GB, 211.21M lines) |
| :--- | :---: | :---: |
| **Raw File Size** | 5,420,477,841 bytes (5.05 GB) | 31,788,301,041 bytes (29.61 GB) |
| **Total Physical Lines** | 16,892,910 lines | 211,212,192 lines |
| **Cold Time-to-First-Render (Chunk 0)** | **140 ms** | **59 ms** |
| **Cold Single-Pass Ingestion Time** | **2.06 s (2,061 ms)** *(was 5.67s)* | **22.64 s (22,641 ms)** *(was 31.8s + bg)* |
| **Cold Ingestion Scan Throughput** | **2.45 GB/s** | **1.31 GB/s** |
| **Warm Cached Open (.idx + .t2 hit)** | **717 ms** *(6.7x speedup)* | **830 ms** *(34.3x speedup)* |
| **Sparse Index RAM Overhead** | **1.01 MB** (0.0196% of file) | **12.69 MB** (0.0419% of file) |
| **Single-Literal SIMD Search** | 928 ms (**5.43 GB/s**) — 802 hits | 15.31 s (**1.93 GB/s**) — 6,504,016 hits |
| **Fused Dual-Literal SIMD (AND)** | 1,450 ms (**3.48 GB/s**) — 82 hits | 11.57 s (**2.56 GB/s**) — 6,497,726 hits |
| **Fused Dual-Literal SIMD (OR)** | 886 ms (**5.69 GB/s**) — 1,132 hits | 10.51 s (**2.82 GB/s**) — 15,988,582 hits |
| **JVM GC Heap Invariant** | **11 MB used** / 4096 MB max | **25 MB used** / 4096 MB max |

#### Memory Spike & Viewport Scrolling Memory Leak Resolutions
1. **Initial File Ingestion GC Memory Spike Eliminated:**
   - *Problem:* During initial 32GB cold file ingestion, `FusedStreamingIndexer` previously called `state.superTable.clone()` and `state.intraTable.clone()` on every single 32MB chunk (~1,000 chunks). With `intraTable` growing to 5M primitive entries (20MB), this generated **~10–20 GB of garbage arrays** in 20 seconds, spiking JVM heap and triggering constant young-gen GC pauses.
   - *Fix:* Throttled snapshot creation to `(now - lastProgressReportTime >= 250L || isFinished)`, reducing snapshot creations from 1,000 down to ~40 over a 20s run (<300MB total temporary allocations). Virtual thread executor is deterministically shut down in `finally`, and `storageReader.trimWorkingSet()` / `PagingAdvisory.trimWorkingSet()` is invoked upon physical index completion, Tier-2 completion, and cache writes to flush committed pages to standby RSS.
2. **Viewport Scrolling Slot-Table Memory Leak Eliminated:**
   - *Problem:* In `LogViewportCanvas.kt` and `ViewportGutter.kt`, `key(physicalLine, rowIndex)` wrapped each of the 50 screen row composables. Because `physicalLine` changes on every single scroll frame, Compose destroyed and recreated all 50 row compositions per frame, reallocating `remember { MutableInteractionSource() }`, modifier chains, and slot tables without recycling nodes.
   - *Fix:* Removed `key(physicalLine, rowIndex)` wrappers. Compose now stably reuses the 50 slot nodes across all virtual scrolling positions, recomposing only primitive parameters with zero memory leak.

---

## 5. Engineering & Code Style Disciplines

1. **High-Value Architectural Comments & Docstrings:** Architectural and class-level docstrings on complex classes, ports, and native adapters are strongly encouraged to explain operational roles, zero-heap invariants, and failure mitigations. Inline comments must be concise and explain *why* non-obvious architecture exists; useless filler comments describing obvious syntax are prohibited.
2. **Trait Implementation Discipline:** Do not use `override` when implementing abstract trait methods in Scala 3; use `override` strictly when overriding concrete methods with default implementations.
3. **Descriptive Identifier Naming:** Avoid cryptic single-character or abbreviated names (`n`, `fd`, `rfRes`, `s1`). Use clear, readable, descriptive names (`bytesTransferred`, `fileDescriptor`, `fileSizePointer`, `initialSlice`).
4. **Functional Transformations & Memory Efficiency:**
   - Use `.view` when chaining operations (`map`, `filter`, `take`) on collections to eliminate intermediate heap objects.
   - Use `foldLeft` for high-performance loops requiring zero allocation.
   - Use recursion (annotated with `@tailrec`) for early termination/short-circuiting.
   - Strictly avoid early `return` keywords; write pure expression-oriented code.
5. **Zero Heap Ingestion:** Raw log text lives strictly in off-heap direct memory segments; strings are instantiated solely for on-screen viewport rows (~50 lines).
6. **Classical Curly Brace Standardization:** All Scala source files strictly use standard curly braces (`{ ... }`). Python-style significant indentation (`:`, `then`, `do`) is prohibited across all modules.


