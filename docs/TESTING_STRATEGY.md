# Umbrix — CI, Benchmarking & Testing Strategy

> **System Mission Invariant:** Strict <150MB RSS memory, sub-second to <2s initial screen render, and 0 bytes of raw log data ingested into the JVM Garbage Collector heap.

---

## 1. Test Architecture Overview

Umbrix employs a multi-tiered, headless testing architecture designed for deterministic execution in CI/CD pipelines (GitHub Actions, Linux/Windows/macOS runners) and local development workstations.

| Layer | Framework | Modules | Focus |
| :--- | :--- | :--- | :--- |
| **Core & Domain** | **MUnit 3 (`1.0.4`)** | `:umbrix-core`, `:umbrix-index` | Zero-boxing opaque types, 64-bit RoaringBitmap, two-level sparse index arithmetic, query AST parser |
| **Native & SIMD** | **MUnit 3 (`1.0.4`)** | `:umbrix-simd`, `:umbrix-vfs` | AVX-512 / AVX2 vector kernels, 64-byte alignment prologue, FFM Panama downcalls, Win32 IOCP / POSIX pread |
| **MCP & Agent Bridge** | **MUnit 3 (`1.0.4`)** | `:umbrix-mcp` | Zero-allocation `jsoniter-scala` serialization, jtokkit BPE token counting |
| **UI State & Telemetry** | **JUnit 5 + `kotlin.test`** | `:umbrix-ui` | Headless Compose state, LRU `HighlightCache`, length clamping (`4,096` chars), forensic error interceptor |
| **App Composition** | **MUnit 3 (`1.0.4`)** | `:umbrix-app` | Entry point launcher, CLI arguments parsing, DI composition root |

---

## 2. Headless UI Testing Protocol

To ensure UI tests execute deterministically on headless CI servers without X11, Wayland, or desktop display servers:
1. **No Live Window Spawning:** UI unit tests never invoke `application { Window { ... } }` directly.
2. **State & Pure Model Verification:** Tests exercise Compose reactive states (`WorkspaceTab`, `WorkstationKeyDispatcher`, `ViewportState`), annotated string builders, and error handlers directly as pure JVM unit tests.
3. **Safety Clamping Tests:** `Virtual64ViewportTest.kt` verifies that lines exceeding `4,096` characters are clamped to prevent Skia font shaping exhaustion.
4. **Cache & Throughput Benchmarks:** `HighlightCacheTest.kt` verifies LRU eviction (strictly capped at 512 entries) and executes microbenchmarks exceeding 1,000,000 calls/sec without intermediate allocations.

---

## 3. Native & Memory Safety Verification

1. **Circular Buffer Ring Safety:** `PositionalLogReaderLeakTest.scala` asserts that consecutive multi-megabyte `readSlice` invocations cycle through the pre-allocated 16MB ring buffer without increasing JVM or native RSS.
2. **Chunked PieceTable Safety:** `PieceTableLeakTest.scala` verifies that append mutations allocate fixed 1MB chunks (`ArrayList[MemorySegment]`) without leaving orphaned or unmapped segments upon table expansion.
3. **Deterministic Arena Cleanliness:** All native file access operates under explicit `Arena` scopes (`Arena.ofShared()` or `Arena.ofConfined()`). Cleaners and finalizers are never used for native resource reclamation.

---

## 4. Performance & SIMD Alignment Benchmarks

Microbenchmarks and alignment tests verify hardware throughput across platforms:
- **`VectorScannerTest.scala`**: Tests newline (`0x0A`) counting across all 64 possible byte misalignments, ensuring the scalar alignment prologue correctly aligns pointers to cache line boundaries before entering the AVX-512/AVX2 loop.
- **`SimdAlignmentBenchmarkTest.scala`**: Verifies sustained throughput between 13.3 GB/s and 14.3 GB/s across unaligned memory blocks on real log data.
- **`ProgressiveIndexingBenchmarkTest.scala`**: Validates the Two-Phase indexing pipeline:
  - *Tier 1 (Physical scan)*: Immediate UI display (<150ms on multi-gigabyte logs).
  - *Tier 2 (Logical & Temporal)*: Non-blocking background worker fiber populating `Roaring64NavigableMap` event boundaries and temporal checkpoints.

---

## 5. Gradle Verification Commands

