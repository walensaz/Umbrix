# Umbrix Architecture & Engineering Specifications

> **Mission:** Desktop log and forensic analysis platform for investigating outages and production issues across **20GB–100GB+ log files** with **<2s initial render**, **<150MB RSS**, and **0 bytes of log data on the JVM GC heap**.

---

## 1. Specification Index & Overview

This directory contains the public technical specifications, architectural standards, performance analyses, and design blueprints for the **Umbrix** platform.

| Specification | Primary Scope & Subsystem | Core Technical Invariants & Mechanisms |
| :--- | :--- | :--- |
| **[`USER_GUIDE.md`](USER_GUIDE.md)** | End-User Usage Guide | Opening logs, tri-mode viewport, search & query AST, forensic triage workflows, settings reference, and diagnostics. |
| **[`ARCHITECTURE.md`](ARCHITECTURE.md)** | System Architecture & Subsystem Boundaries | Module topology, clean architecture layers, off-heap zero-heap memory model (FFM), and two-tier indexing pipeline. |
| **[`SYSTEM_DESIGN.md`](SYSTEM_DESIGN.md)** | Deep Component Design & Subsystem Contracts | Cross-module API contracts, thread pools, off-heap sparse index coordinate systems, and query execution engine. |
| **[`MEMORY_AND_PERFORMANCE_ANALYSIS.md`](MEMORY_AND_PERFORMANCE_ANALYSIS.md)** | Memory Footprint & SIMD Optimization | Hardware memory budgets, zero-heap byte-level guarantees, AVX-512/AVX2 vector sweeps, and working set eviction. |
| **[`UI_ARCHITECTURE_AND_VIEWPORT.md`](UI_ARCHITECTURE_AND_VIEWPORT.md)** | Desktop UI & 64-Bit Skia Viewport | Compose Desktop (Jewel/Skiko), tri-mode viewport geometry (compact, expandable, full wrap), and 4,096-char safety clamp. |
| **[`BIG_DATA_AND_LIVE_STREAMING.md`](BIG_DATA_AND_LIVE_STREAMING.md)** | Dual-Path I/O & Ingestion Topologies | Zero-copy static `mmap`, non-locking Win32 Overlapped / POSIX positional live tailing, and remote streaming roadmap. |
| **[`MCP_SERVER_AND_AGENT_ECOSYSTEM.md`](MCP_SERVER_AND_AGENT_ECOSYSTEM.md)** | Embedded MCP Server & AI Governance | Embedded Model Context Protocol (MCP) JSON-RPC 2.0 server, tri-tier AI governance, and forensic triage toolkits. |
| **[`PLUGIN_DEVELOPMENT_GUIDE.md`](PLUGIN_DEVELOPMENT_GUIDE.md)** | Extensibility SDK & Plugin SPI | Custom log format providers, AST function registration, companion tool windows, and isolated plugin distribution. |
| **[`CODE_GUIDELINES_AND_STANDARDS.md`](CODE_GUIDELINES_AND_STANDARDS.md)** | Coding Standards & Idiomatic Templates | SOLID engineering practices, zero-boxing discipline, coordinate math rules, and classical-brace Scala 3 conventions. |
| **[`LIBRARIES_ECOSYSTEM.md`](LIBRARIES_ECOSYSTEM.md)** | Dependency Ecosystem & Tech Stack | Technical rationale for zero-reflection, low-allocation dependencies (fastutil, RoaringBitmap, RE2J, LangChain4j). |
| **[`TESTING_STRATEGY.md`](TESTING_STRATEGY.md)** | Verification Taxonomies & CI Matrix | Multi-tier test taxonomy (unit, integration, property-based, differential fuzzing), CI workflows, and test commands. |
| **[`BENCHMARKS.md`](BENCHMARKS.md)** | Performance Benchmarks & Baselines | Empirical throughput baselines, latency percentiles across multi-gigabyte datasets, and validation suites. |

---

## 2. Module Dependency Hierarchy

Umbrix enforces strict bottom-up module decoupling. Lower-level native I/O and indexing modules maintain zero UI or domain awareness:

```
[umbrix-vfs]  [umbrix-simd]       (Standalone zero-dependency native I/O & SIMD kernels)
      \            /
      [umbrix-index]              (Two-level sparse index, temporal checkpoints, piece table)
            |
      [umbrix-core]               (Scala 3 domain model, filter AST, query coordinator)
       /         \
[umbrix-mcp]  [umbrix-ui]         (JSON-RPC MCP server | Kotlin Compose Desktop + Skia viewport)
       \         /
      [umbrix-app]                (Composition root, constructor DI wiring, jpackage launcher)
```

- **`umbrix-vfs`**: Zero-dependency cross-platform native file access (static `mmap` and live Overlapped `ReadFile` / `pread`).
- **`umbrix-simd`**: Java Vector API SIMD newline scanners and string search kernels (`jdk.incubator.vector`).
- **`umbrix-index`**: Two-level sparse line indexing, temporal checkpoints, and off-heap Piece Table edit buffers.
- **`umbrix-core`**: Expression-oriented Scala 3 domain abstractions, RE2J linear search engine, and forensic diagnostics.
- **`umbrix-plugin-api`**: Public service provider interfaces (SPI) for third-party format providers and analysis tools.
- **`umbrix-mcp`**: Embedded JSON-RPC Model Context Protocol (MCP) server enabling AI agent pairing.
- **`umbrix-ui`**: JetBrains Jewel + Compose Desktop + Skia interactive 64-bit viewport and docking workspace.
- **`umbrix-app`**: Composition root, compile-time constructor dependency injection, and native desktop installer packaging.

---

## 3. Core Architectural Invariants

1. **Zero Heap Ingestion:** All raw log bytes remain strictly off-heap in `java.lang.foreign.MemorySegment` or OS page cache. JVM `String` objects are allocated solely for the ~50 lines currently visible in the active UI viewport.
2. **Deterministic Off-Heap Lifecycles:** Native memory allocations are bound to explicit `Arena.ofShared()` or `Arena.ofConfined()` scopes—never relying on JVM garbage collection or finalizers.
3. **Zero Boxing on Hot Paths:** Primitive streams avoid boxing via Scala 3 `opaque type`, `Array[Long]`, fastutil primitive collections, and 64-bit Roaring bitmaps (`Roaring64NavigableMap`).
4. **Dual-Path Cross-Platform I/O Safety:**
   - *Static Files:* Continuous zero-copy memory mapping (`MemorySegment.mapFile`).
   - *Active / Live Files:* Win32 Overlapped `ReadFile` with `FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE` (Windows) and POSIX positional `pread` (Unix), preventing file lock violations and `copytruncate` `SIGBUS` crashes.
5. **ReDoS Immunity:** Regular expressions are strictly evaluated using `com.google.re2j` with guaranteed $O(N \cdot M)$ linear execution time.
6. **Tri-Mode Viewport & 4,096-Char Safety Clamp:** The UI viewport enforces a strict 4,096-character safety clamp per line (`[... +XX KB payload]`) before submitting text to HarfBuzz/Skia text layout engines.
