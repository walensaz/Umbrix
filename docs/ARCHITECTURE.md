# Umbrix System Architecture

---

## Table of Contents
- [1. System Overview & Core Invariants](#1-system-overview--core-invariants)
- [2. Memory & Storage Subsystem (FFM API & Dual-Path I/O)](#2-memory--storage-subsystem-ffm-api--dual-path-io)
  - [2.1 Java 25 LTS Foreign Function & Memory (FFM) API](#21-java-25-lts-foreign-function--memory-ffm-api)
  - [2.2 Dual-Path Cross-Platform Storage Architecture](#22-dual-path-cross-platform-storage-architecture)
  - [2.3 Two-Level Hierarchical Sparse Index](#23-two-level-hierarchical-sparse-index)
- [3. Temporal Range Queries & Wall-Clock Binary Seeks](#3-temporal-range-queries--wall-clock-binary-seeks-olog-n)
  - [3.1 Temporal Checkpoint Table](#31-the-temporal-checkpoint-table)
  - [3.2 Monotonic Fast-Path & Out-of-Order Resilience](#32-dual-path-monotonic-fast-path--out-of-order-resilience-architecture)
  - [3.3 Inter-Record Latency Gap Scanner](#33-inter-record-latency-gap-scanner-o1-off-heap-maps)
- [4. Zero-Copy Log Trimming & Slicing Architecture](#4-zero-copy-log-trimming--slicing-architecture)
- [5. Throughput Acceleration & SIMD Scanning](#5-throughput-acceleration--simd-scanning)
  - [5.1 Java Vector API](#51-java-vector-api-jdkgincubatorvector)
  - [5.2 Proof of UTF-8 Invariance on Byte 0x0A](#52-proof-of-utf-8-invariance-on-byte-0x0a)
  - [5.3 Vectorized Case-Insensitive Substring Filtering](#53-vectorized-case-insensitive-substring-filtering)
  - [5.4 Cooperative Cancellation & VM Contention](#54-cooperative-cancellation-thread-headroom--cross-platform-vm-contention-architecture)
  - [5.5 ReDoS-Immune AST Needle Extraction](#55-natural-multi-word-phrase-matching--redos-immune-ast-needle-extraction)
- [6. Compose Desktop 64-Bit Viewport Virtualization & Tri-Mode Layout](#6-compose-desktop-64-bit-viewport-virtualization--tri-mode-layout)
- [7. Cross-Platform OS Kernel & GPU Matrix](#7-cross-platform-os-kernel--gpu-matrix)
- [8. Embedded Model Context Protocol (MCP) Server](#8-embedded-model-context-protocol-mcp-server)
- [9. Architectural Patterns, SOLID & Clean Code Discipline](#9-architectural-patterns-solid--clean-code-discipline)
- [10. Progressive Background Indexing Architecture](#10-progressive-background-indexing-architecture)
- [11. Asynchronous Off-Heap Piece Table Architecture (File Editing)](#11-asynchronous-off-heap-piece-table-architecture-file-editing)
- [12. Cross-Platform Native Packaging, Single-Instance Routing & Distribution](#12-cross-platform-native-packaging-single-instance-routing--distribution)

---

## 1. System Overview & Core Invariants

Umbrix is a desktop log and forensic analysis platform for investigating outages and production issues. It opens, indexes, and searches multi-gigabyte ($20\text{ GB} - 100\text{ GB}+$) log files with sub-second initial load, 60–120 FPS scrolling, and minimal memory consumption ($<150\text{ MB}$ RSS for a 25GB file) by keeping all log data off-heap. Its Apache 2.0 Plugin SDK (`:umbrix-plugin-api` / `:umbrix-plugin-ui-api`) extends the platform to custom formats and analyzers (such as the upcoming JVM diagnostic plugin, which will be published separately).

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                  PRESENTATION LAYER (Compose Desktop + Skia)                 │
│  - JetBrains Jewel (IntelliJ New UI design system, dark carbon theme)        │
│  - Custom 64-bit Virtual Viewport (Sliding window anchored at local (0, 0))  │
│  - GPU-rendered Minimap / Seismograph Gutter (1,000-bin Skia canvas)         │
│  - Multi-pane split docking, incident timeline, and plugin dock views        │
└──────────────────────────────────────▲───────────────────────────────────────┘
                                       │ Zero-Copy FFM MemorySegment Slices
┌──────────────────────────────────────▼───────────────────────────────────────┐
│                    CORE OFF-HEAP ENGINE (Scala 3 / JDK 25)                   │
│                                                                              │
│  ┌─────────────────────────────────┐   ┌──────────────────────────────────┐  │
│  │ Dual-Path Storage Subsystem     │   │ Two-Level Hierarchical Index     │  │
│  │ - Java 25 FFM API (Arena)       │   │ - Super-stride: 16,384 lines     │  │
│  │ - Windows non-locking IOCP reads│   │ - Intra-stride: 64 lines         │  │
│  │ - Unix SIGBUS-immune pread/uring│   │ - ~13 MB RAM for 25GB file       │  │
│  └─────────────────────────────────┘   └──────────────────────────────────┘  │
│                                                                              │
│  ┌─────────────────────────────────┐   ┌──────────────────────────────────┐  │
│  │ SIMD Vector Search Engine       │   │ Real-Time Stream Ingestion       │  │
│  │ - Java Vector API (AVX-512/AVX2)│   │ - Off-Heap Circular Ring Buffer  │  │
│  │ - 2-byte Ripgrep-style filter   │   │ - Multi-source: SSH, K8s, Syslog │  │
│  │ - 15–25 GB/s per host throughput│   │ - Dynamic extent page expansion  │  │
│  └─────────────────────────────────┘   └──────────────────────────────────┘  │
│                                                                              │
│  ┌─────────────────────────────────┐   ┌──────────────────────────────────┐  │
│  │ Temporal Checkpoint Index       │   │ Zero-Copy Trimming & Slicing     │  │
│  │ - O(log N) binary time seek     │   │ - APFS clonefile (macOS)         │  │
│  │ - Wall-clock ISO range filters  │   │ - splice / copy_file_range(Linux)│  │
│  └─────────────────────────────────┘   └──────────────────────────────────┘  │
└──────────────────────────────────────▲───────────────────────────────────────┘
                                       │ Asynchronous Context Assembly
                                       │ & MCP Tool Execution
┌──────────────────────────────────────▼───────────────────────────────────────┐
│                   EMBEDDED MCP SERVER & FORENSIC TOOLS                       │
│  - Embedded Model Context Protocol (MCP) Server for Copilot, Codex, Claude   │
│  - Causal Thread/Trace Reconstruction (MDC trace_id & thread boundary scan) │
│  - Off-Heap Error Clustering (Normalized frame SimHash / MinHash)            │
│  - Dynamic Plugin MCP Tool Registry & Fault-Isolated Plugin Host             │
│  - Client-Side Data Loss Prevention (DLP) & High-Entropy Secret Sanitization  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Core Performance Invariants
1. **Zero JVM Heap Ingestion:** Raw log bytes are never converted into `java.lang.String` collections on the JVM heap. All text resides in native page cache or direct off-heap memory.
2. **GC Independence:** All index structures and search result buffers reside strictly off-heap via Java 25 LTS Foreign Function & Memory (FFM) API (`Arena.ofShared()` / `Arena.ofConfined()`).
3. **64-Bit Viewport Scale:** The layout engine supports $2^{63}-1$ lines, completely eliminating 32-bit Integer overflow and IEEE-754 single-precision float scroll jitter.
4. **Direct GPU Blitting:** UI rendering executes directly on the GPU via Skia (DirectX 12 on Windows, Metal on macOS, Vulkan on Linux).
5. **Fast Temporal Seeking:** $O(\log N)$ binary search on timestamp checkpoints finds any wall-clock second across 25GB in $<2\text{ ms}$.
6. **Zero-Copy Slicing:** Log trimming and exporting pipes bytes directly inside the OS kernel without user-space memory copies.

---

## 2. Memory & Storage Subsystem (FFM API & Dual-Path I/O)

### 2.1 Java 25 LTS Foreign Function & Memory (FFM) API
Instead of legacy `sun.misc.Unsafe` or `MappedByteBuffer` (which suffers from a 2GB buffer limit, non-deterministic unmapping, and kernel file locking), the engine uses standardized Java 25 LTS FFM (`java.lang.foreign.*`):

- **`Arena.ofShared()`**: Used for memory-mapped file segments and global index tables. Thread-safe, accessible across all SIMD worker threads. Explicitly closed via `arena.close()` to deterministically unmap files and release kernel locks immediately.
- **`Arena.ofConfined()`**: Used for per-thread transient search buffers, decompression scratchpads, and temporary vector masks. Zero synchronization overhead.
- **`MemorySegment.asSlice(offset, length)`**: Performs zero-copy pointer arithmetic:
  $$\text{address}_{\text{slice}} = \text{baseAddress} + \text{offset}$$
  Produces lightweight off-heap segment handles without copying underlying pages.

### 2.2 Dual-Path Cross-Platform Storage Architecture
To support both static incident post-mortems and live-tailing rotating log files across Windows, Linux, and macOS without kernel deadlocks or process crashes:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     DUAL-PATH STORAGE SUBSYSTEM CONTRACT                    │
├───────────────────────┬──────────────────────────┬──────────────────────────┤
│ METRIC / CAPABILITY   │ PATH A: STATIC / ARCHIVED│ PATH B: ACTIVE / LIVE    │
├───────────────────────┼──────────────────────────┼──────────────────────────┤
│ Target Scenario       │ Static .log, .gz, dumps  │ Active logs, live tailing│
│ Primary Class         │ `MemoryMappedLogReader`  │ `PositionalLogReader`    │
│ Primary Mechanism     │ FFM `FileChannel.map`    │ Positional unbuffered I/O│
│ Windows NT Protection │ Continuous mmap          │ Win32 Overlapped ReadFile│
│                       │ (`Arena.ofShared()`)     │ with FILE_SHARE_DELETE   │
│ Linux/macOS Protection│ High-throughput mmap     │ Positional pread         │
│                       │ (`Arena.ofShared()`)     │ (immune to SIGBUS)       │
│ Universal Fallback    │ Direct FFM MemorySegment │ Zero-heap NIO FileChannel│
│ File Rotation Safe?   │ Closed via Arena.close() │ 100% immune to locks     │
│ File Truncation Safe? │ Static files do not shrink│ 100% immune to SIGBUS   │
│ Paging Advisory       │ PrefetchVirtualMemory /  │ Non-blocking OS kernel   │
│                       │ madvise(MADV_WILLNEED)   │ readahead caching        │
└───────────────────────┴──────────────────────────┴──────────────────────────┘
```

1. **Windows NT Kernel Section Lock Solution (`Win32PositionalHandle`):** On Windows, `mmap` creates an NT `SECTION_OBJECT` that triggers `ERROR_SHARING_VIOLATION` (error 32) or `ERROR_USER_MAPPED_FILE` when external loggers rotate or truncate files. For active files, Umbrix bypasses section mapping entirely, issuing unbuffered Win32 Overlapped `ReadFile` calls with `FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE` via direct Java 25 FFM Panama downcalls to `kernel32.dll`.
2. **Unix `SIGBUS` File Truncation Solution (`PosixPositionalHandle`):** On Linux and macOS, if an active file is truncated in-place (e.g. `logrotate copytruncate` or `truncate -s 0`), reading beyond the new truncated boundary on an active memory map causes an immediate unrecoverable hardware `SIGBUS` crash. Umbrix utilizes positional `pread` via Panama libc downcalls into reusable direct off-heap buffers for active files, completely neutralizing `SIGBUS` risks.
3. **Universal Cross-Version Fallback (`NioPositionalHandle`):** In environments where native downcalls are unavailable or restricted, Umbrix provides an automatic, transparent fallback to direct off-heap NIO `FileChannel.read(ByteBuffer.allocateDirect)` with 0 bytes of heap garbage, guaranteeing 100% cross-platform and cross-version resilience.
4. **OS Paging Advisories (`PagingAdvisory`):** Pre-warms the OS page cache for sequential vector scans using Win32 `PrefetchVirtualMemory` on Windows and `posix_madvise(POSIX_MADV_WILLNEED)` / `madvise` on Linux/macOS, with automatic fallback to standardized Java FFM `MemorySegment.load()`.

---

### 2.3 Two-Level Hierarchical Sparse Index

#### The Flaw of Flat Indexing
For a 25 GB log file containing ~215 Million lines:
- Storing a flat 64-bit offset for every line requires:
  $$215 \times 10^6 \times 8\text{ bytes} \approx \mathbf{1.72\text{ GB}}$$
- For a 100 GB file (1 Billion lines), it breaches the memory constraint by requiring **8.0 GB**.

#### The Two-Level Hierarchy
To index 215 Million lines in under **13 MB of RAM**, the engine implements a two-level sparse hierarchy:

```
Line Number L
   │
   ├── superIdx = L >>> 14  (divide by 16,384) ──────────────────────────┐
   │                                                                     │
   └── intraIdx = (L & 0x3FFF) >>> 6  (divide by 64 within superblock)   │
                                                                         ▼
Level 1: Super-Stride Table                       Level 2: Intra-Stride Delta Table
(1 entry per 16,384 lines)                        (256 entries per superblock)
uint64 file_offset (8 bytes)                      uint32 delta_offset (4 bytes)
┌──────────────────────────────────────┐          ┌──────────────────────────────────────┐
│ [0]: Offset to Line 0                │          │ [0..255]: Deltas for SuperBlock 0    │
│ [1]: Offset to Line 16,384           │─────────►│   intraOffset = baseOffset + delta   │
│ [2]: Offset to Line 32,768           │          └──────────────────────────────────────┘
└──────────────────────────────────────┘                                 │
                                                                         ▼
                                                     Starting Byte for SIMD Scan
                                                     (at most 63 lines in L1 cache)
```

1. **Level 1: Super-Stride Table**
   - Interval: $K_{\text{super}} = 16,384$ lines ($2^{14}$).
   - Entry: Absolute 64-bit byte offset (`uint64`, 8 bytes).
   - Lookup: $O(1)$ direct array index via `superIdx = line >>> 14`.
2. **Level 2: Intra-Stride Delta Table**
   - Interval: $K_{\text{intra}} = 64$ lines ($2^6$).
   - Entries per superblock: $16,384 / 64 = 256$ entries.
   - Entry: Relative 32-bit delta offset (`uint32`, 4 bytes):
     $$\Delta_{\text{offset}} = \text{Offset}(\text{Line}_i) - \text{Offset}(\text{Line}_{\text{super}})$$
3. **Lookup Mechanics (Zero Binary Search)**:
   $$\text{superIdx} = L \gg 14$$
   $$\text{intraIdx} = (L \ \& \ 0\text{x}3\text{FFF}) \gg 6$$
   $$\text{baseOffset} = \text{SuperTable}[\text{superIdx}]$$
   $$\text{chunkOffset} = \text{baseOffset} + \text{IntraTable}[(\text{superIdx} \ll 8) + \text{intraIdx}]$$
   $$\text{remainingLines} = L \ \& \ 0\text{x}3\text{F} \quad (\le 63 \text{ lines})$$

#### Memory Footprint Calculations
| Log Line Count | File Size (Approx) | Level 1 (Super-Stride) | Level 2 (Intra-Stride) | Total Off-Heap Memory | Seek Latency |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **10 Million** | ~1.2 GB | 4.88 KB | 625.0 KB | **~630 KB** | $<1.5\ \mu\text{s}$ |
| **215 Million** | **25.0 GB** | **102.4 KB** | **12.80 MB** | **~12.90 MB** | $<1.5\ \mu\text{s}$ |
| **1 Billion** | ~100 GB | 488.28 KB | 62.50 MB | **~62.98 MB** | $<1.5\ \mu\text{s}$ |

---

## 3. Temporal Range Queries & Wall-Clock Binary Seeks ($O(\log N)$)

### 3.1 The Temporal Checkpoint Table
Logs are fundamentally temporal time series. To support queries like *"Jump to 2026-09-21 14:02:10 UTC"* or *"Filter between 14:00 and 14:15"* across 25GB without scanning 215M lines:

```
Off-Heap Checkpoint Table (Sampled every 8,192 lines)
┌─────────────────────────────────────────────────────────────────────────────┐
│ Entry 0   │ Line: 0          │ Epoch Micros: 1774274400000000 (14:00:00.000)│
│ Entry 1   │ Line: 8,192      │ Epoch Micros: 1774274403200000 (14:00:03.200)│
│ Entry 2   │ Line: 16,384     │ Epoch Micros: 1774274406500000 (14:00:06.500)│
│ ...       │ ...              │ ...                                          │
│ Entry 26k │ Line: 214,740,000│ Epoch Micros: 1774278000000000 (15:00:00.000)│
└─────────────────────────────────────────────────────────────────────────────┘
```

1. **Memory Footprint:** 26,214 entries $\times 16\text{ bytes} \approx \mathbf{419\text{ KB}}$ of off-heap memory.
2. **Binary Search Seek:** Locating the nearest 8K-line block for an arbitrary timestamp takes $\log_2(26,214) \approx 15$ comparisons (**$< 1\ \mu\text{s}$**).
3. **Local Fine-Grain Seek:** The SIMD scanner parses timestamps in the targeted 8K-line block to pinpoint the exact line in $<1.5\text{ ms}$.

### 3.2 Dual-Path Monotonic Fast-Path & Out-of-Order Resilience Architecture
Server logs in enterprise deployments fall into two distinct temporal regimes:
1. **Monotonic Logs (99% of Documents):** Sequential single-node or properly serialized logs where timestamps never decrease.
   - **Monotonic Fast-Path:** Executes direct tail-recursive binary search against the raw `epochMicros` array. Resolves line bounds in **$\approx 25\text{ ns}$** with **zero auxiliary array allocations** and zero loops.
2. **Non-Monotonic Logs (Out-of-Order / Concatenated Archives):** Multi-node distributed logs (HDFS, Mesos, Flume), NTP step adjustments, or concatenated rotated files frequently contain non-monotonic jumps backwards in time.
   - **Mathematical Vulnerability of Naive Binary Search:** Standard binary search fails completely on non-monotonic arrays, traversing invalid branches and collapsing range queries into arbitrary single points.
   - **Parallel Indirect Sorted Indexing:** When background indexing detects non-monotonic timestamps, `TemporalCheckpointTable` constructs a parallel indirect sorted index (`sortedCpIndices: Array[Int]`, `sortedTimestamps: Array[Long]`). Binary search targets `sortedTimestamps` in $O(\log N)$, and `seekLineRange` resolves the true minimum and maximum enclosing line boundaries in **$\approx 1.0\ \mu\text{s}$**, eliminating range collapse while maintaining 60–120 FPS scrubbing.

### 3.3 Inter-Record Latency Gap Scanner ($O(1)$ Off-Heap Maps)
Thread freezes, GC pauses, unlogged kernel stalls, and network timeouts manifest as temporal latency cliffs between consecutive log records:
- **Asynchronous Detection:** `LatencyGapScanner` asynchronously evaluates inter-record deltas ($\Delta t = t_{i+1} - t_i$) against warning (default 1,000ms) and severe (default 5,000ms) thresholds.
- **Zero-Allocation Lookups:** Gaps are indexed in a primitive Fastutil `Long2LongOpenHashMap` mapping `LineIndex -> DurationMicros`. The viewport canvas executes $O(1)$ primitive gutter lookups without creating heap objects, rendering amber/red indicator dots and minimap track markers at 120 FPS.

---

## 4. Zero-Copy Log Trimming & Slicing Architecture

When an engineer or an external AI agent requests to **trim a 25GB log** to a 50MB incident window:
- Traditional editors copy strings into heap, serialize them, and write out to disk, consuming gigabytes of RAM.
- Our workstation executes **Zero-Copy Kernel Slicing**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     CROSS-PLATFORM ZERO-COPY SLICING PIPELINE               │
├───────────────────────┬──────────────────────────┬──────────────────────────┤
│ macOS (Darwin / APFS) │ Linux (ext4 / XFS / btrfs│ Windows NT (NTFS / ReFS) │
├───────────────────────┼──────────────────────────┼──────────────────────────┤
│ `clonefile()` /       │ `copy_file_range()` /    │ `TransmitFile()` /       │
│ `fcopyfile(CLONE)`    │ `splice()`               │ Chunked MemorySegment    │
│ Zero disk I/O; shared │ Kernel-level pipe; 0     │ Kernel DMA transfer; 0   │
│ copy-on-write blocks. │ user-space memory copies │ user-space memory copies │
└───────────────────────┴──────────────────────────┴──────────────────────────┘
```

- **Execution Speed:** Exporting a 500MB slice from a 25GB log takes **$< 15\text{ ms}$ on macOS (APFS clone)** and **$< 180\text{ ms}$ on Linux/Windows**.

---

## 5. Throughput Acceleration & SIMD Scanning

### 5.1 Java Vector API (`jdk.incubator.vector.*`)
Umbrix utilizes AVX-512 (64-byte vector registers) on modern x86-64 CPUs, with automatic runtime fallback to AVX2 (32-byte) or ARM NEON (16-byte) via `ByteVector.SPECIES_PREFERRED`.

#### AVX-512 Bit-Indexing Execution Flow
```
Memory Segment (64-Byte Off-Heap Chunk)
[ 0x61 | 0x62 | 0x0A | 0x63 | ... | 0x0A | 0x7A ]
       │
       ▼ (SPECIES_512 Vector Load)
ByteVector: [ b0, b1, '\n', b3, ..., '\n', b63 ]
       │
       ▼ (ByteVector.eq((byte)'\n'))
VectorMask: [ 0,  0,   1,   0,  ...,   1,   0  ]
       │
       ▼ (mask.toLong())
64-bit Bitmask: 0x0000_0000_0000_0004 (Bit 2 and Bit 62 set)
       │
       ├── Fast Path: bitmask == 0L  ──► Advance offset by 64 bytes (1 CPU cycle)
       │
       └── Slow Path: bitmask != 0L  ──► Loop:
                1. idx = Long.numberOfTrailingZeros(bitmask)  [TZCNT]
                2. record_index(currOffset + idx)
                3. bitmask &= (bitmask - 1)                   [BLSR]
```

### 5.2 Proof of UTF-8 Invariance on Byte 0x0A
Can scanning for byte `0x0A` (`\n`) accidentally match bytes within multi-byte UTF-8 sequences?
- UTF-8 encodes codepoints using 1 to 4 bytes:
  - 1-byte (ASCII): `0xxxxxxx` (`0x00` – `0x7F`)
  - 2-byte: `110xxxxx 10xxxxxx`
  - 3-byte: `1110xxxx 10xxxxxx 10xxxxxx`
  - 4-byte: `11110xxx 10xxxxxx 10xxxxxx 10xxxxxx`
- Notice:
  1. All continuation bytes strictly match `10xxxxxx` (`0x80` to `0xBF`).
  2. All multi-byte leading bytes match `11xxxxxx` ($\ge \text{0xC0}$).
  3. ASCII newline `\n` is `0x0A` (`00001010` in binary).
  4. Because the most significant bit of `0x0A` is `0`, **`0x0A` can NEVER appear as a continuation byte or leading byte of any multi-byte UTF-8 codepoint**.
- **Conclusion:** Splitting on byte `0x0A` is 100% mathematically immune to UTF-8 sequence tearing.

### 5.3 Vectorized Case-Insensitive Substring Filtering
For case-insensitive ASCII literal queries (e.g. `"Double"` matching `"double"`, `"Double"`, and `"DOUBLE"`), Umbrix avoids software regex fallback by vectorizing two-byte candidate masks in AVX-512/AVX2:
1. For needle length $M \ge 2$, both lowercase and uppercase bytes for the first byte ($b_0$) and last byte ($b_{M-1}$) are broadcast into vector registers.
2. The vector comparison computes:
   $$\text{Mask}_{\text{first}} = (V_{\text{first}} == b_0^{\text{lower}}) \mid (V_{\text{first}} == b_0^{\text{upper}})$$
   $$\text{Mask}_{\text{last}} = (V_{\text{last}} == b_{M-1}^{\text{lower}}) \mid (V_{\text{last}} == b_{M-1}^{\text{upper}})$$
   $$\text{Mask}_{\text{candidate}} = \text{Mask}_{\text{first}} \ \& \ \text{Mask}_{\text{last}}$$
3. Non-matching chunks are rejected in 1 CPU cycle with $(1/128) \times (1/128) = 1/16,384$ false-positive probability ($99.994\%$ rejection rate).
4. Surviving candidates are verified with branchless ASCII lower-casing: $(b \ge \text{'A'} \ \& \ b \le \text{'Z'}) \ ? \ (b + \text{0x20}) : b$.
5. Sustained throughput reaches **15–25 GB/s cold** and **>60 GB/s in page cache**, returning thousands of matches across 4.56GB in **<250ms cold / 65ms warm**, with progressive background streaming emitting the first visible match in **<6ms**.

### 5.4 Cooperative Cancellation, Thread Headroom & Cross-Platform VM Contention Architecture
High-throughput search across multi-gigabyte files requires careful orchestration between background worker pools and the UI event loop:

1. **Atomic Query Generation Tokens (`currentQueryId`):**
   - Each new search query increments a monotonic `AtomicLong` generation counter.
   - Background worker tasks partition the log into 16MB chunks. Before scanning each chunk, workers verify:
     $$\text{currentQueryId.get()} == \text{queryId} \quad \land \quad \neg\text{Thread.currentThread().isInterrupted()}$$
   - When the user types or clears a query, `session.abortSearch()` increments the token and interrupts active workers. In-flight and queued chunk tasks abort immediately ($<1\text{ ms}$), completely eliminating thread pool queue starvation from rapid typing.

2. **Coroutine Cooperative Cancellation Bridge:**
   - Standard Kotlin Coroutines `withContext(Dispatchers.Default) { blockingJavaCall() }` does not interrupt the underlying thread on cancellation.
   - Umbrix bridges search execution via `kotlinx.coroutines.runInterruptible(Dispatchers.Default)`. Cancelling the search coroutine immediately signals thread interruption to the native SIMD scanning loop.

3. **macOS XNU `vm_map_lock` Contention Resolution:**
   - Previously, invoking `posix_madvise(MADV_DONTNEED)` synchronously per 16MB chunk across all SIMD worker threads caused severe kernel-level lock contention on macOS XNU. In XNU, `madvise` acquires the exclusive writer lock on the process `vm_map`, serializing all scanning threads and inducing 2–5 second startup unresponsiveness.
   - Per-chunk madvise was eliminated from hot scanning loops. Page cache memory eviction is now managed globally at document/session scale, restoring sub-second search initiation on macOS.

4. **Dedicated UI Compositor Headroom:**
   - Worker thread pools are sized according to `PlatformMemoryAdvisor.recommendedBackgroundWorkerCount` ($\max(1, N_{\text{cores}} - 1)$) rather than consuming 100% of available CPU cores. This guarantees dedicated compute bandwidth for Compose Desktop layout, HarfBuzz text shaping, and 60–120 FPS Skia GPU rendering.

### 5.5 Natural Multi-Word Phrase Matching & ReDoS-Immune AST Needle Extraction
During incident investigations, engineers frequently search for multi-word phrases (e.g. `added to`, `connection reset by peer`, `Fatal service shutdown`). Standard Lucene-style tokenization that splits queries on whitespace into implicit `AND` operators causes UX regressions:
1. **Accidental Distributed Matches:** Searching for `added to` matches lines containing `added` and `to` separated by hundreds of arbitrary characters (e.g. `added 1 block ... to node`).
2. **Scatter-Shot Highlight Bleed:** Highlighters that tokenize on whitespace highlight every isolated instance of common words (such as `to` or `in`) across the entire screen.

**Umbrix Architectural Solution:**
- **Consecutive Unquoted Term Merging:** In `QueryParser`, adjacent unquoted terms separated by spaces without boolean operators are parsed as a single atomic `QueryAst.Literal(phraseText, caseSensitive = false)`. Original whitespace formatting is preserved exactly from the input buffer.
- **Explicit Boolean Expressions:** Conjunctions (`AND`, `&&`), disjunctions (`OR`, `||`), negations (`NOT`, `!`), and structured filters (`level:ERROR`, `since:`, `until:`, `/regex/`) remain fully supported. Phrases combine naturally with filters (e.g. `level:ERROR AND added to`).
- **AST-Derived Highlighting Needles (`extractNeedlesJava`):** The viewport highlighter derives search needles directly from the compiled `QueryAst` tree. For `added to`, the highlighter extracts `["added to"]` as a single unit, guaranteeing that only the exact contiguous phrase is highlighted, while isolated words are never highlighted.

---

## 6. Compose Desktop 64-Bit Viewport Virtualization & Tri-Mode Layout

Standard Compose `LazyColumn` fails when line counts exceed $10^7$:
1. **`Int32` Layout Overflow:** At a row height of $20\text{ px}$, virtual canvas heights reach `Int.MAX_VALUE` ($2,147,483,647$) at just **$107,374,182$ lines**, causing negative layout sizes, integer wraps, and immediate crashes.
2. **Float Scrollbar Jitter:** Standard scrollbars use 32-bit `Float` ($24\text{-bit}$ significand = $16,777,216$ discrete steps). In a 200M-line log, a single float step jumps $\approx 12$ lines, causing violent visual stuttering.
3. **Skia Matrix Underflow:** Placing items at global coordinate $Y = 50,000,000 \times 20\text{ px} = 1,000,000,000\text{ px}$ degrades Skia's internal single-precision floating-point matrices, resulting in clipped glyphs and text distortion.

### Custom 64-Bit Sliding Window Viewport
The engine completely bypasses `LazyColumn`, replacing it with `Virtual64Viewport`:
- Maintains a **local sliding window** of visible rows anchored at local screen coordinates $(0, 0)$.
- State is governed by:
  $$\text{Viewport State} = \left\langle L_{\text{top}} \in [0, N - 1], \; \Delta y \in [0.0, h_{\text{line}}) \right\rangle$$
  Where $L_{\text{top}}$ is a 64-bit `Long` and $\Delta y$ is sub-pixel `Float` offset.
- The Skia canvas **never translates past one row height**. All rows are positioned strictly within $[ -h_{\text{line}}, H_{\text{viewport}} + h_{\text{line}} ]$.

### Tri-Mode Configurable Layout Engine
To support diverse enterprise workloads without sacrificing smooth 30–60+ FPS scrolling, Umbrix provides three user-configurable viewport modes:

1. **Mode 1: Compact Fixed Mode (Max Performance):**
   - Every line is exactly $20\text{ px}$. Coordinate math is $O(1)$.
   - Long lines scroll horizontally or truncate; clicking opens the full payload in the bottom detail drawer.
   - Smooth 60–120 FPS performance guaranteed across 200M+ lines.
2. **Mode 2: Expandable Drop-Down Mode (Default Enterprise):**
   - Base lines are fixed at $20\text{ px}$. Lines with stack traces or JSON bodies feature a collapsible chevron `[⯈]`.
   - Expanded lines are registered in a primitive sparse map (`Long2IntOpenHashMap`).
   - Coordinate translation uses sparse binary search ($O(\log k)$ where $k$ is the number of expanded rows, typically $<50$), maintaining 60 FPS scrolling.
3. **Mode 3: Full Wrap Mode:**
   - Multi-line logs wrap to the viewport width using a bounded sliding-window LRU layout cache.
   - Preserves smooth scrolling (30–60 FPS) with a hard safety clamp at 4,096 characters per line to protect Skia text shaping from giant 20MB lines.

> 📚 *For full UI component breakdown, `UmbrixColors` semantic palette, modal dialogs, and keyboard interaction flows, see dedicated specification: **[docs/UI_ARCHITECTURE_AND_VIEWPORT.md](UI_ARCHITECTURE_AND_VIEWPORT.md)**.*

---

## 7. Cross-Platform OS Kernel & GPU Matrix

| Capability | macOS (Darwin) | Windows NT | Linux |
| :--- | :--- | :--- | :--- |
| **GPU Rendering** | Metal (`GrDirectContext.MakeMetal`) | DirectX 12 (`GrDirectContext.MakeDirect3D`) | Vulkan (`GrDirectContext.MakeVulkan`) |
| **Static File I/O** | `mmap` (MAP_SHARED) | Win32 `CreateFileMappingW` | `mmap` (MAP_SHARED) |
| **Active File I/O** | Positional `pread` (SIGBUS-safe) | Positional IOCP `ReadFile` (`FILE_SHARE_DELETE`)| Positional `pread` / `io_uring` |
| **File Change Events**| `kqueue` (`EVFILT_VNODE`) | Win32 `ReadDirectoryChangesW` | `inotify` / `fanotify` |
| **Zero-Copy Slicing** | APFS `clonefile` / `fcopyfile` | `TransmitFile` DMA | `copy_file_range` / `splice` |
| **Paging Advisories** | `madvise(MADV_WILLNEED)` | `PrefetchVirtualMemory` | `madvise(MADV_WILLNEED)` |
| **Vector SIMD** | Apple Silicon ARM NEON (128-bit) | AVX-512 / AVX2 (512/256-bit) | AVX-512 / AVX2 / ARM NEON |

---

## 8. Embedded Model Context Protocol (MCP) Server

The workstation embeds an MCP server allowing **GitHub Copilot, Claude Code, Cursor, and OpenAI Codex** to query active 25GB+ log sessions via JSON-RPC 2.0:
- **`search_logs`**: SIMD-accelerated literal/regex scan across 25GB in $<200\text{ ms}$.
- **`get_time_slice`**: $O(\log N)$ temporal seek returning exact log lines between two ISO-8601 timestamps.
- **`extract_causal_context`**: Isolates unbroken trace sequences by correlation ID.
- **`get_error_clusters`**: Off-heap SimHash clustering collapsing 50,000 errors into 2–3 canonical templates.
- **`trim_and_export`**: Zero-copy kernel export of targeted log slices to disk.

External coding agents debug production outages with **under 1,500 prompt tokens** without ever opening or loading the 25GB file into their own memory.

---

## 9. Architectural Patterns, SOLID & Clean Code Discipline

To guarantee long-term maintainability and prevent structural decay, Umbrix adheres to the following core patterns (detailed in [docs/CODE_GUIDELINES_AND_STANDARDS.md](CODE_GUIDELINES_AND_STANDARDS.md)):

1. **Hexagonal Architecture (Ports & Adapters):** Domain core is isolated from concrete I/O mechanisms and UI libraries. High-level coordinators depend on abstract ports (`LogStorageReader`, `VectorScanner`).
2. **Functional Core, Imperative Shell:** Pure algebraic data types and transformations for queries and layout math; zero-allocation imperative loops for SIMD vector scans and FFM memory pointer arithmetic.
3. **Strictly Decoupled Services (No God Objects):** Single-responsibility services (`LogStorageReader`, `PhysicalLineIndexer`, `EventBoundaryClassifier`, `SimdSearchEngine`, `ViewportCoordinateSystem`).
4. **Compile-Time Constructor Injection:** Zero runtime reflection DI (no Spring/Guice). Explicit dependency graph wired in `UmbrixApplicationContext`.
5. **Zero-Boxing Discipline:** Domain primitives are typed using Scala 3 opaque types (`LineIndex`, `ByteOffset`, `EpochMicros`). No boxed collections (`List[Long]`) on hot paths; use primitive arrays, `fastutil`, or `RoaringBitmap`.

---

## 10. Progressive Background Indexing Architecture

Opening a 25GB – 100GB+ file must display visible lines immediately without blocking on secondary index creation or full-file scanning. Umbrix implements a two-tier, two-phase progressive pipeline inspired by IntelliJ IDEA's smart background indexing:

```
User Opens 25GB – 100GB File
         │
         ▼ (Instantaneous mmap / IOCP handle: <5ms)
┌────────────────────────────────────────────────────────┐
│ Phase 1: Two-Phase Physical Newline Scan               │
│ • Step 1A: Synchronous Bootstrap Chunk 0 (<10ms)       │
│   - SIMD scans first 32MB slice into initial index.    │
│   - First 500,000+ lines ready immediately.            │
│   - UI renders Line 0 to Line 50 in <105ms at 60 FPS.  │
│ • Step 1B: Asynchronous Background Streaming (15–25GB/s)│
│   - Streams remaining chunks to EOF on virtual thread. │
│   - Atomically updates `@volatile var sparseIndex`.    │
│   - Status Bar: "Indexing (SIMD): 34% (10.8GB / 31.8GB)│
└────────────────────────┬───────────────────────────────┘
                         │ Non-blocking Hand-off
                         ▼
┌────────────────────────────────────────────────────────┐
│ Phase 2: Asynchronous Background Semantic Indexing     │
│ • Status Bar: "Analyzing events: 45% (RoaringBitmap)"  │
│ • Fiber A: Event Boundary Classifier (Roaring64Map)    │
│ • Fiber B: Temporal Checkpoint Table (O(log N) seeks)  │
│ • Fiber C: SimHash trace centroid pre-clustering       │
│ • Sleek gradient progress bar (#2196F3 to #00E5FF)     │
│ • Zero UI stutter; CPU pinned to worker carrier threads│
└────────────────────────────────────────────────────────┘
```

Users can scroll, jump, and inspect the raw physical file immediately (<105ms time-to-first-render) while higher-level semantic features (stack trace folding, wall-clock scrubber) become active as background fibers finish. Search operations are supported via zero-boxing primitive navigation (`findNextLine`/`findPreviousLine` at 5M–6M ops/sec) and memoized via a bounded 512-entry LRU `HighlightCache` to maintain 60–120 FPS scrolling across search results.

---

## 11. Asynchronous Off-Heap Piece Table Architecture (File Editing)

To support future non-destructive editing and annotation of 25GB+ files without ingesting data into the JVM heap:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      OFF-HEAP PIECE TABLE ARCHITECTURE                      │
├─────────────────────────────────────────────────────────────────────────────┤
│ 1. Original Buffer: Read-only MemorySegment mapping the 25GB file on disk   │
│ 2. Append Buffer:   Direct off-heap MemorySegment recording all new edits   │
├─────────────────────────────────────────────────────────────────────────────┤
│ 3. Piece Descriptor Sequence (Off-Heap Splay Tree / B-Tree):                │
│    Piece[0]: { Source: ORIGINAL, Offset: 0,          Length: 14,210,000 }   │
│    Piece[1]: { Source: APPEND,   Offset: 0,          Length: 42         }   │
│    Piece[2]: { Source: ORIGINAL, Offset: 14,210,080, Length: 85,000,000 }   │
└─────────────────────────────────────────────────────────────────────────────┘
```

1. **Zero Heap Mutations:** Insertions, deletions, and line redactions never rewrite the 25GB file in memory. Edits append to an off-heap append buffer, while the piece table maintains a lightweight tree of coordinate descriptors.
2. **Asynchronous Kernel Slicing Write-Back:** When saving changes, the engine streams slices directly from the Original Buffer and Append Buffer to a temporary target file via OS kernel primitives (`TransmitFile` on Windows, `copy_file_range` / `splice` on Linux, `clonefile` on macOS) without intermediate user-space byte copies.

---

## 12. Cross-Platform Native Packaging, Single-Instance Routing & Distribution

Umbrix is distributed as a zero-dependency desktop workstation that bundles a minimal modular Java 25 LTS runtime, eliminating JRE installation friction:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       NATIVE PACKAGING & IPC TOPOLOGY                       │
├─────────────────────────────────────────────────────────────────────────────┤
│ 1. Minimal JDK 25 Runtime (jlink):                                          │
│    - Stripped to 85.3 MB via --compress zip-6 and module minimization       │
│    - Includes jdk.incubator.vector, java.desktop, jdk.httpserver, FFM API   │
├─────────────────────────────────────────────────────────────────────────────┤
│ 2. Single-Instance Loopback IPC Coordinator (127.0.0.1:4243):               │
│    Primary Process ◄── TCP Socket ◄── Secondary Process (CLI / Shell Open)  │
│    - Deserializes "OPEN <file>\n" commands                                  │
│    - Brings running window to foreground (toFront() + requestWindowFocus()) │
│    - Secondary process terminates immediately (zero memory overhead)        │
├─────────────────────────────────────────────────────────────────────────────┤
│ 3. Operating System Integration:                                            │
│    - Windows: Zero-admin HKCU Context Menu ("Open with Umbrix") + Inno Setup│
│    - macOS: CFBundleDocumentTypes in Info.plist + Desktop OpenFileHandler   │
│    - Homebrew: Non-admin cask specification (distribution/macos/umbrix.rb)  │
└─────────────────────────────────────────────────────────────────────────────┘
```

1. **Modular JRE Minimization:** `jlink` strips unused JDK modules (compilers, debuggers, man pages) while preserving the high-performance Vector API and FFM Panama runtimes.
2. **Loopback Single-Instance Router:** Secondary application invocations send file paths across a loopback TCP socket to port `4243`, bringing the existing window into focus and preventing multiple concurrent multi-gigabyte index allocations.
3. **User-Space Shell Integration:** Registry scripts and Homebrew casks target user-space directories and `HKCU` keys, ensuring seamless deployment in enterprise environments without administrator privileges.