### Full Verification Suite
```bash
./gradlew test --console=plain -q
```
Executes all actionable compile and test tasks across the public modules against Azul OpenJDK 25 LTS via Gradle toolchains.

### Targeted Module Testing
```bash
./gradlew :umbrix-simd:test --console=plain -q   # SIMD vector kernels & alignment
./gradlew :umbrix-vfs:test --console=plain -q    # Cross-platform off-heap I/O
./gradlew :umbrix-index:test --console=plain -q  # Two-level sparse index & piece table
./gradlew :umbrix-core:test --console=plain -q   # Session coordinator & query engine
./gradlew :umbrix-ui:test --console=plain -q     # Headless UI state & highlight cache
./gradlew :umbrix-mcp:test --console=plain -q    # MCP server & JSON-RPC serialization
```

### Production Packaging Verification
```bash
./gradlew :umbrix-app:shadowJar --console=plain -q
```
Verifies fat JAR assembly (`umbrix-1.0.1-all.jar`), `META-INF` signature exclusion, SPI service file merging, and entry point manifest configuration.

---

## 6. Actionable Benchmark Commands

For full benchmark metrics and architectural pillars, see [`BENCHMARKS.md`](BENCHMARKS.md).

```powershell
# 1. Full Core Workstation Performance Suite
.\gradlew.bat :umbrix-core:test --tests "umbrix.core.benchmark.WorkstationPerformanceBenchmarkSuite" --console=plain -q

# 2. SIMD Alignment & Carryover Parity Tests across 64 offsets
.\gradlew.bat :umbrix-simd:test --tests "umbrix.simd.SimdAlignmentBenchmarkTest" --console=plain -q

# 3. 64-Bit RoaringBitmap Search Navigation Throughput
.\gradlew.bat :umbrix-core:test --tests "umbrix.core.search.RoaringBitmapBenchmarkTest" --console=plain -q

# 4. UI 512-Entry LRU Highlight Cache & HarfBuzz Benchmarks
.\gradlew.bat :umbrix-ui:test --tests "umbrix.ui.search.HighlightCacheTest" --console=plain -q
```

---

## 7. Pre-PR & Release Verification Checklist

Every pull request or major feature implementation must pass this checklist prior to merging:

- [ ] **Zero Heap Ingestion:** Raw log bytes are kept off-heap. No `new String(bytes)` inside indexing, search, or streaming loops.
- [ ] **Deterministic Arena Scope:** All native allocations bound to explicit `Arena.ofShared()` or `Arena.ofConfined()`.
- [ ] **Linear-Time Regex:** All regular expressions run via `com.google.re2j` automata (no `java.util.regex`).
- [ ] **Explicit Constructor DI:** Singletons wired in `umbrix-app` composition root (no reflection/runtime DI).
- [ ] **Zero Boxing on Hot Paths:** Primitive types wrapped in Scala 3 `opaque type`; collections use `fastutil` or 64-bit `Roaring64NavigableMap`.
- [ ] **JDK 25 LTS Toolchain:** Clean compilation with `--enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector`.
- [ ] **Cross-Platform I/O Safety:** Windows Overlapped `ReadFile` sharing modes; Unix positional `pread`/`io_uring` `SIGBUS` immunity.
- [ ] **Trait `override` Discipline:** Abstract trait methods implemented without `override`; only concrete overrides use `override`.
- [ ] **No Early `return`:** Pure functional expressions, `@tailrec` recursion, `.view`, and `foldLeft`.
- [ ] **Descriptive Identifier Naming:** No cryptic single-letter abbreviations (`n`, `v`, `l`, `s1`, `buf`).
- [ ] **High-Signal Comments:** Inline comments explain *why* non-obvious architecture exists; no syntax restatement.
- [ ] **Classical Curly Braces:** All Scala 3 code strictly formatted with `{ ... }` (no Python-style indentation `:` / `then`).
- [ ] **4,096-Char Clamping:** Display logic clamps long lines with `[... +XX KB payload]` to protect Skia text shaping.
- [ ] **Test & Packaging Passes:** `./gradlew test --console=plain -q` and `./gradlew :umbrix-app:shadowJar --console=plain -q` succeed.
- [ ] **Documentation Synchronized:** Affected specifications in `docs/` and `README.md` updated in the same turn.
