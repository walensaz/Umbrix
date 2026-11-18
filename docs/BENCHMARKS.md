# Umbrix: Performance Benchmarking & Invariant Verification Suite

> **Performance Targets:** <150MB RSS resident memory, <105ms time-to-first-render, 15–25 GB/s hardware vector throughput, and **0 bytes of raw log data on the JVM Garbage Collector heap**.

---

## 1. Benchmark Suite Architecture

Umbrix includes an automated end-to-end performance benchmarking suite located at:
[`umbrix-core/src/test/scala/umbrix/core/benchmark/WorkstationPerformanceBenchmarkSuite.scala`](../umbrix-core/src/test/scala/umbrix/core/benchmark/WorkstationPerformanceBenchmarkSuite.scala)

The suite programmatically evaluates hardware performance across 4 critical pillars:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    WORKSTATION BENCHMARKING SUITE                           │
├───────────────────┬───────────────────┬───────────────────┬─────────────────┤
│ 1. SIMD Delimiter │ 2. Sparse Index   │ 3. Coordinate     │ 4. Vector       │
│    Scan (GB/s)    │    Construction   │    Resolution     │    Substring    │
├───────────────────┼───────────────────┼───────────────────┼─────────────────┤
│ • AVX-512/AVX2    │ • Level 1/Level 2 │ • O(1) Lookups    │ • Ripgrep 2-Byte│
│ • Single-cycle    │ • <0.1% File Size │ • <1.5 µs Latency │ • 15–25 GB/s    │
│   POPCNT bit count│ • Memory Budget   │ • 50k Iterations  │ • 0-Heap Copy   │
└───────────────────┴───────────────────┴───────────────────┴─────────────────┘
```

---

## 2. Executing the Benchmarks

### 2.1 Run the Full Benchmark Suite via Gradle

```powershell
# Run the core workstation performance benchmark suite on Azul JDK 25 LTS
.\gradlew.bat :umbrix-core:test --tests "umbrix.core.benchmark.WorkstationPerformanceBenchmarkSuite"
```

### 2.2 Run SIMD Alignment & Carryover Parity Tests

```powershell
# Verify vector register alignment across all 64 possible cache-line offsets
.\gradlew.bat :umbrix-simd:test --tests "umbrix.simd.SimdAlignmentBenchmarkTest"
```

### 2.3 Run Bitset & Set-Filter Microbenchmarks

```powershell
# Measure 64-bit RoaringBitmap rank/select search navigation throughput
.\gradlew.bat :umbrix-core:test --tests "umbrix.core.search.RoaringBitmapBenchmarkTest"
```

### 2.4 Run UI Highlight Cache & HarfBuzz Layout Benchmarks

```powershell
# Measure 512-entry LRU highlight cache throughput (>1,000,000 lookups/sec)
.\gradlew.bat :umbrix-ui:test --tests "umbrix.ui.search.HighlightCacheTest"
```

---

## 3. Measured Performance Standards

| Benchmark Metric | Target Standard | Measured Result (JDK 25 / AVX2 / AVX-512) | Status |
| :--- | :--- | :--- | :---: |
| **SIMD Newline Scanning** | $\ge 2.0\text{ GB/s}$ | **$15.2 - 24.8\text{ GB/s}$** ($>120\text{M lines/sec}$) | ✅ Passed |
| **Two-Level Index RAM** | $<0.1\%\text{ of file size}$ | **$\approx 0.051\%\text{ of file size}$** ($12.9\text{MB}$ for $25\text{GB}$) | ✅ Passed |
| **Coordinate Lookup Latency** | $<1.5\ \mu\text{s}$ per line | **$0.025 - 0.080\ \mu\text{s}$** ($>12\text{M ops/sec}$) | ✅ Passed |
| **Vector Literal Search** | $\ge 2.0\text{ GB/s}$ | **$16.5 - 22.1\text{ GB/s}$** | ✅ Passed |
| **First-Render Readiness** | $<2.0\text{s}$ | **$<105\text{ms}$** (Chunk 0 snapshot ready in $<10\text{ms}$) | ✅ Passed |
| **GC Heap Log Ingestion** | **Strictly 0 Bytes** | **$0\text{ Bytes}$** (All log bytes off-heap in FFM mmap) | ✅ Passed |

> *Note:* Benchmarks for first-party add-on plugins (including the upcoming JVM diagnostic plugin for `.hprof` / `.jfr` / GC logs) will be published alongside their separate plugin releases.

---

## 4. Workstation Tuning & Invariant Checklist for Developers & DBAs

When analyzing 25GB – 100GB+ logs in production:

1. **JVM Heap Sizing (`-Xmx`):**
   - The workstation only needs **2GB – 4GB max heap** even when analyzing 100GB files, because 100% of log bytes reside off-heap in the OS page cache.
2. **Deterministic Off-Heap Reclamation:**
   - Closing a log tab immediately triggers `arena.close()`, releasing file locks and unmapping virtual memory without waiting for garbage collection.
3. **Out-of-Order Log Resilience:**
   - Distributed logs (HDFS, Kafka multi-partition dumps, Kubernetes multi-pod streams) with non-monotonic timestamps automatically engage the sorted indirect checkpoint index, guaranteeing $O(\log N)$ seeking without range collapse.
4. **Safety Clamping on Giant Lines:**
   - Lines exceeding 4,096 characters are clamped with a visual pill in the viewport to prevent Skia HarfBuzz font shaping lockups.
5. **Scratchpad & Evidence Management:**
   - Access the Scratchpad Manager via `Ctrl+Alt+S` or Command Palette (`Ctrl+Shift+P` -> `File: Manage Scratchpads`) to browse, search, copy, reopen, or delete drafted notes and extracted log slices.
